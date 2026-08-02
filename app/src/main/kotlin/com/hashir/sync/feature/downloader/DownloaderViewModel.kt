package com.hashir.sync.feature.downloader

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hashir.sync.data.local.DownloadDao
import com.hashir.sync.data.local.DownloadRecordEntity
import com.hashir.sync.data.media.GalleryExporter
import com.hashir.sync.downloader.DownloadProgress
import com.hashir.sync.downloader.FetchInfo
import com.hashir.sync.downloader.YtDlpMediaFetcher
import com.hashir.sync.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
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

data class DownloaderUiState(
    val url: String = "",
    val isProbing: Boolean = false,
    val info: FetchInfo? = null,
    val selectedHeight: Int? = null,
    val isDownloading: Boolean = false,
    val percent: Float = 0f,
    val etaSeconds: Long = -1,
    val error: String? = null,
) {
    val canFetch: Boolean get() = url.isNotBlank() && !isProbing && !isDownloading
    val canDownload: Boolean get() = info != null && selectedHeight != null && !isDownloading
}

/** Emitted when a download finishes, carrying the row the editor should load. */
data class DownloadReady(val downloadId: Long)

@HiltViewModel
class DownloaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val fetcher: YtDlpMediaFetcher,
    private val downloadDao: DownloadDao,
    private val galleryExporter: GalleryExporter,
) : ViewModel() {

    /** Shown after a download so the user knows where the file went. */
    val savedFolderLabel: String get() = galleryExporter.publicFolderLabel()

    val pageId: String = checkNotNull(savedStateHandle[Routes.ARG_PAGE_ID]) {
        "Downloader opened without a page id."
    }

    private val _uiState = MutableStateFlow(DownloaderUiState())
    val uiState: StateFlow<DownloaderUiState> = _uiState.asStateFlow()

    private val readyChannel = Channel<DownloadReady>(Channel.BUFFERED)
    val downloadReady: Flow<DownloadReady> = readyChannel.receiveAsFlow()

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    private var downloadJob: Job? = null

    init {
        // Unpacking the Python runtime is the bulk of the wait on the first Fetch. Doing
        // it as soon as the screen opens means it overlaps with the user pasting a link,
        // instead of being charged to their first tap.
        viewModelScope.launch { runCatching { fetcher.ensureInitialised() } }
    }

    fun onUrlChanged(value: String) = _uiState.update { it.copy(url = value, error = null) }

    fun clearUrl() = _uiState.update {
        it.copy(url = "", info = null, selectedHeight = null, error = null)
    }

    /** Offers a link already on the clipboard, which is nearly always why the user is here. */
    fun offerClipboard(candidate: String?) {
        if (candidate.isNullOrBlank()) return
        if (_uiState.value.url.isNotBlank()) return
        if (!candidate.startsWith("http", ignoreCase = true)) return
        _uiState.update { it.copy(url = candidate.trim()) }
    }

    fun selectHeight(height: Int) = _uiState.update { it.copy(selectedHeight = height) }

    fun fetch() {
        val url = _uiState.value.url.trim()
        if (url.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isProbing = true, error = null, info = null) }
            runCatching { fetcher.probe(url) }
                .onSuccess { info ->
                    _uiState.update {
                        it.copy(
                            isProbing = false,
                            info = info,
                            // Preselect 1080p, or the source maximum when it is lower.
                            selectedHeight = info.defaultHeight,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isProbing = false, error = describe(error)) }
                }
        }
    }

    fun download() {
        val state = _uiState.value
        val info = state.info ?: return
        val height = state.selectedHeight ?: return

        downloadJob = viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, percent = 0f, error = null) }

            fetcher.download(
                url = info.sourceUrl,
                targetHeight = height,
                // App-scoped external files: a real path the yt-dlp process can write to,
                // and no storage permission of any kind.
                outputDir = downloadDir(),
            ).collect { progress ->
                when (progress) {
                    is DownloadProgress.Running -> _uiState.update {
                        it.copy(percent = progress.percent, etaSeconds = progress.etaSeconds)
                    }

                    is DownloadProgress.Completed -> onDownloaded(info, height, progress.file)

                    is DownloadProgress.Failed -> _uiState.update {
                        it.copy(isDownloading = false, error = describe(progress.error))
                    }
                }
            }
        }
    }

    fun cancel() {
        downloadJob?.cancel()
        downloadJob = null
        _uiState.update { it.copy(isDownloading = false, percent = 0f) }
    }

    /**
     * Updates yt-dlp in place.
     *
     * Sites change their players constantly and extractors break with them, so a bundled
     * copy goes stale within months. This is what avoids rebuilding and reinstalling the
     * APK on every phone each time that happens.
     */
    fun updateDownloader() {
        viewModelScope.launch {
            _uiState.update { it.copy(isProbing = true, error = null) }
            runCatching { fetcher.update(nightly = true) }
                .onSuccess { result ->
                    _uiState.update { it.copy(isProbing = false) }
                    messageChannel.send("Downloader updated: $result")
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isProbing = false) }
                    messageChannel.send("Update failed: ${describe(error)}")
                }
        }
    }

    private suspend fun onDownloaded(info: FetchInfo, height: Int, file: File) {
        // Move it somewhere the user can actually find. If the export fails we keep the
        // original rather than losing the download; it just stays invisible to the gallery.
        val exported = galleryExporter.exportVideo(file, info.title?.let { "$it.mp4" })
        val storedPath = exported?.toString() ?: file.absolutePath

        val id = downloadDao.upsert(
            DownloadRecordEntity(
                sourceUrl = info.sourceUrl,
                title = info.title,
                description = info.description,
                localPath = storedPath,
                thumbnailUrl = info.thumbnailUrl,
                durationMs = (info.durationSeconds ?: 0L) * 1000,
                width = 0,
                height = height,
                heightPicked = height,
                downloadedAt = System.currentTimeMillis(),
            ),
        )
        _uiState.update { it.copy(isDownloading = false, percent = 100f) }
        messageChannel.send("Saved to ${galleryExporter.publicFolderLabel()}")
        readyChannel.send(DownloadReady(id))
    }

    private fun downloadDir(): File =
        File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "downloads")
            .apply { mkdirs() }

    /**
     * yt-dlp failures are long multi-line stderr dumps. Showing the whole thing is
     * useless; these map the common ones onto something a person can act on.
     */
    private fun describe(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("Unsupported URL", ignoreCase = true) ->
                "That link is not supported."

            raw.contains("Private video", ignoreCase = true) ||
                raw.contains("login required", ignoreCase = true) ||
                raw.contains("Sign in", ignoreCase = true) ->
                "That video is private or needs a sign-in, so it cannot be downloaded."

            raw.contains("Video unavailable", ignoreCase = true) ||
                raw.contains("not available", ignoreCase = true) ->
                "That video is unavailable. It may have been removed or is region locked."

            // Instagram, TikTok and YouTube all break this way when the bundled yt-dlp
            // falls behind. It is the most common failure over time and the one with a
            // real fix, so the message names the fix rather than the symptom.
            raw.contains("out of date", ignoreCase = true) ||
                raw.contains("update to", ignoreCase = true) ||
                raw.contains("yt-dlp is out", ignoreCase = true) ||
                raw.contains("Unable to extract", ignoreCase = true) ||
                raw.contains("nsig", ignoreCase = true) ->
                "The downloader is out of date for this site. Tap the menu at the top " +
                    "right and choose Update downloader, then try again."

            raw.contains("Unable to download", ignoreCase = true) ||
                raw.contains("timed out", ignoreCase = true) ->
                "Network problem while downloading. Check the connection and try again."

            raw.isBlank() -> "The download failed."

            else -> raw.lineSequence().firstOrNull { it.isNotBlank() }?.take(200)
                ?: "The download failed."
        }
    }
}
