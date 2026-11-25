using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Sockets;
using RynSpeed.Core;
using RynSpeed.Security;

namespace RynSpeed.Downloads;

/// <summary>
/// Production Windows Download Engine (.NET 10 LTS):
/// HTTP/2+3 SocketsHttpHandler, SSRF-checked redirects, If-Range validation,
/// Dynamic Work-Stealing, Token-Bucket throttling, and atomic SHA-256 finalization.
/// </summary>
public sealed class RynDownloadEngine : IDisposable
{
    private readonly HttpClient _http;
    private readonly ModuleEventBus _eventBus;
    private readonly string _rootDownloadDir;
    private readonly bool _allowLocalNetwork;
    private readonly TokenBucketLimiter _globalLimiter = new(0L);
    private const int MaxRedirects = 5;

    public RynDownloadEngine(ModuleEventBus eventBus, string rootDownloadDir, bool allowLocalNetwork = false)
    {
        _eventBus = eventBus;
        _rootDownloadDir = rootDownloadDir;
        _allowLocalNetwork = allowLocalNetwork;
        var handler = new SocketsHttpHandler
        {
            AllowAutoRedirect = false,
            PooledConnectionLifetime = TimeSpan.FromMinutes(2),
            ConnectTimeout = TimeSpan.FromSeconds(15),
            AutomaticDecompression = DecompressionMethods.None
        };
        if (!allowLocalNetwork)
        {
            // Anti DNS-rebinding: validate the address we REALLY connect to, not only the one resolved earlier.
            handler.ConnectCallback = SsrfSafeConnectAsync;
        }
        _http = new HttpClient(handler) { Timeout = Timeout.InfiniteTimeSpan };
    }

    public void SetGlobalSpeedLimit(long bytesPerSecond) => _globalLimiter.BytesPerSecond = bytesPerSecond;

    public Task<RynResult<ProbeResult>> ProbeUrlAsync(string rawUrl, CancellationToken ct = default) =>
        FaultIsolation.RunIsolatedAsync("PROBE_FAILED", async token =>
        {
            var currentUri = await UrlAndPathValidator
                .ValidateDownloadUrlAsync(rawUrl, _allowLocalNetwork, token)
                .ConfigureAwait(false);

            for (var hop = 0; ; hop++)
            {
                // One ranged GET (bytes=0-0) gives redirects, size, Range support and validators together.
                using var rangeReq = new HttpRequestMessage(HttpMethod.Get, currentUri);
                rangeReq.Headers.Range = new RangeHeaderValue(0, 0);
                using var rangeResp = await _http.SendAsync(rangeReq, HttpCompletionOption.ResponseHeadersRead, token).ConfigureAwait(false);

                if (rangeResp.StatusCode is HttpStatusCode.MovedPermanently or HttpStatusCode.Found or
                    HttpStatusCode.SeeOther or HttpStatusCode.TemporaryRedirect or HttpStatusCode.PermanentRedirect)
                {
                    if (hop >= MaxRedirects)
                        throw new InvalidOperationException("TOO_MANY_REDIRECTS: Redirect limit exceeded.");
                    var location = rangeResp.Headers.Location
                        ?? throw new InvalidOperationException("Redirect without Location header.");
                    var nextUri = location.IsAbsoluteUri ? location : new Uri(currentUri, location);
                    currentUri = await UrlAndPathValidator
                        .ValidateDownloadUrlAsync(nextUri.ToString(), _allowLocalNetwork, token)
                        .ConfigureAwait(false);
                    continue;
                }

                CheckFatalStatus(rangeResp.StatusCode);
                if (!rangeResp.IsSuccessStatusCode)
                    throw new HttpRequestException($"Unexpected HTTP {(int)rangeResp.StatusCode}");

                var supportsRanges = rangeResp.StatusCode == HttpStatusCode.PartialContent;
                // A 206 with unknown total ("bytes 0-0/*") must not fall back to Content-Length (that is 1).
                var totalSize = supportsRanges
                    ? rangeResp.Content.Headers.ContentRange?.Length
                    : rangeResp.Content.Headers.ContentLength;
                var rawName = rangeResp.Content.Headers.ContentDisposition?.FileNameStar
                    ?? rangeResp.Content.Headers.ContentDisposition?.FileName?.Trim('"')
                    ?? Path.GetFileName(currentUri.LocalPath);
                var safeName = UrlAndPathValidator.SanitizeFileName(string.IsNullOrWhiteSpace(rawName) ? "download.bin" : rawName);
                var mime = rangeResp.Content.Headers.ContentType?.MediaType;

                return new ProbeResult(
                    FinalUri: currentUri,
                    FileName: safeName,
                    Category: DownloadPolicy.ClassifyCategory(safeName, mime),
                    MimeType: mime,
                    FileSize: totalSize,
                    SupportsRanges: supportsRanges,
                    ETag: rangeResp.Headers.ETag?.ToString(),
                    LastModified: rangeResp.Content.Headers.LastModified?.ToString("R", System.Globalization.CultureInfo.InvariantCulture));
            }
        }, ct);

    public Task<RynResult<(string FilePath, string Sha256)>> DownloadAsync(
        string rawUrl,
        string? expectedSha256 = null,
        int maxConnectionsCap = DownloadPolicy.DefaultMaxConnections,
        long perDownloadLimitBps = 0L,
        CancellationToken ct = default) =>
        FaultIsolation.RunIsolatedAsync("DOWNLOAD_FAILED", async token =>
        {
            var probeRes = await ProbeUrlAsync(rawUrl, token).ConfigureAwait(false);
            if (!probeRes.IsSuccess || probeRes.Value is null)
                throw new InvalidOperationException($"{probeRes.ErrorCode}: {probeRes.ErrorMessage}");

            var probe = probeRes.Value;
            var downloadId = Guid.NewGuid().ToString("N");
            var categoryDir = Path.Combine(_rootDownloadDir, probe.Category.ToLowerInvariant());
            Directory.CreateDirectory(categoryDir);

            var finalPath = ClaimUniqueTarget(categoryDir, probe.FileName);
            var tempPath = finalPath + DownloadPolicy.SparseTempExtension;
            var connections = DownloadPolicy.ComputeOptimalConnections(probe.FileSize, probe.SupportsRanges, maxConnectionsCap);
            using var itemLimiter = new TokenBucketLimiter(perDownloadLimitBps);
            // If-Range needs a STRONG validator; a weak ETag (W/"..") would make servers answer 200.
            var ifRange = probe.ETag is { } etag && !etag.StartsWith("W/", StringComparison.Ordinal) ? etag : probe.LastModified;

            using var writer = new SparseFileWriter(tempPath, finalPath);
            if (probe.SupportsRanges && probe.FileSize is >= DownloadPolicy.MinRangedFileBytes)
            {
                writer.Preallocate(probe.FileSize.Value);
                var initial = BuildInitialSegments(downloadId, probe.FileSize.Value, connections);
                var coordinator = new SegmentCoordinator(downloadId, initial);

                // First failing worker cancels its siblings instead of letting them burn bandwidth.
                using var workersCts = CancellationTokenSource.CreateLinkedTokenSource(token);
                var workerToken = workersCts.Token;
                var workers = Enumerable.Range(0, connections).Select(workerIdx => Task.Run(async () =>
                {
                    try
                    {
                        ActiveSegment? seg = initial[workerIdx];
                        while (seg is not null)
                        {
                            await DownloadSegmentAsync(
                                probe.FinalUri, seg, ifRange, writer, itemLimiter, downloadId, probe.FileSize.Value, workerToken
                            ).ConfigureAwait(false);
                            seg = coordinator.StealWorkOrNull();
                        }
                    }
                    catch (Exception) when (!workerToken.IsCancellationRequested)
                    {
                        await workersCts.CancelAsync().ConfigureAwait(false);
                        throw;
                    }
                }, CancellationToken.None)).ToArray();

                await Task.WhenAll(workers).ConfigureAwait(false);
            }
            else
            {
                await DownloadSingleStreamAsync(probe.FinalUri, writer, itemLimiter, downloadId, probe.FileSize, token)
                    .ConfigureAwait(false);
            }

            var sha256 = await writer.FinalizeAtomicallyAsync(probe.FileSize, expectedSha256, token).ConfigureAwait(false);
            return (finalPath, sha256);
        }, ct);

    /// <summary>
    /// Runs (or continues) one download job. Throws on failure / cancellation; the caller decides what
    /// pause, cancel and failure mean. The job object always holds the latest checkpoint.
    /// </summary>
    public async Task RunJobAsync(DownloadJob job, Action onCheckpoint, CancellationToken ct)
    {
        var probeRes = await ProbeUrlAsync(job.Url, ct).ConfigureAwait(false);
        if (!probeRes.IsSuccess || probeRes.Value is null)
            throw new InvalidOperationException($"{probeRes.ErrorCode}: {probeRes.ErrorMessage}");
        var probe = probeRes.Value;

        // Continue the partial file only if the server still serves exactly the same bytes.
        var canResume = CanResume(job, probe);
        if (!canResume)
        {
            if (job.TempPath is not null) TryDeleteFile(job.TempPath);
            if (job.FinalPath is null)
            {
                var categoryDir = Path.Combine(_rootDownloadDir, probe.Category.ToLowerInvariant());
                Directory.CreateDirectory(categoryDir);
                job.FinalPath = ClaimUniqueTarget(categoryDir, probe.FileName);
            }
            job.TempPath = job.FinalPath + DownloadPolicy.SparseTempExtension;
            job.Segments = [];
            job.DownloadedBytes = 0;
        }

        var finalPath = job.FinalPath ?? throw new InvalidOperationException("Missing target path.");
        var tempPath = job.TempPath ?? throw new InvalidOperationException("Missing temp path.");
        job.FileName = Path.GetFileName(finalPath);
        job.Category = probe.Category;
        job.FileSize = probe.FileSize;
        job.SupportsRanges = probe.SupportsRanges;
        job.ETag = probe.ETag;
        job.LastModified = probe.LastModified;
        job.Status = "DOWNLOADING";
        job.LastError = null;
        onCheckpoint();

        var connections = DownloadPolicy.ComputeOptimalConnections(probe.FileSize, probe.SupportsRanges, job.MaxConnections);
        using var itemLimiter = new TokenBucketLimiter(job.SpeedLimitBps);
        // If-Range needs a STRONG validator; a weak ETag (W/"..") would make servers answer 200.
        var ifRange = probe.ETag is { } etag && !etag.StartsWith("W/", StringComparison.Ordinal) ? etag : probe.LastModified;

        using var writer = new SparseFileWriter(tempPath, finalPath);
        if (probe.SupportsRanges && probe.FileSize is >= DownloadPolicy.MinRangedFileBytes)
        {
            var size = probe.FileSize.Value;
            writer.Preallocate(size);
            var segments = canResume ? RestoreSegments(job) : BuildInitialSegments(job.Id, size, connections);
            foreach (var s in segments)
            {
                if (s.CurrentPosition() > s.EndByte) s.Status = "COMPLETED";
            }

            var coordinator = new SegmentCoordinator(job.Id, segments);
            var queue = new ConcurrentQueue<ActiveSegment>(segments.Where(s => s.Status != "COMPLETED"));

            using var checkpointCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            var checkpointTask = CheckpointLoopAsync(job, writer, coordinator, onCheckpoint, checkpointCts.Token);
            try
            {
                using var workersCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
                var workerToken = workersCts.Token;
                var workers = Enumerable.Range(0, connections).Select(_ => Task.Run(async () =>
                {
                    try
                    {
                        ActiveSegment? seg = queue.TryDequeue(out var first) ? first : null;
                        while (seg is not null)
                        {
                            await DownloadSegmentAsync(
                                probe.FinalUri, seg, ifRange, writer, itemLimiter, job.Id, size, workerToken
                            ).ConfigureAwait(false);
                            seg = queue.TryDequeue(out var next) ? next : coordinator.StealWorkOrNull();
                        }
                    }
                    catch (Exception) when (!workerToken.IsCancellationRequested)
                    {
                        await workersCts.CancelAsync().ConfigureAwait(false);
                        throw;
                    }
                }, CancellationToken.None)).ToArray();

                await Task.WhenAll(workers).ConfigureAwait(false);
            }
            finally
            {
                // Always leave a final, consistent checkpoint (also when paused or failed).
                await checkpointCts.CancelAsync().ConfigureAwait(false);
                await checkpointTask.ConfigureAwait(false);
                writer.FlushToDisk();
                CaptureSnapshot(job, coordinator);
                onCheckpoint();
            }
        }
        else
        {
            await DownloadSingleStreamAsync(probe.FinalUri, writer, itemLimiter, job.Id, probe.FileSize, ct, offset => job.DownloadedBytes = offset)
                .ConfigureAwait(false);
        }

        var sha256 = await writer.FinalizeAtomicallyAsync(probe.FileSize, job.ExpectedSha256, ct).ConfigureAwait(false);
        job.ActualSha256 = sha256;
        job.DownloadedBytes = new FileInfo(finalPath).Length;
        job.Segments = [];
        job.Status = "COMPLETED";
        onCheckpoint();
    }

    private static async Task CheckpointLoopAsync(
        DownloadJob job, SparseFileWriter writer, SegmentCoordinator coordinator, Action onCheckpoint, CancellationToken ct)
    {
        try
        {
            using var timer = new PeriodicTimer(TimeSpan.FromSeconds(1));
            while (await timer.WaitForNextTickAsync(ct).ConfigureAwait(false))
            {
                writer.FlushToDisk(); // flush first, then record: the checkpoint can never be ahead of the disk
                CaptureSnapshot(job, coordinator);
                onCheckpoint();
            }
        }
        catch (OperationCanceledException)
        {
            // normal shutdown of the checkpoint loop
        }
    }

    private static void CaptureSnapshot(DownloadJob job, SegmentCoordinator coordinator)
    {
        var states = coordinator.Snapshot().Select(s => new SegmentState
        {
            Id = s.Id,
            Index = s.Index,
            StartByte = s.StartByte,
            EndByte = s.EndByte,
            DownloadedBytes = Math.Min(s.DownloadedBytes, s.EndByte - s.StartByte + 1L)
        }).ToList();
        job.Segments = states;
        job.DownloadedBytes = states.Sum(x => x.DownloadedBytes);
    }

    private static List<ActiveSegment> RestoreSegments(DownloadJob job)
    {
        var list = new List<ActiveSegment>();
        foreach (var s in job.Segments.OrderBy(x => x.Index))
        {
            var seg = new ActiveSegment(s.Id, s.Index, s.StartByte, s.EndByte);
            seg.AddDownloaded(Math.Clamp(s.DownloadedBytes, 0L, s.EndByte - s.StartByte + 1L));
            list.Add(seg);
        }
        return list;
    }

    private static bool CanResume(DownloadJob job, ProbeResult probe)
    {
        if (job.TempPath is null || job.FinalPath is null || job.Segments.Count == 0) return false;
        if (!probe.SupportsRanges || probe.FileSize is null) return false;
        if (job.FileSize != probe.FileSize || job.ETag != probe.ETag || job.LastModified != probe.LastModified) return false;
        if (!File.Exists(job.TempPath) || new FileInfo(job.TempPath).Length != probe.FileSize.Value) return false;

        long expectedStart = 0;
        foreach (var s in job.Segments.OrderBy(x => x.StartByte))
        {
            if (s.StartByte != expectedStart || s.EndByte < s.StartByte) return false;
            expectedStart = s.EndByte + 1L;
        }
        return expectedStart == probe.FileSize.Value;
    }

    private static void TryDeleteFile(string path)
    {
        try
        {
            File.Delete(path);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // best effort: a locked temp file is cleaned up by the next run
        }
    }

    private async Task DownloadSegmentAsync(
        Uri uri,
        ActiveSegment segment,
        string? ifRange,
        SparseFileWriter writer,
        TokenBucketLimiter itemLimiter,
        string downloadId,
        long totalSize,
        CancellationToken ct)
    {
        for (var attempt = 0; attempt <= 5; attempt++)
        {
            segment.Status = attempt == 0 ? "CONNECTING" : "RETRYING";
            var resumeOffset = segment.CurrentPosition();
            if (resumeOffset > segment.EndByte)
            {
                segment.Status = "COMPLETED";
                return;
            }

            using var req = new HttpRequestMessage(HttpMethod.Get, uri);
            req.Headers.Range = new RangeHeaderValue(resumeOffset, segment.EndByte);
            if (!string.IsNullOrWhiteSpace(ifRange))
                req.Headers.TryAddWithoutValidation("If-Range", ifRange);

            try
            {
                using var resp = await _http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
                if (resp.StatusCode == HttpStatusCode.PartialContent)
                {
                    segment.Status = "DOWNLOADING";
                    await using var stream = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
                    var buffer = new byte[32 * 1024];
                    var writePos = resumeOffset;
                    long bytesSinceEmit = 0;

                    while (true)
                    {
                        // EndByte can shrink while we run (work-stealing): read it once per iteration.
                        var end = segment.EndByte;
                        if (writePos > end) break;
                        var maxRead = (int)Math.Min(buffer.Length, end - writePos + 1L);
                        var read = await stream.ReadAsync(buffer.AsMemory(0, maxRead), ct).ConfigureAwait(false);
                        if (read == 0) break;

                        await _globalLimiter.AcquireAsync(read, ct).ConfigureAwait(false);
                        await itemLimiter.AcquireAsync(read, ct).ConfigureAwait(false);
                        await writer.WriteAtAsync(writePos, buffer.AsMemory(0, read), ct).ConfigureAwait(false);

                        writePos += read;
                        segment.AddDownloaded(read);
                        bytesSinceEmit += read;
                        if (bytesSinceEmit >= DownloadPolicy.CheckpointFlushBytes)
                        {
                            bytesSinceEmit = 0;
                            _eventBus.Publish(new DownloadProgressEvent(downloadId, writePos, totalSize));
                        }
                    }

                    if (segment.CurrentPosition() > segment.EndByte)
                    {
                        segment.Status = "COMPLETED";
                        return;
                    }
                    throw new IOException($"Premature EOF on segment {segment.Index}");
                }

                if (resp.StatusCode == HttpStatusCode.OK)
                    throw new UnauthorizedAccessException("SERVER_FILE_CHANGED_IF_RANGE: Remote file changed.");

                CheckFatalStatus(resp.StatusCode);

                if (resp.StatusCode is HttpStatusCode.TooManyRequests or HttpStatusCode.ServiceUnavailable)
                {
                    var retrySec = (int)(resp.Headers.RetryAfter?.Delta?.TotalSeconds ?? Math.Pow(2, attempt));
                    var delayMs = Math.Clamp(retrySec, 1, 60) * 1000 + Random.Shared.Next(100, 600);
                    await Task.Delay(delayMs, ct).ConfigureAwait(false);
                    continue;
                }
                throw new HttpRequestException($"Unexpected HTTP {(int)resp.StatusCode}");
            }
            catch (OperationCanceledException) { throw; }
            catch (Exception ex) when (ex is UnauthorizedAccessException or FileNotFoundException)
            {
                segment.Status = "FAILED"; // fatal (401/403/404/410/file changed): retrying cannot help
                throw;
            }
            catch
            {
                segment.RetryCount = attempt + 1;
                if (attempt == 5)
                {
                    segment.Status = "FAILED";
                    throw;
                }
                var backoffMs = Math.Min(60_000, 1000 << attempt) + Random.Shared.Next(100, 500);
                await Task.Delay(backoffMs, ct).ConfigureAwait(false);
            }
        }
    }

    private async Task DownloadSingleStreamAsync(
        Uri uri, SparseFileWriter writer, TokenBucketLimiter itemLimiter,
        string downloadId, long? totalSize, CancellationToken ct, Action<long>? onBytes = null)
    {
        using var resp = await _http.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
        CheckFatalStatus(resp.StatusCode);
        resp.EnsureSuccessStatusCode();

        await using var stream = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        var buffer = new byte[32 * 1024];
        long offset = 0;
        while (true)
        {
            var read = await stream.ReadAsync(buffer, ct).ConfigureAwait(false);
            if (read == 0) break;
            await _globalLimiter.AcquireAsync(read, ct).ConfigureAwait(false);
            await itemLimiter.AcquireAsync(read, ct).ConfigureAwait(false);
            await writer.WriteAtAsync(offset, buffer.AsMemory(0, read), ct).ConfigureAwait(false);
            offset += read;
            onBytes?.Invoke(offset);
            _eventBus.Publish(new DownloadProgressEvent(downloadId, offset, totalSize));
        }
    }

    private static List<ActiveSegment> BuildInitialSegments(string downloadId, long fileSize, int count)
    {
        var chunk = fileSize / count;
        var list = new List<ActiveSegment>(count);
        for (var i = 0; i < count; i++)
        {
            var start = i * chunk;
            var end = i == count - 1 ? fileSize - 1L : ((i + 1) * chunk) - 1L;
            list.Add(new ActiveSegment($"{downloadId}_seg_{i}", i, start, end));
        }
        return list;
    }

    /// <summary>
    /// Atomically reserves "name.ryntmp" and returns the matching final path ("name", "name (1)", ...),
    /// so an existing user file or a parallel download is never overwritten.
    /// </summary>
    private static string ClaimUniqueTarget(string directory, string fileName)
    {
        var first = UrlAndPathValidator.ResolveSafeTargetPath(directory, fileName);
        var stem = Path.GetFileNameWithoutExtension(first);
        var ext = Path.GetExtension(first);
        var candidate = first;
        for (var i = 1; i <= 9_999; i++)
        {
            if (!File.Exists(candidate))
            {
                try
                {
                    using var tempClaim = new FileStream(candidate + DownloadPolicy.SparseTempExtension, FileMode.CreateNew, FileAccess.Write, FileShare.None);
                    return candidate;
                }
                catch (IOException)
                {
                    // temp name already taken by a parallel download: try the next suffix
                }
            }
            candidate = UrlAndPathValidator.ResolveSafeTargetPath(directory, $"{stem} ({i}){ext}");
        }
        throw new IOException($"TOO_MANY_DUPLICATE_FILE_NAMES: {fileName}");
    }

    private static async ValueTask<Stream> SsrfSafeConnectAsync(SocketsHttpConnectionContext context, CancellationToken ct)
    {
        var addresses = await Dns.GetHostAddressesAsync(context.DnsEndPoint.Host, ct).ConfigureAwait(false);
        var allowed = addresses.Where(a => !UrlAndPathValidator.IsBlockedAddress(a)).ToArray();
        if (allowed.Length == 0)
            throw new UnauthorizedAccessException("SSRF_BLOCKED: Private or loopback target blocked.");

        var socket = new Socket(SocketType.Stream, ProtocolType.Tcp) { NoDelay = true };
        try
        {
            await socket.ConnectAsync(allowed, context.DnsEndPoint.Port, ct).ConfigureAwait(false);
            return new NetworkStream(socket, ownsSocket: true);
        }
        catch
        {
            socket.Dispose();
            throw;
        }
    }

    private static void CheckFatalStatus(HttpStatusCode code)
    {
        if (code is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden)
            throw new UnauthorizedAccessException($"AUTH_OR_EXPIRED_LINK: HTTP {(int)code}");
        if (code is HttpStatusCode.NotFound or HttpStatusCode.Gone)
            throw new FileNotFoundException($"RESOURCE_NOT_FOUND: HTTP {(int)code}");
    }

    public void Dispose()
    {
        _globalLimiter.Dispose();
        _http.Dispose();
    }
}
