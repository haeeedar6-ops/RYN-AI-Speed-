package com.ryn.aispeed.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAutoManagerTest {
    @Test
    fun `master kill switch halts all automation immediately`() {
        val mgr = AiAutoManager(initialEnabled = true)
        mgr.setMasterEnabled(false)
        assertEquals(
            AutoManagerDecision.DisabledByMasterSwitch,
            mgr.evaluateNetworkTelemetry(250.0, 15.0, vpnActive = true)
        )
        assertEquals(
            AutoManagerDecision.DisabledByMasterSwitch,
            mgr.evaluateDownloadFailure("d1", 0, isFatalAuthOrNotFound = false)
        )
    }

    @Test
    fun `proposes vpn switch with mandatory user approval on high packet loss`() {
        val mgr = AiAutoManager(initialEnabled = true)
        val dec = mgr.evaluateNetworkTelemetry(45.0, 12.0, vpnActive = true)
        assertTrue(dec is AutoManagerDecision.ProposeVpnSwitch && dec.requiresUserApproval)
    }

    @Test
    fun `respects retry cap and never retries 401 or 404 fatal errors`() {
        val mgr = AiAutoManager(initialEnabled = true)
        assertEquals(
            AutoManagerDecision.NoActionNeeded,
            mgr.evaluateDownloadFailure("d1", 0, isFatalAuthOrNotFound = true)
        )
        assertEquals(
            AutoManagerDecision.NoActionNeeded,
            mgr.evaluateDownloadFailure("d1", 3, isFatalAuthOrNotFound = false)
        )
    }
}
