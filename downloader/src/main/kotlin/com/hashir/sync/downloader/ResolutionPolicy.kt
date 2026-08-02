package com.hashir.sync.downloader

/**
 * Which resolution to download, and how to express that to yt-dlp.
 *
 * Pure logic with no Android or yt-dlp dependency, so it is unit-tested directly. Worth
 * doing: getting format selection subtly wrong produces a file that downloads fine and is
 * then the wrong shape or quality, which nobody notices until it has been published.
 */
object ResolutionPolicy {

    /**
     * The ceiling we default to even when the source offers more.
     *
     * 1080p is what Facebook actually serves, so pulling 4K costs time and storage for
     * quality nobody sees. Higher options stay selectable -- this is a default, not a cap.
     */
    const val PREFERRED_HEIGHT: Int = 1080

    /**
     * The only qualities worth offering.
     *
     * yt-dlp reports every rendition a site has, which for YouTube means 144p, 240p and
     * 360p cluttering the picker with options nobody would deliberately publish to a Page.
     */
    val STANDARD_TIERS: List<Int> = listOf(2160, 1440, 1080, 720, 480)

    /**
     * The quality chips to show, given every height the source reports.
     *
     * Only standard tiers at or below what the source actually has, so a 720p video never
     * offers a fictitious 1080p. If the source is below 480p entirely, its native height
     * is offered alone rather than showing nothing -- taking it as-is beats refusing.
     */
    fun offeredHeights(availableHeights: List<Int>): List<Int> {
        val best = availableHeights.maxOrNull() ?: return listOf(PREFERRED_HEIGHT)
        val tiers = STANDARD_TIERS.filter { it <= best }
        return tiers.ifEmpty { listOf(best) }
    }

    /**
     * Which chip starts selected.
     *
     * - source up to 4K -> 1080p, the preferred cap
     * - source max 720p -> 720p, all it has
     * - source max 360p -> 360p, its original quality
     */
    fun defaultHeightFor(availableHeights: List<Int>): Int {
        val offered = offeredHeights(availableHeights)
        return offered.firstOrNull { it <= PREFERRED_HEIGHT } ?: offered.min()
    }

    /**
     * yt-dlp format selector: a height ceiling, and nothing else.
     *
     * Deliberately does NOT demand a container. An earlier version required `[ext=mp4]`,
     * which on sites publishing several renditions could push yt-dlp past the best
     * matching stream onto a differently-cropped or lower-quality one purely because of
     * its container. Container preference belongs in [formatSortOrder], where it is a
     * preference rather than a requirement.
     */
    fun formatSelector(targetHeight: Int): String {
        val h = targetHeight.coerceAtLeast(1)
        return "bv*[height<=$h]+ba/b[height<=$h]/bv*+ba/b"
    }

    /**
     * How yt-dlp ranks the formats that survive [formatSelector].
     *
     * This is what protects quality and shape. Ranking by resolution and bitrate picks the
     * best real stream rather than whichever was listed first, and stating codec and
     * container as preferences means a better stream is never rejected for being in the
     * wrong wrapper -- it gets remuxed instead.
     */
    fun formatSortOrder(): String = "res,fps,vbr,abr,codec:h264:aac,ext:mp4:m4a"

    /**
     * Turns the raw per-format heights yt-dlp reports into a clean list: audio-only
     * entries dropped, duplicates collapsed, highest first.
     */
    fun normalizeHeights(rawHeights: List<Int?>): List<Int> =
        rawHeights.asSequence()
            .filterNotNull()
            .filter { it > 0 }
            .distinct()
            .sortedDescending()
            .toList()
}
