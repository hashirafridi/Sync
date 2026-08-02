package com.hashir.sync.downloader

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * [MediaFetcher] backed by yt-dlp running on the device.
 *
 * yt-dlp is a real Python process here, not a library call: the AAR ships CPython, the
 * yt-dlp script and FFmpeg as native assets, and each download spawns a process. That has
 * two consequences worth knowing:
 *
 *  - every call blocks, so all of them run on [Dispatchers.IO]
 *  - output must go to a real filesystem path, since the process cannot write through
 *    MediaStore
 */
class YtDlpMediaFetcher(private val context: Context) : MediaFetcher {

    private val initMutex = Mutex()

    @Volatile
    private var initialised = false

    /**
     * Unpacks the Python runtime and yt-dlp on first use.
     *
     * Costs a few seconds and hundreds of megabytes of extraction, so it is deliberately
     * lazy rather than done at app start: someone who never opens the downloader should
     * never pay for it.
     */
    suspend fun ensureInitialised() {
        if (initialised) return
        initMutex.withLock {
            if (initialised) return
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
            }
            initialised = true
        }
    }

    override suspend fun probe(url: String): FetchInfo = withContext(Dispatchers.IO) {
        ensureInitialised()

        val request = YoutubeDLRequest(url).apply {
            // A pasted link often carries a playlist id. Without this, probing a single
            // video from a playlist returns the whole playlist instead.
            addOption("--no-playlist")
            applyNetworkResilience()
        }

        val info = YoutubeDL.getInstance().getInfo(request)

        FetchInfo(
            sourceUrl = url,
            title = info.title ?: info.fulltitle,
            description = info.description,
            durationSeconds = info.duration.toLong().takeIf { it > 0 },
            thumbnailUrl = info.thumbnail,
            // Only standard tiers reach the picker. The raw list from yt-dlp includes
            // 144p/240p/360p renditions that are useless for a Page post.
            availableHeights = ResolutionPolicy.offeredHeights(
                ResolutionPolicy.normalizeHeights(
                    info.formats.orEmpty().map { format -> format.height.takeIf { it > 0 } },
                ),
            ),
        )
    }

    override fun download(
        url: String,
        targetHeight: Int,
        outputDir: File,
    ): Flow<DownloadProgress> = callbackFlow {
        // A per-download directory makes finding the result unambiguous. yt-dlp decides
        // the final filename itself, after merging, so scanning a shared folder would be
        // guesswork.
        val workDir = File(outputDir, "dl_${System.currentTimeMillis()}").apply { mkdirs() }
        val processId = UUID.randomUUID().toString()

        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                ensureInitialised()

                val request = YoutubeDLRequest(url).apply {
                    addOption("-f", ResolutionPolicy.formatSelector(targetHeight))
                    // Ranks the surviving formats by real quality. Without this yt-dlp can
                    // settle on a lower-bitrate or differently-cropped rendition that
                    // merely happens to satisfy the filter first.
                    addOption("-S", ResolutionPolicy.formatSortOrder())
                    addOption("-o", "${workDir.absolutePath}/%(title).80s.%(ext)s")
                    addOption("--no-playlist")
                    // Container only. Remuxes rather than re-encodes, so the picture is
                    // bit-for-bit what the source served -- no quality loss, no rescaling.
                    addOption("--merge-output-format", "mp4")
                    // Keep the download timestamp rather than the source's, so "newest
                    // file" ordering stays meaningful.
                    addOption("--no-mtime")
                    applyNetworkResilience()
                }

                YoutubeDL.getInstance().execute(request, processId) { progress, etaSeconds, _ ->
                    trySend(DownloadProgress.Running(progress, etaSeconds))
                }

                val file = workDir.listFiles()
                    ?.filter { it.isFile && it.length() > 0 }
                    ?.maxByOrNull { it.lastModified() }

                if (file != null) {
                    trySend(DownloadProgress.Completed(file))
                } else {
                    trySend(
                        DownloadProgress.Failed(
                            IllegalStateException(
                                "The download finished but produced no file. The site may " +
                                    "have changed; try updating the downloader in Settings.",
                            ),
                        ),
                    )
                }
            } catch (e: Throwable) {
                trySend(DownloadProgress.Failed(e))
            } finally {
                close()
            }
        }

        awaitClose {
            // Kills the yt-dlp process. Without this, cancelling the screen would leave a
            // Python process writing to disk in the background.
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
            job.cancel()
        }
    }

    /**
     * Updates the bundled yt-dlp in place.
     *
     * This is the single most important maintenance feature in the app. Extractors break
     * whenever a site changes its player, and without in-place updates the downloader
     * quietly rots within months and every fix would need a new APK on every phone.
     *
     * Allowed here only because this app is never distributed through Google Play, whose
     * policy forbids downloading executable code at runtime.
     */
    suspend fun update(nightly: Boolean = true): String = withContext(Dispatchers.IO) {
        ensureInitialised()
        val channel = if (nightly) {
            YoutubeDL.UpdateChannel._NIGHTLY
        } else {
            YoutubeDL.UpdateChannel._STABLE
        }
        val status = YoutubeDL.getInstance().updateYoutubeDL(context, channel)
        "$status (${versionName() ?: "unknown version"})"
    }

    suspend fun versionName(): String? = withContext(Dispatchers.IO) {
        runCatching { YoutubeDL.getInstance().versionName(context) }.getOrNull()
    }

    private companion object {

        /**
         * Options that make a flaky mobile connection survivable.
         *
         * Without these, yt-dlp gives up on the first refused connection or stalled
         * socket, which on a phone happens constantly -- moving between cells, a weak
         * Wi-Fi edge, a moment of congestion. Retrying is nearly always the right answer
         * and the default of none is far too pessimistic for this environment.
         */
        fun YoutubeDLRequest.applyNetworkResilience() {
            // Whole-request attempts.
            addOption("--retries", "10")
            // Per-fragment, which is what actually matters for segmented (HLS/DASH) video:
            // a single dropped fragment would otherwise fail the entire download.
            addOption("--fragment-retries", "20")
            // Wait between attempts instead of hammering a struggling connection.
            addOption("--retry-sleep", "linear=1::5")
            // Fail a genuinely dead socket in 30s rather than hanging until the user
            // gives up and assumes the app is broken.
            addOption("--socket-timeout", "30")
            // Resume a partially downloaded file rather than starting again.
            addOption("--continue")
        }
    }
}
