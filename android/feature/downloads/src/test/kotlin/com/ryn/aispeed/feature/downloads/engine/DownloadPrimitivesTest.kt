package com.ryn.aispeed.feature.downloads.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadPrimitivesTest {
    @Test
    fun `adaptive connection count respects range support and file size`() {
        assertEquals(1, DownloadPolicy.computeOptimalConnections(1_000_000L, true, 8))
        assertEquals(1, DownloadPolicy.computeOptimalConnections(500_000_000L, false, 8))
        assertEquals(2, DownloadPolicy.computeOptimalConnections(10_000_000L, true, 8))
        assertEquals(4, DownloadPolicy.computeOptimalConnections(50_000_000L, true, 8))
        assertEquals(8, DownloadPolicy.computeOptimalConnections(1_000_000_000L, true, 8))
        assertEquals(4, DownloadPolicy.computeOptimalConnections(1_000_000_000L, true, 4))
    }

    @Test
    fun `work stealing splits largest active segment when remaining exceeds 4MB`() = runBlocking {
        val seg0 = ActiveSegment("d1_seg_0", 0, 0L, 19_999_999L, status = "DOWNLOADING")
        seg0.downloadedBytes.set(2_000_000L) // 18 MB remaining
        val coord = SegmentCoordinator("d1", listOf(seg0))

        val stolen = coord.stealWorkOrNull()
        assertNotNull(stolen)
        assertEquals(seg0.endByte + 1L, stolen!!.startByte)
        assertEquals(19_999_999L, stolen.endByte)
    }

    @Test
    fun `work stealing returns null when remaining is below threshold`() = runBlocking {
        val seg0 = ActiveSegment("d1_seg_0", 0, 0L, 2_000_000L, status = "DOWNLOADING")
        val coord = SegmentCoordinator("d1", listOf(seg0))
        assertNull(coord.stealWorkOrNull())
    }
}
