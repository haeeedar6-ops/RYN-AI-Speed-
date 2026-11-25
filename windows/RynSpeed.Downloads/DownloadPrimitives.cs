using System.Security.Cryptography;
using Microsoft.Win32.SafeHandles;
using RynSpeed.Security;

namespace RynSpeed.Downloads;

public static class DownloadPolicy
{
    public const long MinRangedFileBytes = 2L * 1024L * 1024L;
    public const long WorkStealingMinRemainingBytes = 4L * 1024L * 1024L;
    public const int DefaultMaxConnections = 8;
    public const int HardMaxConnections = 16;
    public const long CheckpointFlushBytes = 512L * 1024L;
    public const long DiskSafetyBufferBytes = 50L * 1024L * 1024L;
    public const string SparseTempExtension = ".ryntmp";

    public static int ComputeOptimalConnections(long? fileSize, bool supportsRanges, int userCap)
    {
        if (!supportsRanges || fileSize is null || fileSize.Value < MinRangedFileBytes)
            return 1;

        var cap = Math.Clamp(userCap, 1, HardMaxConnections);
        var recommended = fileSize.Value switch
        {
            < 20L * 1024L * 1024L => 2,
            < 100L * 1024L * 1024L => 4,
            < 500L * 1024L * 1024L => 6,
            _ => DefaultMaxConnections
        };
        return Math.Min(recommended, cap);
    }

    public static string ClassifyCategory(string fileName, string? mimeType)
    {
        var ext = Path.GetExtension(fileName).TrimStart('.').ToLowerInvariant();
        var mime = (mimeType ?? string.Empty).ToLowerInvariant();
        if (mime.StartsWith("video/", StringComparison.Ordinal) || ext is "mp4" or "mkv" or "avi" or "mov" or "webm") return "VIDEO";
        if (mime.StartsWith("audio/", StringComparison.Ordinal) || ext is "mp3" or "flac" or "wav" or "aac" or "ogg") return "AUDIO";
        if (mime.StartsWith("image/", StringComparison.Ordinal) || ext is "jpg" or "jpeg" or "png" or "webp" or "gif") return "IMAGES";
        if (ext is "zip" or "rar" or "7z" or "tar" or "gz" or "xz") return "ARCHIVES";
        if (ext is "exe" or "msi" or "msix" or "apk" or "iso") return "SOFTWARE";
        if (ext is "pdf" or "doc" or "docx" or "xls" or "xlsx" or "txt" or "csv") return "DOCUMENTS";
        return "OTHER";
    }
}

public sealed record ProbeResult(
    Uri FinalUri,
    string FileName,
    string Category,
    string? MimeType,
    long? FileSize,
    bool SupportsRanges,
    string? ETag,
    string? LastModified);

public sealed class ActiveSegment(string id, int index, long startByte, long endByte)
{
    private long _downloadedBytes;
    private long _endByte = endByte;

    public string Id { get; } = id;
    public int Index { get; } = index;
    public long StartByte { get; } = startByte;
    public long EndByte
    {
        get => Interlocked.Read(ref _endByte);
        set => Interlocked.Exchange(ref _endByte, value);
    }
    public long DownloadedBytes => Interlocked.Read(ref _downloadedBytes);
    public string Status { get; set; } = "PENDING";
    public int RetryCount { get; set; }

    public void AddDownloaded(long delta) => Interlocked.Add(ref _downloadedBytes, delta);
    public long CurrentPosition() => StartByte + DownloadedBytes;
    public long RemainingBytes() => Math.Max(0L, EndByte - CurrentPosition() + 1L);
}

public sealed class SegmentCoordinator
{
    private readonly string _downloadId;
    private readonly List<ActiveSegment> _segments;
    private readonly Lock _sync = new();
    private int _nextIndex;

    public SegmentCoordinator(string downloadId, IEnumerable<ActiveSegment> initialSegments)
    {
        _downloadId = downloadId;
        _segments = [.. initialSegments];
        _nextIndex = _segments.Count;
    }

    /// <summary>Consistent copy of all segments (used for checkpoints).</summary>
    public List<ActiveSegment> Snapshot()
    {
        lock (_sync)
        {
            return [.. _segments];
        }
    }

    public ActiveSegment? StealWorkOrNull()
    {
        lock (_sync)
        {
            var candidate = _segments
                .Where(s => s.Status == "DOWNLOADING" && s.RemainingBytes() >= DownloadPolicy.WorkStealingMinRemainingBytes)
                .OrderByDescending(s => s.RemainingBytes())
                .FirstOrDefault();

            if (candidate is null) return null;

            var currentPos = candidate.CurrentPosition();
            var oldEnd = candidate.EndByte;
            var remaining = oldEnd - currentPos + 1L;
            if (remaining < DownloadPolicy.WorkStealingMinRemainingBytes) return null;

            var mid = currentPos + (remaining / 2L);
            candidate.EndByte = mid;

            var stolen = new ActiveSegment($"{_downloadId}_seg_{_nextIndex}", _nextIndex++, mid + 1L, oldEnd);
            _segments.Add(stolen);
            return stolen;
        }
    }
}

public sealed class TokenBucketLimiter(long bytesPerSecond = 0L) : IDisposable
{
    private readonly SemaphoreSlim _gate = new(1, 1);
    private double _availableTokens;
    private long _lastTicks = Environment.TickCount64;
    private long _rate = bytesPerSecond;

    public long BytesPerSecond
    {
        get => Interlocked.Read(ref _rate);
        set => Interlocked.Exchange(ref _rate, Math.Max(0L, value));
    }

    public async ValueTask AcquireAsync(int bytes, CancellationToken ct)
    {
        var rate = BytesPerSecond;
        if (rate <= 0L || bytes <= 0) return;

        int delayMs = 0;
        await _gate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            var now = Environment.TickCount64;
            var elapsedSec = (now - _lastTicks) / 1000.0;
            _lastTicks = now;
            _availableTokens = Math.Min(rate, _availableTokens + (elapsedSec * rate));
            _availableTokens -= bytes;
            if (_availableTokens < 0.0)
                delayMs = Math.Max(1, (int)Math.Ceiling((-_availableTokens / rate) * 1000.0));
        }
        finally
        {
            _gate.Release();
        }

        if (delayMs > 0)
            await Task.Delay(delayMs, ct).ConfigureAwait(false);
    }

    public void Dispose() => _gate.Dispose();
}

/// <summary>
/// Lock-free positional writer using .NET RandomAccess.Write, SHA-256 verification,
/// atomic rename, and Windows Mark-of-the-Web (Zone.Identifier) tagging.
/// </summary>
public sealed class SparseFileWriter : IDisposable
{
    private readonly string _tempPath;
    private readonly string _finalPath;
    private SafeFileHandle? _handle;

    public SparseFileWriter(string tempPath, string finalPath)
    {
        _tempPath = tempPath;
        _finalPath = finalPath;
        var dir = Path.GetDirectoryName(tempPath);
        if (!string.IsNullOrWhiteSpace(dir)) Directory.CreateDirectory(dir);
        _handle = File.OpenHandle(tempPath, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.ReadWrite, FileOptions.Asynchronous);
    }

    public void Preallocate(long totalBytes)
    {
        // Resuming: the file already has its full length, no new space is needed.
        if (RandomAccess.GetLength(_handle!) == totalBytes) return;

        var root = Path.GetPathRoot(_tempPath) ?? _tempPath;
        var drive = new DriveInfo(root);
        if (drive.AvailableFreeSpace < totalBytes + DownloadPolicy.DiskSafetyBufferBytes)
            throw new IOException($"INSUFFICIENT_DISK_SPACE: Need {totalBytes + DownloadPolicy.DiskSafetyBufferBytes}, free {drive.AvailableFreeSpace}");

        RandomAccess.SetLength(_handle!, totalBytes);
    }

    /// <summary>Pushes written data to disk so a checkpoint never claims more than the file really holds.</summary>
    public void FlushToDisk()
    {
        if (_handle is not null) RandomAccess.FlushToDisk(_handle);
    }

    public ValueTask WriteAtAsync(long offset, ReadOnlyMemory<byte> buffer, CancellationToken ct) =>
        RandomAccess.WriteAsync(_handle!, buffer, offset, ct);

    public async Task<string> FinalizeAtomicallyAsync(long? expectedSize, string? expectedSha256, CancellationToken ct)
    {
        if (_handle is not null)
        {
            RandomAccess.FlushToDisk(_handle);
            _handle.Dispose();
            _handle = null;
        }

        var actualLength = new FileInfo(_tempPath).Length;
        if (expectedSize.HasValue && actualLength != expectedSize.Value)
            throw new IOException($"IO_WRITE_ERROR: Size mismatch ({actualLength} != {expectedSize.Value})");

        string actualSha256;
        await using (var fs = File.OpenRead(_tempPath))
        {
            var hash = await SHA256.HashDataAsync(fs, ct).ConfigureAwait(false);
            actualSha256 = Convert.ToHexStringLower(hash);
        }

        if (!string.IsNullOrWhiteSpace(expectedSha256) &&
            !actualSha256.Equals(expectedSha256, StringComparison.OrdinalIgnoreCase))
        {
            throw new CryptographicException($"CHECKSUM_MISMATCH: Expected {expectedSha256}, got {actualSha256}");
        }

        var parentDir = Path.GetDirectoryName(_finalPath)
            ?? throw new UnauthorizedAccessException("UNSAFE_FILENAME: Missing parent directory.");
        var safeFinal = UrlAndPathValidator.ResolveSafeTargetPath(parentDir, Path.GetFileName(_finalPath));

        File.Move(_tempPath, safeFinal, overwrite: true);
        ApplyMarkOfTheWeb(safeFinal);
        return actualSha256;
    }

    private static void ApplyMarkOfTheWeb(string filePath)
    {
        try
        {
            File.WriteAllText($"{filePath}:Zone.Identifier", "[ZoneTransfer]\r\nZoneId=3\r\n");
        }
        catch
        {
            // Non-NTFS filesystems (e.g., exFAT/FAT32) do not support Alternate Data Streams.
        }
    }

    public void Dispose()
    {
        _handle?.Dispose();
        _handle = null;
    }
}
