using System.ComponentModel;
using System.Globalization;
using RynSpeed.Downloads;

namespace RynSpeed.App;

/// <summary>One row of the IDM-style grid: turns a <see cref="DownloadJob"/> into display text.</summary>
public sealed class DownloadRow(string id) : INotifyPropertyChanged
{
    private long _lastChangeBytes;
    private long _lastChangeTicks;
    private double _speed;

    public string Id { get; } = id;
    public string Name { get; private set; } = string.Empty;
    public string SizeText { get; private set; } = "—";
    public double Progress { get; private set; }
    public string ProgressText { get; private set; } = "0%";
    public string SpeedText { get; private set; } = string.Empty;
    public string EtaText { get; private set; } = string.Empty;
    public string StatusText { get; private set; } = string.Empty;
    public string RawStatus { get; private set; } = string.Empty;
    public string? FinalPath { get; private set; }
    public double SpeedBps => _speed;

    public event PropertyChangedEventHandler? PropertyChanged;

    public void Update(DownloadJob job)
    {
        var now = Environment.TickCount64;
        var bytes = job.DownloadedBytes;

        // Ranged downloads publish progress about once per second: measure speed between real changes.
        if (job.Status == "DOWNLOADING")
        {
            if (_lastChangeTicks == 0)
            {
                _lastChangeBytes = bytes;
                _lastChangeTicks = now;
            }
            else if (bytes != _lastChangeBytes)
            {
                var dt = now - _lastChangeTicks;
                if (dt > 0 && bytes > _lastChangeBytes)
                {
                    var sample = (bytes - _lastChangeBytes) * 1000.0 / dt;
                    _speed = _speed <= 0 ? sample : (_speed * 0.5) + (sample * 0.5);
                }
                _lastChangeBytes = bytes;
                _lastChangeTicks = now;
            }
            else if (now - _lastChangeTicks > 4000)
            {
                _speed = 0;
            }
        }
        else
        {
            _speed = 0;
            _lastChangeTicks = 0;
        }

        Name = string.IsNullOrWhiteSpace(job.FileName) ? job.Url : job.FileName;
        RawStatus = job.Status;
        FinalPath = job.FinalPath;
        SizeText = job.FileSize is { } total ? Fmt.Bytes(total) : "—";

        double percent = 0;
        if (job.Status == "COMPLETED") percent = 100;
        else if (job.FileSize is { } size && size > 0) percent = Math.Min(100.0, bytes * 100.0 / size);
        Progress = percent;
        ProgressText = percent.ToString("F0", CultureInfo.InvariantCulture) + "%";

        SpeedText = job.Status == "DOWNLOADING" && _speed > 0 ? Fmt.Bytes(_speed) + "/s" : string.Empty;
        EtaText = string.Empty;
        if (job.Status == "DOWNLOADING" && _speed > 0 && job.FileSize is { } fileSize && fileSize > bytes)
            EtaText = Fmt.Eta((fileSize - bytes) / _speed);

        StatusText = job.Status switch
        {
            "QUEUED" => "في الانتظار",
            "DOWNLOADING" => "جاري التنزيل",
            "PAUSED" => "متوقف مؤقتاً",
            "COMPLETED" => "مكتمل",
            "FAILED" => string.IsNullOrWhiteSpace(job.LastError) ? "فشل" : "فشل: " + job.LastError,
            "CANCELED" => "ملغي",
            _ => job.Status
        };

        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(string.Empty));
    }
}

internal static class Fmt
{
    public static string Bytes(double value)
    {
        string[] units = ["B", "KB", "MB", "GB", "TB"];
        var i = 0;
        while (value >= 1024 && i < units.Length - 1)
        {
            value /= 1024;
            i++;
        }
        return value.ToString(i == 0 ? "F0" : "F1", CultureInfo.InvariantCulture) + " " + units[i];
    }

    public static string Eta(double seconds)
    {
        if (double.IsNaN(seconds) || double.IsInfinity(seconds) || seconds > 99 * 3600) return "—";
        var ts = TimeSpan.FromSeconds(seconds);
        return string.Create(CultureInfo.InvariantCulture, $"{(int)ts.TotalHours:D2}:{ts.Minutes:D2}:{ts.Seconds:D2}");
    }
}
