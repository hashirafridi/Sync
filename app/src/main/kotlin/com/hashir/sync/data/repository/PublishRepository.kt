package com.hashir.sync.data.repository

import android.net.Uri
import com.hashir.sync.api.publish.PublishApi
import com.hashir.sync.api.publish.PublishResult
import com.hashir.sync.api.publish.UploadProgress
import com.hashir.sync.api.publish.VideoPublishApi
import com.hashir.sync.data.local.FbPageEntity
import com.hashir.sync.data.local.PageDao
import com.hashir.sync.data.media.MediaResolver
import com.hashir.sync.data.media.VideoNormalizer
import com.hashir.sync.data.security.EncryptedTokenStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishing, with the Android-specific parts resolved before handing off to `:core-api`.
 *
 * Two things happen here that cannot happen inside `:core-api`: looking up the Page's own
 * access token, and turning a `content://` URI into a real file.
 */
@Singleton
class PublishRepository @Inject constructor(
    private val publishApi: PublishApi,
    private val videoPublishApi: VideoPublishApi,
    private val tokenStore: EncryptedTokenStore,
    private val pageDao: PageDao,
    private val mediaResolver: MediaResolver,
    private val videoNormalizer: VideoNormalizer,
) {

    suspend fun publishText(
        pageId: String,
        message: String,
        link: String? = null,
        scheduledForEpochSeconds: Long? = null,
    ): Result<PublishResult> = runCatching {
        val (_, token) = requirePublishable(pageId)
        publishApi.publishText(
            pageId = pageId,
            pageToken = token,
            message = message,
            link = link,
            scheduledForEpochSeconds = scheduledForEpochSeconds,
        )
    }

    suspend fun publishPhoto(
        pageId: String,
        photoUri: Uri,
        caption: String,
        scheduledForEpochSeconds: Long? = null,
    ): Result<PublishResult> = runCatching {
        val (_, token) = requirePublishable(pageId)
        val file = mediaResolver.toFile(photoUri)
        publishApi.publishPhoto(
            pageId = pageId,
            pageToken = token,
            photo = file,
            caption = caption,
            scheduledForEpochSeconds = scheduledForEpochSeconds,
        )
    }

    /**
     * Publishes a video, preferring Reels and falling back automatically.
     *
     * [resumeFromOffset] comes from the job row, so an upload killed by the OS restarts
     * from where it stopped rather than pushing a large file again from zero.
     * [onProgress] reports bytes sent so that offset can be persisted as it goes.
     */
    suspend fun publishVideo(
        pageId: String,
        videoUri: Uri,
        caption: String,
        scheduledForEpochSeconds: Long? = null,
        resumeFromOffset: Long = 0,
        onTranscodeStart: () -> Unit = {},
        onProgress: (UploadProgress) -> Unit = {},
    ): Result<PublishResult> = runCatching {
        val (_, token) = requirePublishable(pageId)
        val staged = mediaResolver.toFile(videoUri)

        // Almost always a no-op: the downloader asks for MP4/H.264/AAC up front. This
        // catches the sources that only offer VP9 or Opus, which Facebook rejects.
        val file = if (videoNormalizer.needsTranscode(staged)) {
            onTranscodeStart()
            videoNormalizer.ensureCompatible(staged)
        } else {
            staged
        }

        videoPublishApi.publishVideo(
            pageId = pageId,
            pageToken = token,
            video = file,
            description = caption,
            scheduledForEpochSeconds = scheduledForEpochSeconds,
            // Only meaningful if the staged file is the same one; a re-staged copy is
            // byte-identical, so the offset stays valid.
            resumeFromOffset = resumeFromOffset.coerceAtMost(file.length()),
            onProgress = onProgress,
        )
    }

    /**
     * Resolves a Page and its token, refusing early when publishing cannot possibly work.
     *
     * Checking `canCreateContent` here rather than letting Facebook reject the call means
     * the user finds out before they have written a caption and waited through an upload,
     * and the message names the actual cause instead of a numeric error code.
     */
    private suspend fun requirePublishable(pageId: String): Pair<FbPageEntity, String> {
        val page = pageDao.getById(pageId)
            ?: error("That Page is no longer connected. Refresh the account and try again.")

        if (!page.canCreateContent) {
            error("You do not have permission to publish to ${page.name}.")
        }

        val token = tokenStore.getPageToken(pageId)
            ?: error("No stored token for ${page.name}. Reconnect the account that owns it.")

        return page to token
    }
}
