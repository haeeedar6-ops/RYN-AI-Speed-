package com.ryn.aispeed.feature.ai

import java.util.concurrent.atomic.AtomicBoolean

data class AutoManagerPolicy(
    val autoRetryFailedDownloads: Boolean = true,
    val maxAutoRetriesPerDownload: Int = 3,
    val suggestVpnSwitchOnDegradation: Boolean = true,
    val pingDegradationThresholdMs: Double = 150.0,
    val packetLossThresholdPct: Double = 5.0,
    val monthlyDataCapBytes: Long = 0L // 0 = unlimited
)

sealed interface AutoManagerDecision {
    data object DisabledByMasterSwitch : AutoManagerDecision
    data object NoActionNeeded : AutoManagerDecision
    data class RetryDownloadSafe(val downloadId: String, val attempt: Int) : AutoManagerDecision
    data class ProposeVpnSwitch(val reason: String, val requiresUserApproval: Boolean = true) : AutoManagerDecision
    data class DataCapWarning(val usedBytes: Long, val capBytes: Long) : AutoManagerDecision
}

/**
 * AI Auto Manager for Android:
 * Monitors network quality, download failures, and data usage strictly within user permissions.
 * Includes an instant Master Kill Switch (`setMasterEnabled(false)`) that halts all automation immediately.
 */
class AiAutoManager(
    @Volatile var policy: AutoManagerPolicy = AutoManagerPolicy(),
    initialEnabled: Boolean = true
) {
    private val masterEnabled = AtomicBoolean(initialEnabled)

    fun isMasterEnabled(): Boolean = masterEnabled.get()

    fun setMasterEnabled(enabled: Boolean) {
        masterEnabled.set(enabled)
    }

    fun evaluateNetworkTelemetry(
        pingMs: Double?,
        packetLossPct: Double,
        vpnActive: Boolean
    ): AutoManagerDecision {
        if (!masterEnabled.get()) return AutoManagerDecision.DisabledByMasterSwitch
        if (!policy.suggestVpnSwitchOnDegradation || !vpnActive) return AutoManagerDecision.NoActionNeeded

        return when {
            packetLossPct >= policy.packetLossThresholdPct -> AutoManagerDecision.ProposeVpnSwitch(
                reason = "Packet loss reached ${packetLossPct.toInt()}% on current VPN node.",
                requiresUserApproval = true
            )
            (pingMs ?: 0.0) >= policy.pingDegradationThresholdMs -> AutoManagerDecision.ProposeVpnSwitch(
                reason = "Ping rose to ${pingMs?.toInt()} ms on current VPN node.",
                requiresUserApproval = true
            )
            else -> AutoManagerDecision.NoActionNeeded
        }
    }

    fun evaluateDownloadFailure(
        downloadId: String,
        currentRetries: Int,
        isFatalAuthOrNotFound: Boolean
    ): AutoManagerDecision {
        if (!masterEnabled.get()) return AutoManagerDecision.DisabledByMasterSwitch
        if (!policy.autoRetryFailedDownloads || isFatalAuthOrNotFound) return AutoManagerDecision.NoActionNeeded
        if (currentRetries >= policy.maxAutoRetriesPerDownload) return AutoManagerDecision.NoActionNeeded
        return AutoManagerDecision.RetryDownloadSafe(downloadId, currentRetries + 1)
    }

    fun evaluateDataUsage(usedBytes: Long): AutoManagerDecision {
        if (!masterEnabled.get()) return AutoManagerDecision.DisabledByMasterSwitch
        val cap = policy.monthlyDataCapBytes
        if (cap > 0L && usedBytes >= (cap * 85L) / 100L) {
            return AutoManagerDecision.DataCapWarning(usedBytes, cap)
        }
        return AutoManagerDecision.NoActionNeeded
    }
}
