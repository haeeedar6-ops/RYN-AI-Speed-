using System.Text;
using System.Text.RegularExpressions;
using RynSpeed.Core;

namespace RynSpeed.VPN;

public enum VpnTunnelState
{
    Disconnected,
    Connecting,
    VerifyingRoute,
    Connected,
    Disconnecting,
    Error
}

public sealed record VpnServerProfile(
    string ServerId,
    string ServerName,
    string Country,
    string Region,
    string Endpoint,
    string PublicKey,
    string ServerType,        // PRIVATE | COMMUNITY
    bool OperatorVerified,
    bool NoLogsVerified,
    string HealthStatus,      // HEALTHY | DEGRADED | OFFLINE
    string CapacityStatus,    // LOW | MEDIUM | HIGH | FULL
    double? PingMs,
    double? JitterMs,
    double? PacketLossPct,
    double HistoricalStabilityPct = 99.0);

public sealed record KillSwitchPolicy(
    string TunnelInterfaceName,
    string AllowedEndpointIp,
    int AllowedEndpointUdpPort,
    bool BlockNonTunnelIpv4,
    bool BlockNonTunnelIpv6,
    bool DisableWindowsSmhnrDns);

/// <summary>
/// Weighted VPN Server Auto-Selector (matches Android VpnServerScorer contract identically).
/// </summary>
public static partial class VpnServerScorer
{
    [GeneratedRegex(@"^[A-Za-z0-9+/]{43}=$")]
    private static partial Regex WireGuardKeyRegex();

    public static bool IsValidWireGuardKey(string key) =>
        !string.IsNullOrWhiteSpace(key) && WireGuardKeyRegex().IsMatch(key.Trim());

    public static bool IsEligible(VpnServerProfile server)
    {
        if (!string.Equals(server.HealthStatus, "HEALTHY", StringComparison.Ordinal)) return false;
        if (string.Equals(server.CapacityStatus, "FULL", StringComparison.Ordinal)) return false;
        if (!IsValidWireGuardKey(server.PublicKey)) return false;
        if (string.Equals(server.ServerType, "COMMUNITY", StringComparison.Ordinal) &&
            (!server.OperatorVerified || !server.NoLogsVerified))
        {
            return false;
        }
        return true;
    }

    public static double ScoreServer(VpnServerProfile server)
    {
        if (!IsEligible(server)) return -1.0;

        var ping = Math.Clamp(server.PingMs ?? 250.0, 1.0, 500.0);
        var loss = Math.Clamp(server.PacketLossPct ?? 5.0, 0.0, 100.0);
        var jitter = Math.Clamp(server.JitterMs ?? 30.0, 0.0, 200.0);

        var pingScore = (1.0 - (ping / 500.0)) * 35.0;
        var lossScore = (1.0 - (loss / 100.0)) * 30.0;
        var jitterScore = (1.0 - (jitter / 200.0)) * 15.0;
        var capacityScore = server.CapacityStatus switch
        {
            "LOW" => 10.0,
            "MEDIUM" => 7.0,
            "HIGH" => 3.0,
            _ => 4.0
        };
        var stabilityScore = (Math.Clamp(server.HistoricalStabilityPct, 0.0, 100.0) / 100.0) * 7.0;
        var privateBonus = string.Equals(server.ServerType, "PRIVATE", StringComparison.Ordinal) ? 3.0 : 0.0;

        return pingScore + lossScore + jitterScore + capacityScore + stabilityScore + privateBonus;
    }

    public static VpnServerProfile? SelectBestServer(IEnumerable<VpnServerProfile> servers) =>
        servers.Where(IsEligible).OrderByDescending(ScoreServer).FirstOrDefault();
}

/// <summary>
/// Generates strict WireGuard tunnel configs for Windows Wintun service.
/// Private keys stay strictly in DPAPI memory/vault and are never sent to Backend or AI.
/// </summary>
public static class WireGuardConfigBuilder
{
    public static string BuildConfig(
        string clientPrivateKeyBase64,
        string clientAddressCidr,
        VpnServerProfile server,
        IReadOnlyList<string>? dnsResolvers = null)
    {
        if (!VpnServerScorer.IsValidWireGuardKey(clientPrivateKeyBase64))
            throw new ArgumentException("Invalid client WireGuard private key.");
        if (!VpnServerScorer.IsValidWireGuardKey(server.PublicKey))
            throw new ArgumentException("Invalid server WireGuard public key.");
        if (!server.Endpoint.Contains(':', StringComparison.Ordinal))
            throw new ArgumentException("Endpoint must be in host:port format.");

        var dns = dnsResolvers is { Count: > 0 } ? dnsResolvers : ["1.1.1.1", "9.9.9.9"];
        var sb = new StringBuilder();
        sb.AppendLine("[Interface]");
        sb.AppendLine($"PrivateKey = {clientPrivateKeyBase64}");
        sb.AppendLine($"Address = {clientAddressCidr}");
        sb.AppendLine($"DNS = {string.Join(", ", dns)}");
        sb.AppendLine("MTU = 1280");
        sb.AppendLine();
        sb.AppendLine("[Peer]");
        sb.AppendLine($"PublicKey = {server.PublicKey}");
        sb.AppendLine($"Endpoint = {server.Endpoint}");
        sb.AppendLine("AllowedIPs = 0.0.0.0/0, ::/0");
        sb.AppendLine("PersistentKeepalive = 25");
        return sb.ToString();
    }
}

/// <summary>
/// OS-level Kill Switch contract backed by Windows Filtering Platform (WFP) persistent sublayer filters.
/// Blocks all non-tunnel IPv4/IPv6 traffic and disables SMHNR DNS leaks even if the UI app crashes.
/// </summary>
public interface IWindowsKillSwitchWfp
{
    Task<RynResult<bool>> EngageAsync(KillSwitchPolicy policy, CancellationToken ct = default);
    Task<RynResult<bool>> DisengageAsync(CancellationToken ct = default);
    bool IsEngaged { get; }
}

public sealed class RynWindowsVpnController(ModuleEventBus eventBus)
{
    public VpnTunnelState State { get; private set; } = VpnTunnelState.Disconnected;

    public void TransitionTo(VpnTunnelState next)
    {
        State = next;
        eventBus.Publish(new NetworkStateChangedEvent(
            IsOnline: next != VpnTunnelState.Error,
            VpnActive: next == VpnTunnelState.Connected));
    }
}
