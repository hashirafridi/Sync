package com.hashir.sync.api.publish

import com.hashir.sync.api.GraphClient
import com.hashir.sync.api.GraphError
import com.hashir.sync.api.model.ReelStartResponse
import com.hashir.sync.api.model.VideoFinishResponse
import com.hashir.sync.api.model.VideoStatusResponse
import kotlinx.coroutines.delay
import okhttp3.FormBody
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.RandomAccessFile

/** How far along an upload is, for progress reporting. */
data class UploadProgress(val bytesSent: Long, val totalBytes: Long) {
    val percent: Int get() = if (totalBytes <= 0) 0 else ((bytesSent * 100) / totalBytes).toInt()
}

/**
 * Publishing video to a Page.
 *
 * Facebook merged videos and Reels in 2025 -- everything uploaded becomes a Reel and the
 * old duration limits were dropped. But the v25.0 docs still describe 3-90 second, 9:16
 * constraints on the Reels endpoint, and `/videos` still exists separately. Rather than
 * guess which is true, [publishVideo] always attempts Reels first and silently falls back
 * to `/videos` only when the API actually complains. That way the app self-corrects
 * whichever way Facebook really behaves, and the user is never asked to choose.
 *
 * The Reels path is three phases on two different hosts:
 *
 * ```
 * 1. start   POST graph.facebook.com/{page-id}/video_reels?upload_phase=start
 *            -> video_id
 * 2. upload  POST rupload.facebook.com/video-upload/{version}/{video_id}
 *            headers: Authorization: OAuth <token>, offset, file_size
 * 3. finish  POST graph.facebook.com/{page-id}/video_reels?upload_phase=finish
 * ```
 */
class VideoPublishApi(private val client: GraphClient) {

    /**
     * Publishes a video, preferring Reels and falling back when Facebook refuses.
     *
     * [resumeFromOffset] lets a killed upload pick up where it stopped instead of pushing
     * a large file again from zero. Callers should persist the offset reported through
     * [onProgress] so it survives process death.
     */
    suspend fun publishVideo(
        pageId: String,
        pageToken: String,
        video: File,
        description: String,
        scheduledForEpochSeconds: Long? = null,
        resumeFromOffset: Long = 0,
        onProgress: (UploadProgress) -> Unit = {},
    ): PublishResult {
        require(video.isFile) { "Video not found: ${video.absolutePath}" }

        return try {
            publishAsReel(
                pageId = pageId,
                pageToken = pageToken,
                video = video,
                description = description,
                scheduledForEpochSeconds = scheduledForEpochSeconds,
                resumeFromOffset = resumeFromOffset,
                onProgress = onProgress,
            )
        } catch (e: GraphError.Api) {
            // Only fall back when Facebook rejected the *content*. A dead token or a
            // missing permission would fail identically on the other endpoint, and
            // retrying there would just double the wasted upload.
            if (!e.isRejectedVideoSpec()) throw e

            publishAsPlainVideo(
                pageId = pageId,
                pageToken = pageToken,
                video = video,
                description = description,
                scheduledForEpochSeconds = scheduledForEpochSeconds,
            )
        }
    }

    // --- Reels: three phases ------------------------------------------------------

    suspend fun startReelUpload(pageId: String, pageToken: String): ReelStartResponse {
        val body = client.post(
            url = client.graphUrl("$pageId/video_reels", mapOf("upload_phase" to "start")),
            body = FormBody.Builder().build(),
            token = pageToken,
        )
        return client.decode(body)
    }

    /**
     * Streams the file to the resumable upload host, starting at [offset].
     *
     * Streamed from disk rather than buffered: a Reel can be a couple of hundred
     * megabytes and holding that in memory would kill a low-end phone.
     */
    suspend fun uploadReelBytes(
        videoId: String,
        pageToken: String,
        video: File,
        offset: Long = 0,
        onProgress: (UploadProgress) -> Unit = {},
    ): String {
        val total = video.length()
        require(offset in 0..total) { "Resume offset $offset is outside the file (size $total)." }

        val request = Request.Builder()
            // Version sits after the endpoint name on this host, not before it.
            .url(client.uploadUrl("video-upload/${client.config.apiVersion}/$videoId"))
            // This host wants "OAuth <token>", not the "Bearer <token>" the Graph API
            // uses. Sending the wrong scheme here fails with an unhelpful error.
            .header("Authorization", "OAuth $pageToken")
            .header("offset", offset.toString())
            .header("file_size", total.toString())
            .post(FileRangeBody(video, offset, OCTET_STREAM, onProgress))
            .build()

        return client.execute(request)
    }

    suspend fun finishReel(
        pageId: String,
        pageToken: String,
        videoId: String,
        description: String,
        scheduledForEpochSeconds: Long? = null,
    ): VideoFinishResponse {
        val params = buildMap {
            put("upload_phase", "finish")
            put("video_id", videoId)
            if (scheduledForEpochSeconds != null) {
                put("video_state", "SCHEDULED")
                put("scheduled_publish_time", scheduledForEpochSeconds.toString())
            } else {
                put("video_state", "PUBLISHED")
            }
            if (description.isNotBlank()) put("description", description)
        }

        val body = client.post(
            url = client.graphUrl("$pageId/video_reels", params),
            body = FormBody.Builder().build(),
            token = pageToken,
        )
        return client.decode(body)
    }

    suspend fun uploadStatus(videoId: String, pageToken: String): VideoStatusResponse {
        val body = client.get(
            url = client.graphUrl(videoId, mapOf("fields" to "status")),
            token = pageToken,
        )
        return client.decode(body)
    }

    /**
     * How many bytes Facebook already holds for this video.
     *
     * Asked before resuming, because the local idea of progress can be stale -- the
     * connection may have died after bytes left the phone but before the response
     * arrived.
     */
    suspend fun uploadedBytes(videoId: String, pageToken: String): Long =
        uploadStatus(videoId, pageToken).status.uploadingPhase?.bytesTransferred ?: 0L

    private suspend fun publishAsReel(
        pageId: String,
        pageToken: String,
        video: File,
        description: String,
        scheduledForEpochSeconds: Long?,
        resumeFromOffset: Long,
        onProgress: (UploadProgress) -> Unit,
    ): PublishResult {
        val session = startReelUpload(pageId, pageToken)

        uploadReelBytes(
            videoId = session.videoId,
            pageToken = pageToken,
            video = video,
            offset = resumeFromOffset,
            onProgress = onProgress,
        )

        finishReel(
            pageId = pageId,
            pageToken = pageToken,
            videoId = session.videoId,
            description = description,
            scheduledForEpochSeconds = scheduledForEpochSeconds,
        )

        // Scheduled reels never reach the published phase now, so waiting would hang.
        if (scheduledForEpochSeconds == null) {
            awaitPublished(session.videoId, pageToken)
        }

        return PublishResult(postId = session.videoId, rawId = session.videoId)
    }

    /**
     * Polls until the video is actually live.
     *
     * The upload returning 200 only means the bytes arrived. Processing and publishing
     * happen afterwards and can still fail, so reporting success at upload time would
     * routinely tell the user a post exists when it does not.
     */
    private suspend fun awaitPublished(videoId: String, pageToken: String) {
        repeat(MAX_STATUS_POLLS) { attempt ->
            val status = uploadStatus(videoId, pageToken).status

            status.failedPhase?.let { phase ->
                val error = phase.errors.firstOrNull()
                throw GraphError.Api(
                    code = error?.code ?: 0,
                    subcode = null,
                    type = "VideoProcessingError",
                    fbMessage = error?.message ?: "Facebook could not process this video.",
                    fbTraceId = null,
                    httpStatus = 200,
                )
            }

            if (status.isReady) return

            delay(POLL_INTERVAL_MS * (attempt + 1).coerceAtMost(POLL_BACKOFF_CAP))
        }
        // Not treated as a failure: Facebook is still working on it, and the post will
        // usually appear shortly. Claiming it failed would be worse than saying nothing.
    }

    // --- Plain /videos fallback ---------------------------------------------------

    private suspend fun publishAsPlainVideo(
        pageId: String,
        pageToken: String,
        video: File,
        description: String,
        scheduledForEpochSeconds: Long?,
    ): PublishResult {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("description", description)
            .addFormDataPart("source", video.name, FileRangeBody(video, 0, VIDEO_MP4))
            .apply {
                PublishApi.scheduleFields(scheduledForEpochSeconds).forEach { (key, value) ->
                    addFormDataPart(key, value)
                }
            }
            .build()

        val body = client.post(
            url = client.graphUrl("$pageId/videos"),
            body = multipart,
            token = pageToken,
        )
        val response: VideoFinishResponse = client.decode(body)
        val id = response.postId ?: response.id
            ?: throw GraphError.Unexpected("The video endpoint returned no id.")

        return PublishResult(postId = id, rawId = response.id ?: id)
    }

    companion object {
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val VIDEO_MP4 = "video/mp4".toMediaType()

        private const val MAX_STATUS_POLLS = 20
        private const val POLL_INTERVAL_MS = 2_000L
        private const val POLL_BACKOFF_CAP = 5

        /**
         * Whether Facebook rejected the video itself, as opposed to rejecting us.
         *
         * Codes 100 and 6000 cover malformed or unsupported media on the Reels endpoint.
         * Permission, token and rate-limit failures deliberately do not qualify: falling
         * back on those would upload the whole file a second time only to fail again.
         */
        internal fun GraphError.Api.isRejectedVideoSpec(): Boolean {
            if (isTokenExpired || isPermissionDenied || isRateLimited) return false
            return code == 100 || code == 6000 || code == 390 ||
                fbMessage.contains("aspect ratio", ignoreCase = true) ||
                fbMessage.contains("duration", ignoreCase = true) ||
                fbMessage.contains("length", ignoreCase = true) ||
                fbMessage.contains("format", ignoreCase = true)
        }
    }
}

/**
 * Streams a file from a byte offset, reporting progress.
 *
 * The offset is what makes a resumed upload possible: OkHttp is handed only the tail of
 * the file that Facebook has not already received.
 */
private class FileRangeBody(
    private val file: File,
    private val offset: Long,
    private val mediaType: MediaType,
    private val onProgress: (UploadProgress) -> Unit = {},
) : RequestBody() {

    private val total = file.length()

    override fun contentType(): MediaType = mediaType

    override fun contentLength(): Long = total - offset

    override fun writeTo(sink: BufferedSink) {
        if (offset == 0L) {
            // Whole-file case: let okio stream it, which is cheaper than seeking.
            file.source().use { source ->
                var sent = 0L
                val buffer = okio.Buffer()
                while (true) {
                    val read = source.read(buffer, CHUNK)
                    if (read == -1L) break
                    sink.write(buffer, read)
                    sent += read
                    onProgress(UploadProgress(sent, total))
                }
            }
            return
        }

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buffer = ByteArray(CHUNK.toInt())
            var sent = offset
            while (true) {
                val read = raf.read(buffer)
                if (read == -1) break
                sink.write(buffer, 0, read)
                sent += read
                onProgress(UploadProgress(sent, total))
            }
        }
    }

    private companion object {
        const val CHUNK = 64 * 1024L
    }
}
