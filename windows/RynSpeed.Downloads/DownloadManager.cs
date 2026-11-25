using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using RynSpeed.Security;

namespace RynSpeed.Downloads;

/// <summary>
/// IDM-style download queue: add / pause / resume / cancel / remove, a limit on parallel downloads, and an
/// encrypted (DPAPI, current user) on-disk list, so unfinished downloads survive closing the app or a crash.
/// </summary>
public sealed class DownloadManager : IDisposable
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("RYN-AI-Speed.downloads.v1");
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = false };

    private readonly RynDownloadEngine _engine;
    private readonly string _storePath;
    private readonly List<DownloadJob> _jobs = [];
    private readonly Dictionary<string, CancellationTokenSource> _running = [];
    private readonly Dictionary<string, string> _stopReasons = [];
    private readonly SemaphoreSlim _slots;
    private readonly Lock _sync = new();
    private readonly Lock _saveLock = new();
    private long _lastSaveTicks;

    /// <summary>Raised (from any thread) whenever something changed; the UI should marshal to its thread.</summary>
    public event Action? Changed;

    public DownloadManager(RynDownloadEngine engine, string storePath, int maxConcurrent = 3)
    {
        _engine = engine;
        _storePath = storePath;
        _slots = new SemaphoreSlim(Math.Clamp(maxConcurrent, 1, 8));
        Load();
    }

    public IReadOnlyList<DownloadJob> Snapshot()
    {
        lock (_sync)
        {
            return [.. _jobs];
        }
    }

    public void SetGlobalSpeedLimit(long bytesPerSecond) => _engine.SetGlobalSpeedLimit(bytesPerSecond);

    public DownloadJob Add(string url, int maxConnections = DownloadPolicy.DefaultMaxConnections, long speedLimitBps = 0L)
    {
        var job = new DownloadJob
        {
            Url = url.Trim(),
            MaxConnections = Math.Clamp(maxConnections, 1, DownloadPolicy.HardMaxConnections),
            SpeedLimitBps = Math.Max(0L, speedLimitBps)
        };
        job.FileName = FriendlyNameFromUrl(job.Url);
        lock (_sync)
        {
            _jobs.Add(job);
        }
        Save(force: true);
        Changed?.Invoke();
        _ = RunAsync(job);
        return job;
    }

    public void Pause(string id) => Stop(id, "PAUSE");

    public void Resume(string id)
    {
        var job = Find(id);
        if (job is null || job.Status is "COMPLETED" or "DOWNLOADING" or "QUEUED") return;
        job.Status = "QUEUED";
        Changed?.Invoke();
        _ = RunAsync(job);
    }

    /// <summary>Stops the download and deletes its partial file.</summary>
    public void Cancel(string id)
    {
        if (Stop(id, "CANCEL")) return;

        var job = Find(id);
        if (job is null || job.Status == "COMPLETED") return;
        DeletePartial(job);
        job.Status = "CANCELED";
        Save(force: true);
        Changed?.Invoke();
    }

    /// <summary>Removes the row. A finished file stays on disk unless <paramref name="deleteFile"/> is true.</summary>
    public void Remove(string id, bool deleteFile)
    {
        Stop(id, "CANCEL");
        DownloadJob? job;
        lock (_sync)
        {
            job = _jobs.FirstOrDefault(j => j.Id == id);
            if (job is not null) _jobs.Remove(job);
        }
        if (job is null) return;

        if (job.Status != "COMPLETED") DeletePartial(job);
        if (deleteFile && job.Status == "COMPLETED" && job.FinalPath is not null) TryDelete(job.FinalPath);
        Save(force: true);
        Changed?.Invoke();
    }

    private DownloadJob? Find(string id)
    {
        lock (_sync)
        {
            return _jobs.FirstOrDefault(j => j.Id == id);
        }
    }

    private bool Stop(string id, string reason)
    {
        CancellationTokenSource? cts;
        lock (_sync)
        {
            if (!_running.TryGetValue(id, out cts)) return false;
            _stopReasons[id] = reason;
        }
        try
        {
            cts.Cancel();
        }
        catch (ObjectDisposedException)
        {
            // the run just finished on its own
        }
        return true;
    }

    private string? TakeStopReason(string id)
    {
        lock (_sync)
        {
            return _stopReasons.Remove(id, out var reason) ? reason : null;
        }
    }

    private async Task RunAsync(DownloadJob job)
    {
        var cts = new CancellationTokenSource();
        lock (_sync)
        {
            if (_running.ContainsKey(job.Id))
            {
                cts.Dispose();
                return;
            }
            _running[job.Id] = cts;
        }

        try
        {
            await _slots.WaitAsync(cts.Token).ConfigureAwait(false);
            try
            {
                await _engine.RunJobAsync(job, OnCheckpoint, cts.Token).ConfigureAwait(false);
            }
            finally
            {
                _slots.Release();
            }
        }
        catch (OperationCanceledException) when (cts.IsCancellationRequested)
        {
            if (TakeStopReason(job.Id) == "CANCEL")
            {
                DeletePartial(job);
                job.Status = "CANCELED";
            }
            else
            {
                job.Status = "PAUSED"; // partial file + checkpoint are kept, so Resume continues from here
            }
        }
        catch (Exception ex)
        {
            job.Status = "FAILED";
            job.LastError = LogRedactor.Redact(ex.Message);
            if (job.Segments.Count == 0 && job.TempPath is not null) TryDelete(job.TempPath);
        }
        finally
        {
            lock (_sync)
            {
                _running.Remove(job.Id);
                _stopReasons.Remove(job.Id);
            }
            cts.Dispose();
            Save(force: true);
            Changed?.Invoke();
        }
    }

    private void OnCheckpoint()
    {
        Save(force: false);
        Changed?.Invoke();
    }

    private static void DeletePartial(DownloadJob job)
    {
        if (job.TempPath is not null) TryDelete(job.TempPath);
        job.Segments = [];
        job.DownloadedBytes = 0;
    }

    private static void TryDelete(string path)
    {
        try
        {
            File.Delete(path);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // best effort
        }
    }

    private static string FriendlyNameFromUrl(string url)
    {
        if (Uri.TryCreate(url, UriKind.Absolute, out var uri))
        {
            var name = Path.GetFileName(uri.LocalPath);
            if (!string.IsNullOrWhiteSpace(name)) return name;
            return uri.Host;
        }
        return url;
    }

    private void Save(bool force)
    {
        var now = Environment.TickCount64;
        if (!force && now - Interlocked.Read(ref _lastSaveTicks) < 1000) return;
        Interlocked.Exchange(ref _lastSaveTicks, now);

        try
        {
            byte[] json;
            lock (_sync)
            {
                json = JsonSerializer.SerializeToUtf8Bytes(_jobs, JsonOptions);
            }
            var blob = ProtectedData.Protect(json, Entropy, DataProtectionScope.CurrentUser);
            lock (_saveLock)
            {
                var dir = Path.GetDirectoryName(_storePath);
                if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
                var tmp = _storePath + ".tmp";
                File.WriteAllBytes(tmp, blob);
                File.Move(tmp, _storePath, overwrite: true);
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or CryptographicException)
        {
            // Saving is best effort; the next checkpoint tries again.
        }
    }

    private void Load()
    {
        try
        {
            if (!File.Exists(_storePath)) return;
            var json = ProtectedData.Unprotect(File.ReadAllBytes(_storePath), Entropy, DataProtectionScope.CurrentUser);
            var loaded = JsonSerializer.Deserialize<List<DownloadJob>>(json, JsonOptions) ?? [];
            foreach (var job in loaded)
            {
                // Nothing is running right after startup: whatever was active is now simply paused.
                if (job.Status is "DOWNLOADING" or "QUEUED") job.Status = "PAUSED";
                _jobs.Add(job);
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or CryptographicException or JsonException)
        {
            try
            {
                File.Move(_storePath, _storePath + ".bad", overwrite: true);
            }
            catch (Exception moveEx) when (moveEx is IOException or UnauthorizedAccessException)
            {
                // keep going with an empty list
            }
        }
    }

    public void Dispose()
    {
        lock (_sync)
        {
            foreach (var cts in _running.Values)
            {
                try
                {
                    cts.Cancel();
                }
                catch (ObjectDisposedException)
                {
                    // already finished
                }
            }
        }

        // Give running downloads a moment to write their final checkpoint (they become PAUSED).
        SpinWait.SpinUntil(() =>
        {
            lock (_sync)
            {
                return _running.Count == 0;
            }
        }, TimeSpan.FromSeconds(4));

        Save(force: true);
        _slots.Dispose();
    }
}
