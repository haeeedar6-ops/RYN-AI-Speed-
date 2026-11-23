package com.ryn.aispeed.feature.downloads.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumePolicyTest {
    private fun probe(size: Long? = 1000L, ranges: Boolean = true, etag: String? = "\"abc\"", lm: String? = "Mon, 01 Jan 2026 00:00:00 GMT") =
        ProbeResult("https://x/f.bin", "f.bin", "OTHER", null, size, ranges, etag, lm)

    private fun seg(i: Int, start: Long, end: Long) = ActiveSegment("s$i", i, start, end)

    @Test
    fun `resume allowed only when server file is unchanged`() {
        val lm = "Mon, 01 Jan 2026 00:00:00 GMT"
        assertTrue(DownloadPolicy.canResume(1000L, "\"abc\"", lm, probe()))
        assertFalse(DownloadPolicy.canResume(1000L, "\"zzz\"", lm, probe()))       // etag changed
        assertFalse(DownloadPolicy.canResume(999L, "\"abc\"", lm, probe()))        // size changed
        assertFalse(DownloadPolicy.canResume(1000L, "\"abc\"", "other", probe()))  // last-modified changed
        assertFalse(DownloadPolicy.canResume(1000L, "\"abc\"", lm, probe(ranges = false)))
        assertFalse(DownloadPolicy.canResume(null, "\"abc\"", lm, probe(size = null)))
    }

    @Test
    fun `checkpoint must tile the whole file exactly`() {
        assertTrue(DownloadPolicy.coversWholeFile(listOf(seg(0, 0, 499), seg(1, 500, 999)), 1000L))
        assertTrue(DownloadPolicy.coversWholeFile(listOf(seg(1, 500, 999), seg(0, 0, 499)), 1000L)) // order independent
        assertFalse(DownloadPolicy.coversWholeFile(listOf(seg(0, 0, 499), seg(1, 501, 999)), 1000L)) // gap
        assertFalse(DownloadPolicy.coversWholeFile(listOf(seg(0, 0, 500), seg(1, 500, 999)), 1000L)) // overlap
        assertFalse(DownloadPolicy.coversWholeFile(listOf(seg(0, 0, 499)), 1000L))                   // short
        assertFalse(DownloadPolicy.coversWholeFile(emptyList(), 1000L))
    }
}
