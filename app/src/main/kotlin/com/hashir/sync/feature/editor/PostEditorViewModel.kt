package com.hashir.sync.feature.editor

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hashir.sync.data.local.DownloadDao
import com.hashir.sync.data.local.PageDao
import com.hashir.sync.data.media.MediaResolver
import com.hashir.sync.data.repository.PublishQueue
import com.hashir.sync.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

enum class EditorMode {
    /** Photo or video picked from the device. */
    MEDIA,

    /** Caption only, optionally with a link. */
    TEXT,
}

data class PostEditorUiState(
    val mode: EditorMode = EditorMode.MEDIA,
    val pageName: String = "",
    val mediaUri: Uri? = null,
    val mediaName: String? = null,
    val mediaSizeLabel: String? = null,
    val isVideo: Boolean = false,
    val caption: String = "",
    val link: String = "",
    val isPublishing: Boolean = false,
    /** Epoch millis, or null to publish immediately. */
    val scheduledFor: Long? = null,
    /**
     * How many more videos this Page can have published through the API today.
     * Facebook caps it at 30 per Page per 24 hours; null while it is being counted.
     */
    val videoQuotaRemaining: Int? = null,
) {
    val hasMedia: Boolean get() = mediaUri != null

    val isScheduled: Boolean get() = scheduledFor != null

    /** True once the Page has used its 30 API-published videos for the day. */
    val videoQuotaExhausted: Boolean
        get() = isVideo && videoQuotaRemaining != null && videoQuotaRemaining <= 0

    val canPublish: Boolean
        get() = !isPublishing && !videoQuotaExhausted && when (mode) {
            EditorMode.MEDIA -> hasMedia
            EditorMode.TEXT -> caption.isNotBlank() || link.isNotBlank()
        }
}

/** Emitted once the post has been queued, so the screen can navigate away. */
data class PublishQueued(val jobId: Long)

@HiltViewModel
class PostEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val publishQueue: PublishQueue,
    private val mediaResolver: MediaResolver,
    private val pageDao: PageDao,
    private val downloadDao: DownloadDao,
) : ViewModel() {

    private val pageId: String = checkNotNull(savedStateHandle[Routes.ARG_PAGE_ID]) {
        "Editor opened without a page id."
    }

    private val _uiState = MutableStateFlow(
        PostEditorUiState(
            mode = runCatching {
                EditorMode.valueOf(savedStateHandle[Routes.ARG_MODE] ?: EditorMode.MEDIA.name)
            }.getOrDefault(EditorMode.MEDIA),
        ),
    )
    val uiState: StateFlow<PostEditorUiState> = _uiState.asStateFlow()

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    private val queuedChannel = Channel<PublishQueued>(Channel.BUFFERED)
    val publishQueued: Flow<PublishQueued> = queuedChannel.receiveAsFlow()

    private val downloadId: Long =
        savedStateHandle[Routes.ARG_DOWNLOAD_ID] ?: Routes.NO_DOWNLOAD

    init {
        viewModelScope.launch {
            pageDao.getById(pageId)?.let { page ->
                _uiState.update { it.copy(pageName = page.name) }
            }
            if (downloadId != Routes.NO_DOWNLOAD) loadDownload(downloadId)
        }
    }

    /**
     * Pre-loads a freshly downloaded file, so the user lands in the editor with the video
     * and its original caption already in place and only has to press Publish.
     */
    private suspend fun loadDownload(id: Long) {
        val record = downloadDao.getById(id) ?: return

        // localPath is a MediaStore content URI once the download has been moved into the
        // public Movies folder, and a plain file path only when that export failed.
        val uri: Uri
        val name: String?
        val size: Long?

        if (record.localPath.startsWith("content://")) {
            uri = Uri.parse(record.localPath)
            name = mediaResolver.displayName(uri)
            size = mediaResolver.sizeBytes(uri)
            if (name == null && size == null) {
                messageChannel.send("That download is no longer on the device.")
                return
            }
        } else {
            val file = File(record.localPath)
            if (!file.isFile) {
                messageChannel.send("That download is no longer on the device.")
                return
            }
            uri = Uri.fromFile(file)
            name = file.name
            size = file.length()
        }

        _uiState.update {
            it.copy(
                mediaUri = uri,
                mediaName = name,
                mediaSizeLabel = size?.let(::formatBytes),
                // Everything the downloader produces is video; that is the whole point.
                isVideo = true,
                // Extractors disagree about where the real caption lives, so FetchInfo
                // already resolved it. Prefer description, fall back to title.
                caption = record.description?.takeIf { text -> text.isNotBlank() }
                    ?: record.title.orEmpty(),
            )
        }
    }

    fun onMediaPicked(uri: Uri?) {
        if (uri == null) return
        val isVideo = mediaResolver.isVideo(uri)
        _uiState.update {
            it.copy(
                mediaUri = uri,
                mediaName = mediaResolver.displayName(uri),
                mediaSizeLabel = mediaResolver.sizeBytes(uri)?.let(::formatBytes),
                isVideo = isVideo,
            )
        }
        // Counted only once a video is actually selected -- the limit is irrelevant to
        // photos and text, and querying it every time would be noise.
        if (isVideo) viewModelScope.launch { refreshVideoQuota() }
    }

    fun onCaptionChanged(value: String) = _uiState.update { it.copy(caption = value) }

    fun onLinkChanged(value: String) = _uiState.update { it.copy(link = value) }

    fun clearMedia() = _uiState.update {
        it.copy(mediaUri = null, mediaName = null, mediaSizeLabel = null, isVideo = false)
    }

    /**
     * Facebook requires a scheduled post to be at least 10 minutes out and no more than
     * 75 days. Clamping here rather than letting the API reject it means the user finds
     * out while they can still change it, instead of after the upload.
     */
    fun setSchedule(epochMillis: Long) {
        val earliest = System.currentTimeMillis() + MIN_SCHEDULE_LEAD_MS
        _uiState.update { it.copy(scheduledFor = maxOf(epochMillis, earliest)) }
    }

    fun clearSchedule() = _uiState.update { it.copy(scheduledFor = null) }

    private suspend fun refreshVideoQuota() {
        val used = publishQueue.videosPublishedToday(pageId)
        _uiState.update { it.copy(videoQuotaRemaining = (DAILY_VIDEO_LIMIT - used).coerceAtLeast(0)) }
    }

    /**
     * Hands the post to the queue and returns immediately.
     *
     * Deliberately does not await the upload. A large photo over a phone connection can
     * take a while, and holding the user on a spinner means backgrounding the app kills
     * the post. Queued work survives that; outcomes appear in the queue screen.
     */
    fun publish() {
        val state = _uiState.value
        if (!state.canPublish) return

        viewModelScope.launch {
            _uiState.update { it.copy(isPublishing = true) }

            val jobId = when (state.mode) {
                EditorMode.MEDIA -> publishQueue.enqueueMedia(
                    pageId = pageId,
                    mediaUri = requireNotNull(state.mediaUri).toString(),
                    caption = state.caption,
                    isVideo = state.isVideo,
                    scheduledFor = state.scheduledFor,
                )

                EditorMode.TEXT -> publishQueue.enqueueText(
                    pageId = pageId,
                    message = buildTextMessage(state),
                    scheduledFor = state.scheduledFor,
                )
            }

            queuedChannel.send(PublishQueued(jobId))
            _uiState.update { it.copy(isPublishing = false) }
        }
    }

    /**
     * Facebook stopped honouring the separate `link` parameter for Page posts some years
     * ago; a URL in the message body is what actually produces a link preview. So the two
     * fields are joined rather than sent separately.
     */
    private fun buildTextMessage(state: PostEditorUiState): String = when {
        state.link.isBlank() -> state.caption
        state.caption.isBlank() -> state.link
        else -> "${state.caption}\n\n${state.link}"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }

    private companion object {
        /** Facebook's cap on API-published reels per Page per 24 hours. */
        const val DAILY_VIDEO_LIMIT = 30

        /**
         * Facebook rejects anything scheduled less than 10 minutes ahead. The extra
         * minute absorbs the time between choosing and the upload actually finishing.
         */
        const val MIN_SCHEDULE_LEAD_MS = 11 * 60 * 1000L
    }
}
