using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using RynSpeed.Core;

namespace RynSpeed.Network;

public sealed record LatencyReport(
    string Host,
    int Port,
    int ProbesSent,
    int ProbesSucceeded,
    double? AvgPingMs,
    double? JitterMs,
    double PacketLossPct);

public sealed record DnsBenchmarkEntry(
    string Name,
    string ResolverIp,
    double? AvgLatencyMs,
    bool Reachable);

public sealed record SpeedTestReport(
    double DownloadMbps,
    double UploadMbps,
    double? PingMs,
    double? JitterMs,
    double PacketLossPct,
    bool VpnActive);

public sealed record VpnComparisonDelta(
    double? PingDeltaMs,
    double DownloadDeltaMbps,
    double PacketLossDeltaPct,
    string SummaryRecommendation);

public static class NetworkMetricsCalculator
{
    public static LatencyReport ComputeStats(string host, int port, int probesSent, IReadOnlyList<double> rttSamplesMs)
    {
        var sent = Math.Max(1, probesSent);
        var succeeded = rttSamplesMs.Count;
        var lossPct = Math.Clamp(((sent - succeeded) / (double)sent) * 100.0, 0.0, 100.0);
        double? avgPing = succeeded > 0 ? rttSamplesMs.Average() : null;
        double? jitter = succeeded switch
        {
            0 => null,
            1 => 0.0,
            _ => Enumerable.Range(1, succeeded - 1)
                .Select(i => Math.Abs(rttSamplesMs[i] - rttSamplesMs[i - 1]))
                .Average()
        };
        return new LatencyReport(host, port, sent, succeeded, avgPing, jitter, lossPct);
    }

    public static VpnComparisonDelta ComparePreAndPostVpn(SpeedTestReport before, SpeedTestReport after)
    {
        double? pingDelta = before.PingMs.HasValue && after.PingMs.HasValue
            ? after.PingMs.Value - before.PingMs.Value
            : null;
        var dlDelta = after.DownloadMbps - before.DownloadMbps;
        var lossDelta = after.PacketLossPct - before.PacketLossPct;

        var rec = after.PacketLossPct > 5.0
            ? "High packet loss detected over VPN; switch to a lower-loss server."
            : pingDelta is > 80.0
                ? $"VPN adds significant latency (+{(int)pingDelta.Value} ms); try a closer node."
                : dlDelta > 0.5 && after.PacketLossPct <= before.PacketLossPct
                    ? "VPN routing improved throughput with stable packet delivery."
                    : "Connection is within normal variance for your ISP line.";

        return new VpnComparisonDelta(pingDelta, dlDelta, lossDelta, rec);
    }
}

/// <summary>
/// Real Windows Network Diagnostics & Optimizer Engine (zero fabricated metrics).
/// </summary>
public sealed class RynNetworkOptimizer : IDisposable
{
    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromSeconds(25) };

    private static readonly (string Name, string Ip)[] TrustedResolvers =
    [
        ("Cloudflare", "1.1.1.1"),
        ("Quad9", "9.9.9.9"),
        ("Google", "8.8.8.8"),
        ("OpenDNS", "208.67.222.222")
    ];

    public Task<RynResult<LatencyReport>> MeasureTcpLatencyAsync(
        string host,
        int port = 443,
        int count = 5,
        int timeoutMs = 2000,
        CancellationToken ct = default) =>
        FaultIsolation.RunIsolatedAsync("PING_TEST_FAILED", async token =>
        {
            var probes = Math.Clamp(count, 1, 20);
            var samples = new List<double>(probes);

            for (var i = 0; i < probes; i++)
            {
                using var tcp = new TcpClient();
                using var cts = CancellationTokenSource.CreateLinkedTokenSource(token);
                cts.CancelAfter(timeoutMs);
                var sw = Stopwatch.StartNew();
                try
                {
                    await tcp.ConnectAsync(host, port, cts.Token).ConfigureAwait(false);
                    sw.Stop();
                    samples.Add(sw.Elapsed.TotalMilliseconds);
                }
                catch (OperationCanceledException) when (!token.IsCancellationRequested)
                {
                    // Probe timeout counts as packet loss
                }
                catch (SocketException)
                {
                    // Socket unreachable counts as packet loss
                }
            }
            return NetworkMetricsCalculator.ComputeStats(host, port, probes, samples);
        }, ct);

    public Task<RynResult<IReadOnlyList<DnsBenchmarkEntry>>> BenchmarkDnsResolversAsync(CancellationToken ct = default) =>
        FaultIsolation.RunIsolatedAsync<IReadOnlyList<DnsBenchmarkEntry>>("DNS_BENCH_FAILED", async token =>
        {
            var results = new List<DnsBenchmarkEntry>(TrustedResolvers.Length);
            foreach (var (name, ip) in TrustedResolvers)
            {
                var res = await MeasureTcpLatencyAsync(ip, 53, 3, 1500, token).ConfigureAwait(false);
                results.Add(res.IsSuccess && res.Value is not null
                    ? new DnsBenchmarkEntry(name, ip, res.Value.AvgPingMs, res.Value.ProbesSucceeded > 0)
                    : new DnsBenchmarkEntry(name, ip, null, false));
            }
            return results
                .OrderByDescending(r => r.Reachable)
                .ThenBy(r => r.AvgLatencyMs ?? double.MaxValue)
                .ToList();
        }, ct);

    public Task<RynResult<SpeedTestReport>> RunRealSpeedTestAsync(
        string downloadEndpointUrl,
        string? uploadEndpointUrl,
        bool vpnActive,
        CancellationToken ct = default) =>
        FaultIsolation.RunIsolatedAsync("SPEED_TEST_FAILED", async token =>
        {
            var latencyRes = await MeasureTcpLatencyAsync("1.1.1.1", 443, 5, 2000, token).ConfigureAwait(false);

            var swDl = Stopwatch.StartNew();
            long dlBytes = 0;
            using (var resp = await _http.GetAsync(downloadEndpointUrl, HttpCompletionOption.ResponseHeadersRead, token).ConfigureAwait(false))
            {
                resp.EnsureSuccessStatusCode();
                await using var stream = await resp.Content.ReadAsStreamAsync(token).ConfigureAwait(false);
                var buf = new byte[64 * 1024];
                while (true)
                {
                    var read = await stream.ReadAsync(buf, token).ConfigureAwait(false);
                    if (read == 0) break;
                    dlBytes += read;
                }
            }
            swDl.Stop();
            var dlMbps = (dlBytes * 8.0) / (Math.Max(0.001, swDl.Elapsed.TotalSeconds) * 1_000_000.0);

            double ulMbps = 0.0;
            if (!string.IsNullOrWhiteSpace(uploadEndpointUrl))
            {
                var payload = new byte[512 * 1024];
                var swUl = Stopwatch.StartNew();
                using var content = new ByteArrayContent(payload);
                using var resp = await _http.PostAsync(uploadEndpointUrl, content, token).ConfigureAwait(false);
                swUl.Stop();
                if (resp.IsSuccessStatusCode)
                {
                    ulMbps = (payload.Length * 8.0) / (Math.Max(0.001, swUl.Elapsed.TotalSeconds) * 1_000_000.0);
                }
            }

            return new SpeedTestReport(
                DownloadMbps: dlMbps,
                UploadMbps: ulMbps,
                PingMs: latencyRes.Value?.AvgPingMs,
                JitterMs: latencyRes.Value?.JitterMs,
                PacketLossPct: latencyRes.Value?.PacketLossPct ?? 100.0,
                VpnActive: vpnActive);
        }, ct);

    public void Dispose() => _http.Dispose();
}

/// <summary>
/// Monitors live Windows network adapter transitions (Ethernet / Wi-Fi / VPN).
/// </summary>
public sealed class WindowsConnectionMonitor(ModuleEventBus eventBus) : IDisposable
{
    public void Start()
    {
        NetworkChange.NetworkAvailabilityChanged += OnAvailabilityChanged;
        NetworkChange.NetworkAddressChanged += OnAddressChanged;
    }

    private void OnAvailabilityChanged(object? sender, NetworkAvailabilityEventArgs e) =>
        eventBus.Publish(new NetworkStateChangedEvent(e.IsAvailable, IsVpnInterfaceActive()));

    private void OnAddressChanged(object? sender, EventArgs e) =>
        eventBus.Publish(new NetworkStateChangedEvent(NetworkInterface.GetIsNetworkAvailable(), IsVpnInterfaceActive()));

    private static bool IsVpnInterfaceActive() =>
        NetworkInterface.GetAllNetworkInterfaces().Any(n =>
            n.OperationalStatus == OperationalStatus.Up &&
            (n.Description.Contains("WireGuard", StringComparison.OrdinalIgnoreCase) ||
             n.Description.Contains("Wintun", StringComparison.OrdinalIgnoreCase)));

    public void Dispose()
    {
        NetworkChange.NetworkAvailabilityChanged -= OnAvailabilityChanged;
        NetworkChange.NetworkAddressChanged -= OnAddressChanged;
    }
}
