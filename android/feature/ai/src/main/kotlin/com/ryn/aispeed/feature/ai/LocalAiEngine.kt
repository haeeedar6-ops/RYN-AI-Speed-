package com.ryn.aispeed.feature.ai

enum class AiPermissionLevel { READ, SAFE_ACTION, PROTECTED_ACTION }

data class LocalDiagnosis(
    val titleAr: String,
    val titleEn: String,
    val recommendedConnections: Int,
    val suggestedAction: String
)

/**
 * Offline Local AI Rules Engine & Client-Side Tool RBAC Guard for Android.
 * Works completely without external API keys and enforces explicit user approval for PROTECTED_ACTION.
 */
object LocalAiRulesEngine {
    val toolPermissions: Map<String, AiPermissionLevel> = mapOf(
        "get_network_stats" to AiPermissionLevel.READ,
        "get_dns_info" to AiPermissionLevel.READ,
        "list_vpn_servers" to AiPermissionLevel.READ,
        "get_downloads" to AiPermissionLevel.READ,
        "get_server_health" to AiPermissionLevel.READ,
        "run_speed_test" to AiPermissionLevel.SAFE_ACTION,
        "pause_download" to AiPermissionLevel.SAFE_ACTION,
        "resume_download" to AiPermissionLevel.SAFE_ACTION,
        "set_download_limit" to AiPermissionLevel.SAFE_ACTION,
        "connect_vpn" to AiPermissionLevel.PROTECTED_ACTION,
        "disconnect_vpn" to AiPermissionLevel.PROTECTED_ACTION,
        "cancel_download" to AiPermissionLevel.PROTECTED_ACTION
    )

    fun canExecute(toolName: String, userConfirmed: Boolean): Boolean {
        val level = toolPermissions[toolName] ?: return false
        return when (level) {
            AiPermissionLevel.READ, AiPermissionLevel.SAFE_ACTION -> true
            AiPermissionLevel.PROTECTED_ACTION -> userConfirmed
        }
    }

    fun diagnoseNetworkOffline(pingMs: Double?, jitterMs: Double?, packetLossPct: Double): LocalDiagnosis {
        return when {
            packetLossPct >= 5.0 -> LocalDiagnosis(
                titleAr = "فقدان حزم مرتفع (${packetLossPct.toInt()}%)",
                titleEn = "High packet loss (${packetLossPct.toInt()}%)",
                recommendedConnections = 2,
                suggestedAction = "Reduce parallel download connections to 2 and test switching DNS or VPN node."
            )
            (pingMs ?: 0.0) > 150.0 || (jitterMs ?: 0.0) > 35.0 -> LocalDiagnosis(
                titleAr = "تذبذب أو تأخير مرتفع في الشبكة",
                titleEn = "Elevated latency or jitter detected",
                recommendedConnections = 4,
                suggestedAction = "Run DNS benchmark and compare pre/post-VPN routing."
            )
            else -> LocalDiagnosis(
                titleAr = "الاتصال مستقر وجاهز للتنزيل السريع",
                titleEn = "Connection is stable for high-speed downloads",
                recommendedConnections = 8,
                suggestedAction = "Safe to use adaptive multi-connection downloading up to 8 connections."
            )
        }
    }
}
