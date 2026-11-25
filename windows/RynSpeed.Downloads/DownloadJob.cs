namespace RynSpeed.Downloads;

/// <summary>Persisted state of one segment, so an interrupted download can continue where it stopped.</summary>
public sealed class SegmentState
{
    public string Id { get; set; } = string.Empty;
    public int Index { get; set; }
    public long StartByte { get; set; }
    public long EndByte { get; set; }
    public long DownloadedBytes { get; set; }
}

/// <summary>One download as the user sees it (IDM-style row). Plain data so it can be saved as JSON.</summary>
public sealed class DownloadJob
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Url { get; set; } = string.Empty;
    public string FileName { get; set; } = string.Empty;
    public string Category { get; set; } = "OTHER";
    public string? FinalPath { get; set; }
    public string? TempPath { get; set; }
    public long? FileSize { get; set; }
    public long DownloadedBytes { get; set; }

    /// <summary>QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELED.</summary>
    public string Status { get; set; } = "QUEUED";
    public bool SupportsRanges { get; set; }
    public string? ETag { get; set; }
    public string? LastModified { get; set; }
    public int MaxConnections { get; set; } = DownloadPolicy.DefaultMaxConnections;
    public long SpeedLimitBps { get; set; }
    public string? ExpectedSha256 { get; set; }
    public string? ActualSha256 { get; set; }
    public string? LastError { get; set; }
    public List<SegmentState> Segments { get; set; } = [];
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;
}
