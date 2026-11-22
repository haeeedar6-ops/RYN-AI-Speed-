namespace RynSpeed.AI;

public enum AiPermissionLevel
{
    Read,
    SafeAction,
    ProtectedAction
}

public sealed record LocalDiagnosis(
    string TitleAr,
    string TitleEn,
    int RecommendedConnections,
    string SuggestedAction);

/// <summary>
/// Offline Local AI Rules Engine & Client-Side 3-Tier RBAC Tool Gate for Windows.
/// </summary>
public static class LocalAiRulesEngine
{
    private static readonly Dictionary<string, AiPermissionLevel> ToolPermissions = new(StringComparer.Ordinal)
    {
        ["get_network_stats"] = AiPermissionLevel.Read,
        ["get_dns_info"] = AiPermissionLevel.Read,
        ["list_vpn_servers"] = AiPermissionLevel.Read,
        ["get_downloads"] = AiPermissionLevel.Read,
        ["get_server_health"] = AiPermissionLevel.Read,
        ["run_speed_test"] = AiPermissionLevel.SafeAction,
        ["pause_download"] = AiPermissionLevel.SafeAction,
        ["resume_download"] = AiPermissionLevel.SafeAction,
        ["set_download_limit"] = AiPermissionLevel.SafeAction,
        ["connect_vpn"] = AiPermissionLevel.ProtectedAction,
        ["disconnect_vpn"] = AiPermissionLevel.ProtectedAction,
        ["cancel_download"] = AiPermissionLevel.ProtectedAction
    };

    public static bool CanExecute(string toolName, bool userConfirmed)
    {
        if (!ToolPermissions.TryGetValue(toolName, out var level)) return false;
        return level != AiPermissionLevel.ProtectedAction || userConfirmed;
    }

    public static LocalDiagnosis DiagnoseNetworkOffline(double? pingMs, double? jitterMs, double packetLossPct)
    {
        if (packetLossPct >= 5.0)
        {
            return new LocalDiagnosis(
                $"فقدان حزم مرتفع ({(int)packetLossPct}%)",
                $"High packet loss ({(int)packetLossPct}%)",
                2,
                "Reduce parallel download connections to 2 and test switching DNS or VPN node.");
        }

        if ((pingMs ?? 0.0) > 150.0 || (jitterMs ?? 0.0) > 35.0)
        {
            return new LocalDiagnosis(
                "تذبذب أو تأخير مرتفع في الشبكة",
                "Elevated latency or jitter detected",
                4,
                "Run DNS benchmark and compare pre/post-VPN routing.");
        }

        return new LocalDiagnosis(
            "الاتصال مستقر وجاهز للتنزيل السريع",
            "Connection is stable for high-speed downloads",
            8,
            "Safe to use adaptive multi-connection downloading up to 8 connections.");
    }
}
