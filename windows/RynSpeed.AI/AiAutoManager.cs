namespace RynSpeed.AI;

public sealed record AutoManagerPolicy(
    bool AutoRetryFailedDownloads = true,
    int MaxAutoRetriesPerDownload = 3,
    bool SuggestVpnSwitchOnDegradation = true,
    double PingDegradationThresholdMs = 150.0,
    double PacketLossThresholdPct = 5.0,
    long MonthlyDataCapBytes = 0L);

public abstract record AutoManagerDecision;
public sealed record DisabledByMasterSwitchDecision : AutoManagerDecision;
public sealed record NoActionNeededDecision : AutoManagerDecision;
public sealed record RetryDownloadSafeDecision(string DownloadId, int Attempt) : AutoManagerDecision;
public sealed record ProposeVpnSwitchDecision(string Reason, bool RequiresUserApproval = true) : AutoManagerDecision;
public sealed record DataCapWarningDecision(long UsedBytes, long CapBytes) : AutoManagerDecision;

/// <summary>
/// Windows AI Auto Manager with instant Master Kill Switch, safe retry limits,
/// and non-silent VPN switch proposals requiring explicit user approval.
/// </summary>
public sealed class AiAutoManager(AutoManagerPolicy? policy = null, bool initialEnabled = true)
{
    private volatile int _masterEnabled = initialEnabled ? 1 : 0;
    public AutoManagerPolicy Policy { get; set; } = policy ?? new AutoManagerPolicy();

    public bool IsMasterEnabled => _masterEnabled == 1;
    public void SetMasterEnabled(bool enabled) => Interlocked.Exchange(ref _masterEnabled, enabled ? 1 : 0);

    public AutoManagerDecision EvaluateNetworkTelemetry(double? pingMs, double packetLossPct, bool vpnActive)
    {
        if (!IsMasterEnabled) return new DisabledByMasterSwitchDecision();
        if (!Policy.SuggestVpnSwitchOnDegradation || !vpnActive) return new NoActionNeededDecision();

        if (packetLossPct >= Policy.PacketLossThresholdPct)
            return new ProposeVpnSwitchDecision($"Packet loss reached {(int)packetLossPct}% on current VPN node.");
        if ((pingMs ?? 0.0) >= Policy.PingDegradationThresholdMs)
            return new ProposeVpnSwitchDecision($"Ping rose to {(int)(pingMs ?? 0)} ms on current VPN node.");

        return new NoActionNeededDecision();
    }

    public AutoManagerDecision EvaluateDownloadFailure(string downloadId, int currentRetries, bool isFatalAuthOrNotFound)
    {
        if (!IsMasterEnabled) return new DisabledByMasterSwitchDecision();
        if (!Policy.AutoRetryFailedDownloads || isFatalAuthOrNotFound) return new NoActionNeededDecision();
        if (currentRetries >= Policy.MaxAutoRetriesPerDownload) return new NoActionNeededDecision();
        return new RetryDownloadSafeDecision(downloadId, currentRetries + 1);
    }

    public AutoManagerDecision EvaluateDataUsage(long usedBytes)
    {
        if (!IsMasterEnabled) return new DisabledByMasterSwitchDecision();
        if (Policy.MonthlyDataCapBytes > 0L && usedBytes >= (Policy.MonthlyDataCapBytes * 85L) / 100L)
            return new DataCapWarningDecision(usedBytes, Policy.MonthlyDataCapBytes);
        return new NoActionNeededDecision();
    }
}
