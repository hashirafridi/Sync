package com.hashir.sync.downloader

import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * The one seam between the app and whatever actually fetches media.
 *
 * Today the only implementation runs yt-dlp on the device. Keeping the app behind this
 * interface means a server-side downloader could replace it later without any UI change
 * -- which matters, because on-device yt-dlp is the part of this product most likely to
 * need rethinking as sites change.
 */
interface MediaFetcher {

    /**
     * Reads metadata without downloading anything, so the user can see what they are
     * about to get and pick a resolution first.
     */
    suspend fun probe(url: String): FetchInfo

    /**
     * Downloads at the requested height, emitting progress as it goes.
     *
     * [targetHeight] is a ceiling, not an exact match -- yt-dlp picks the best stream at
     * or below it. See [ResolutionPolicy.formatSelector].
     */
    fun download(url: String, targetHeight: Int, outputDir: File): Flow<DownloadProgress>
}

/**
 * What a source turned out to contain.
 *
 * [suggestedCaption] is already resolved from the messy per-site reality -- see
 * [FetchInfo.resolveCaption].
 */
data class FetchInfo(
    val sourceUrl: String,
    val title: String?,
    val description: String?,
    val durationSeconds: Long?,
    val thumbnailUrl: String?,
    /** Distinct video heights the source actually offers, highest first. */
    val availableHeights: List<Int>,
) {
    /** Which chip starts selected. See [ResolutionPolicy.defaultHeightFor]. */
    val defaultHeight: Int
        get() = ResolutionPolicy.defaultHeightFor(availableHeights)

    /**
     * The caption to prefill the editor with.
     *
     * Extractors disagree about where the user-visible caption lives: TikTok and
     * Instagram put it in `description`, while YouTube's `description` is a long blurb
     * and its `title` is the real headline. Preferring a non-blank `description` and
     * falling back to `title` gets the useful text in both cases, and the field stays
     * editable regardless.
     */
    val suggestedCaption: String
        get() = description?.takeIf { it.isNotBlank() } ?: title.orEmpty()
}

/** Progress of an in-flight download. */
sealed interface DownloadProgress {

    /** [percent] is 0..100. [etaSeconds] is -1 when yt-dlp has not estimated one yet. */
    data class Running(val percent: Float, val etaSeconds: Long) : DownloadProgress

    data class Completed(val file: File) : DownloadProgress

    data class Failed(val error: Throwable) : DownloadProgress
}
