package com.hashir.sync.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolutionPolicyTest {

    // --- which chips are offered ---------------------------------------------------

    @Test
    fun `only standard tiers are offered, never 144p or 240p or 360p`() {
        // What YouTube actually reports for a 1080p video.
        val raw = listOf(1080, 720, 480, 360, 240, 144)

        assertEquals(listOf(1080, 720, 480), ResolutionPolicy.offeredHeights(raw))
    }

    @Test
    fun `a 4K source offers everything down to 480p`() {
        val raw = listOf(2160, 1440, 1080, 720, 480, 360, 240, 144)

        assertEquals(listOf(2160, 1440, 1080, 720, 480), ResolutionPolicy.offeredHeights(raw))
    }

    @Test
    fun `tiers above what the source has are never offered`() {
        // A 720p source must not advertise a 1080p it cannot deliver.
        assertEquals(listOf(720, 480), ResolutionPolicy.offeredHeights(listOf(720, 480, 360)))
    }

    @Test
    fun `a source below 480p offers its own height rather than nothing`() {
        assertEquals(listOf(360), ResolutionPolicy.offeredHeights(listOf(360, 240, 144)))
        assertEquals(listOf(240), ResolutionPolicy.offeredHeights(listOf(240, 144)))
    }

    @Test
    fun `no formats at all does not crash`() {
        assertEquals(listOf(1080), ResolutionPolicy.offeredHeights(emptyList()))
    }

    // --- which chip starts selected ------------------------------------------------

    @Test
    fun `defaults to 1080p when the source has it or more`() {
        assertEquals(1080, ResolutionPolicy.defaultHeightFor(listOf(2160, 1440, 1080, 720)))
        assertEquals(1080, ResolutionPolicy.defaultHeightFor(listOf(1080, 720, 480, 360)))
    }

    @Test
    fun `defaults to the source maximum when it is below 1080p`() {
        assertEquals(720, ResolutionPolicy.defaultHeightFor(listOf(720, 480, 360)))
        assertEquals(480, ResolutionPolicy.defaultHeightFor(listOf(480, 360, 240)))
        assertEquals(360, ResolutionPolicy.defaultHeightFor(listOf(360, 240)))
    }

    // --- format selection ----------------------------------------------------------

    @Test
    fun `selector caps height without demanding a container`() {
        val selector = ResolutionPolicy.formatSelector(1080)

        assertTrue("must cap the height", selector.contains("height<=1080"))
        // Requiring mp4 here can push yt-dlp past the best matching stream onto a
        // differently-cropped or lower-quality rendition purely over its container.
        // Container preference belongs in the sort order, expressed as a preference.
        assertFalse("must not require a container", selector.contains("ext=mp4"))
        assertTrue("must degrade to a pre-muxed stream", selector.contains("/b[height<=1080]"))
        assertTrue("must have an unconstrained last resort", selector.endsWith("/bv*+ba/b"))
    }

    @Test
    fun `selector honours the requested height`() {
        assertTrue(ResolutionPolicy.formatSelector(480).contains("height<=480"))
        assertTrue(ResolutionPolicy.formatSelector(2160).contains("height<=2160"))
    }

    @Test
    fun `nonsense heights cannot produce a malformed selector`() {
        assertTrue(ResolutionPolicy.formatSelector(0).contains("height<=1"))
        assertTrue(ResolutionPolicy.formatSelector(-5).contains("height<=1"))
    }

    @Test
    fun `sort order ranks by real quality before container preference`() {
        val sort = ResolutionPolicy.formatSortOrder()

        // Resolution first: this is what stops a small or oddly-cropped rendition winning
        // simply by appearing earlier in the site's own format list.
        assertTrue(sort.startsWith("res"))
        assertTrue("bitrate must be considered", sort.contains("vbr"))
        assertTrue("h264 preferred for Facebook", sort.contains("codec:h264"))
        assertTrue("mp4 preferred but not required", sort.contains("ext:mp4"))
    }

    // --- raw height cleanup --------------------------------------------------------

    @Test
    fun `heights are deduped, sorted and stripped of audio only entries`() {
        val raw = listOf(1080, null, 720, 1080, 0, 480, null, 720)
        assertEquals(listOf(1080, 720, 480), ResolutionPolicy.normalizeHeights(raw))
    }
}
