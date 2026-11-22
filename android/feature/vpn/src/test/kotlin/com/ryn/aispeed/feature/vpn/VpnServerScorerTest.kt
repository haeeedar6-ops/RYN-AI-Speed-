package com.ryn.aispeed.feature.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnServerScorerTest {
    private val validKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    @Test
    fun `rejects unverified community servers and offline nodes`() {
        val unverified = VpnServerProfile(
            "c1", "Unverified", "DE", "EU", "1.2.3.4:51820", validKey,
            serverType = "COMMUNITY", operatorVerified = false, noLogsVerified = false,
            healthStatus = "HEALTHY", capacityStatus = "LOW", pingMs = 15.0, jitterMs = 1.0, packetLossPct = 0.0
        )
        assertFalse(VpnServerScorer.isEligible(unverified))
        assertNull(VpnServerScorer.selectBestServer(listOf(unverified)))
    }

    @Test
    fun `selects lower packet loss and latency server over congested node`() {
        val good = VpnServerProfile(
            "p1", "NL-Private-1", "NL", "EU", "5.6.7.8:51820", validKey,
            serverType = "PRIVATE", operatorVerified = true, noLogsVerified = true,
            healthStatus = "HEALTHY", capacityStatus = "LOW", pingMs = 22.0, jitterMs = 2.0, packetLossPct = 0.0
        )
        val poor = VpnServerProfile(
            "p2", "DE-Congested", "DE", "EU", "9.10.11.12:51820", validKey,
            serverType = "PRIVATE", operatorVerified = true, noLogsVerified = true,
            healthStatus = "HEALTHY", capacityStatus = "HIGH", pingMs = 180.0, jitterMs = 40.0, packetLossPct = 12.0
        )
        assertEquals("p1", VpnServerScorer.selectBestServer(listOf(poor, good))?.serverId)
    }

    @Test
    fun `wireguard config enforces full IPv4 and IPv6 tunnel routing`() {
        val server = VpnServerProfile(
            "p1", "NL-1", "NL", "EU", "5.6.7.8:51820", validKey,
            serverType = "PRIVATE", operatorVerified = true, noLogsVerified = true,
            healthStatus = "HEALTHY", capacityStatus = "LOW", pingMs = 20.0, jitterMs = 2.0, packetLossPct = 0.0
        )
        val cfg = WireGuardConfigBuilder.buildConfig(validKey, "10.66.66.2/32", server)
        assertTrue(cfg.contains("AllowedIPs = 0.0.0.0/0, ::/0"))
        assertTrue(cfg.contains("DNS = 1.1.1.1, 9.9.9.9"))
    }
}
