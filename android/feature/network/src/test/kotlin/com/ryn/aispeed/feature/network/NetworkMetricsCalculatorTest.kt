package com.ryn.aispeed.feature.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkMetricsCalculatorTest {
    @Test
    fun `computes accurate ping, jitter, and packet loss`() {
        val report = NetworkMetricsCalculator.computeStats(
            host = "1.1.1.1",
            port = 443,
            probesSent = 5,
            rttSamplesMs = listOf(20.0, 30.0, 25.0, 35.0)
        )
        assertEquals(27.5, report.avgPingMs!!, 0.001)
        assertEquals(8.333, report.jitterMs!!, 0.01)
        assertEquals(20.0, report.packetLossPct, 0.001)
    }

    @Test
    fun `flags high packet loss in VPN comparison`() {
        val before = SpeedTestReport(50.0, 10.0, 25.0, 2.0, 0.0, vpnActive = false)
        val after = SpeedTestReport(40.0, 9.0, 45.0, 5.0, 10.0, vpnActive = true)
        val delta = NetworkMetricsCalculator.comparePreAndPostVpn(before, after)
        assertEquals(20.0, delta.pingDeltaMs!!, 0.001)
        assertTrue(delta.summaryRecommendation.contains("packet loss", ignoreCase = true))
    }
}
