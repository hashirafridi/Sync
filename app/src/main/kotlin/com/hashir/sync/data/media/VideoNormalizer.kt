package com.hashir.sync.data.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Last-resort re-encode for video Facebook will not accept.
 *
 * This should almost never run. The downloader's format selector asks yt-dlp for MP4 with
 * H.264 and AAC first, which is exactly what Facebook wants, so the file usually uploads
 * untouched. But some sources only offer VP9 video and Opus audio in a WebM container at
 * a given resolution, and those are rejected.
 *
 * Uses Media3 Transformer rather than FFmpeg deliberately: `ffmpeg-kit` was retired in
 * 2025, and Transformer encodes through MediaCodec on the device's hardware, which is
 * dramatically faster and easier on the battery than software encoding on a phone.
 */
@Singleton
class VideoNormalizer @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Returns a file Facebook will accept: the original when it is already fine, or a
     * re-encoded copy when it is not.
     */
    suspend fun ensureCompatible(input: File): File {
        if (!needsTranscode(input)) return input
        return transcode(input)
    }

    /**
     * Cheap container and codec probe, without decoding anything.
     *
     * Errs towards *not* transcoding: if the file cannot be inspected, it is passed
     * through unchanged. A needless re-encode costs minutes of a user's time and quality,
     * whereas letting Facebook reject it costs one error message.
     */
    fun needsTranscode(file: File): Boolean {
        if (!file.isFile) return false
        if (!file.extension.equals("mp4", ignoreCase = true)) return true

        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)

            var videoOk = false
            var audioOk = true // a silent video is acceptable

            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    mime.startsWith("video/") ->
                        videoOk = mime == MimeTypes.VIDEO_H264 || mime == MimeTypes.VIDEO_H265

                    mime.startsWith("audio/") ->
                        audioOk = mime == MimeTypes.AUDIO_AAC
                }
            }

            !(videoOk && audioOk)
        } catch (e: Exception) {
            false
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Re-encodes to H.264 + AAC in MP4.
     *
     * Transformer needs a Looper, so it is created and started on the main thread even
     * though the caller is a background worker. The actual encoding happens off it.
     */
    private suspend fun transcode(input: File): File = withContext(Dispatchers.Main) {
        val output = File(cacheDir(), "normalized_${System.currentTimeMillis()}.mp4")

        suspendCancellableCoroutine { continuation ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            continuation.resume(output)
                        }

                        override fun onError(
                            composition: Composition,
                            result: ExportResult,
                            exception: ExportException,
                        ) {
                            output.delete()
                            continuation.resumeWithException(exception)
                        }
                    },
                )
                .build()

            transformer.start(MediaItem.fromUri(Uri.fromFile(input)), output.absolutePath)

            continuation.invokeOnCancellation {
                // Without this a cancelled job leaves the encoder running and the
                // half-written file behind.
                runCatching { transformer.cancel() }
                output.delete()
            }
        }
    }

    private fun cacheDir(): File = File(context.cacheDir, "normalized").apply { mkdirs() }
}
