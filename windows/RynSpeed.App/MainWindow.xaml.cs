using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using RynSpeed.AI;
using RynSpeed.Core;
using RynSpeed.Downloads;
using RynSpeed.Network;

namespace RynSpeed.App;

public partial class MainWindow : Window
{
    private readonly ModuleEventBus _eventBus = new();
    private readonly RynDownloadEngine _downloadEngine;
    private readonly DownloadManager _downloads;
    private readonly ObservableCollection<DownloadRow> _rows = [];
    private readonly DispatcherTimer _uiTimer = new() { Interval = TimeSpan.FromMilliseconds(500) };
    private readonly string _downloadDir;
    private string _lastClipboardUrl = string.Empty;
    private readonly RynNetworkOptimizer _networkOptimizer = new();
    private readonly AiAutoManager _aiAutoManager = new(initialEnabled: true);
    private LatencyReport? _lastLatency;

    public MainWindow()
    {
        InitializeComponent();
        var dlDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            "Downloads",
            "RYN-AI-Speed");
        Directory.CreateDirectory(dlDir);
        _downloadDir = dlDir;
        _downloadEngine = new RynDownloadEngine(_eventBus, dlDir);
        var storePath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "RYN-AI-Speed",
            "downloads.bin");
        _downloads = new DownloadManager(_downloadEngine, storePath);
        DownloadsGrid.ItemsSource = _rows;
        SpeedLimitCombo.SelectionChanged += SpeedLimitCombo_Changed;
        CategoryList.SelectionChanged += (_, _) => RefreshRows();
        Activated += Window_Activated;
        _uiTimer.Tick += (_, _) => RefreshRows();
        _uiTimer.Start();
        RefreshRows();
        AiChatList.Items.Add("RYN AI: أهلاً بك! اكتب «افحص الإنترنت» أو «ليش التنزيل بطيء؟» للتشخيص المحلي الفوري.");
    }

    private void AutoManagerToggle_Changed(object sender, RoutedEventArgs e)
    {
        var enabled = AutoManagerToggle.IsChecked == true;
        _aiAutoManager.SetMasterEnabled(enabled);
        if (StatusBannerText is not null)
        {
            StatusBannerText.Text = enabled
                ? "المدير التلقائي الذكي مفعّل"
                : "تم إيقاف المدير التلقائي الذكي فوراً (Master Switch OFF)";
        }
    }

    private void AddBtn_Click(object sender, RoutedEventArgs e) => AddFromBox();

    private void DownloadUrlBox_KeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        if (e.Key == System.Windows.Input.Key.Enter) AddFromBox();
    }

    private void AddFromBox()
    {
        var url = DownloadUrlBox.Text.Trim();
        if (string.IsNullOrWhiteSpace(url) || url == "https://") return;
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps))
        {
            StatusBannerText.Text = "الرابط غير صالح (يجب أن يبدأ بـ http أو https)";
            return;
        }

        _downloads.Add(url, speedLimitBps: 0L);
        DownloadUrlBox.Text = "https://";
        StatusBannerText.Text = "تمت إضافة التنزيل";
        RefreshRows();
    }

    private DownloadRow? SelectedRow() => DownloadsGrid.SelectedItem as DownloadRow;

    private void PauseBtn_Click(object sender, RoutedEventArgs e)
    {
        if (SelectedRow() is { } row) _downloads.Pause(row.Id);
    }

    private void ResumeBtn_Click(object sender, RoutedEventArgs e)
    {
        if (SelectedRow() is { } row) _downloads.Resume(row.Id);
    }

    private void CancelBtn_Click(object sender, RoutedEventArgs e)
    {
        if (SelectedRow() is { } row) _downloads.Cancel(row.Id);
    }

    private void RemoveBtn_Click(object sender, RoutedEventArgs e)
    {
        if (SelectedRow() is not { } row) return;
        var deleteFile = false;
        if (row.RawStatus == "COMPLETED")
        {
            var answer = MessageBox.Show(
                "حذف الملف من القرص أيضاً؟\n(نعم = احذف الملف، لا = احذفه من القائمة فقط)",
                "حذف",
                MessageBoxButton.YesNoCancel,
                MessageBoxImage.Question);
            if (answer == MessageBoxResult.Cancel) return;
            deleteFile = answer == MessageBoxResult.Yes;
        }
        _downloads.Remove(row.Id, deleteFile);
        RefreshRows();
    }

    private void OpenFolderBtn_Click(object sender, RoutedEventArgs e)
    {
        var path = SelectedRow()?.FinalPath;
        if (!string.IsNullOrEmpty(path) && File.Exists(path))
            Process.Start(new ProcessStartInfo("explorer.exe", $"/select,\"{path}\"") { UseShellExecute = true });
        else
            Process.Start(new ProcessStartInfo(_downloadDir) { UseShellExecute = true });
    }

    private void SpeedLimitCombo_Changed(object sender, SelectionChangedEventArgs e)
    {
        long[] limits = [0L, 512L * 1024L, 1024L * 1024L, 5L * 1024L * 1024L, 10L * 1024L * 1024L];
        var index = SpeedLimitCombo.SelectedIndex;
        _downloads.SetGlobalSpeedLimit(index >= 0 && index < limits.Length ? limits[index] : 0L);
    }

    private void Window_Activated(object? sender, EventArgs e)
    {
        // IDM-style: pick up a copied link automatically.
        try
        {
            if (!Clipboard.ContainsText()) return;
            var text = Clipboard.GetText().Trim();
            var boxIsEmpty = string.IsNullOrEmpty(DownloadUrlBox.Text) || DownloadUrlBox.Text == "https://";
            if (boxIsEmpty && text != _lastClipboardUrl && !text.Contains(' ', StringComparison.Ordinal) &&
                (text.StartsWith("http://", StringComparison.OrdinalIgnoreCase) || text.StartsWith("https://", StringComparison.OrdinalIgnoreCase)))
            {
                _lastClipboardUrl = text;
                DownloadUrlBox.Text = text;
                StatusBannerText.Text = "تم التقاط رابط من الحافظة. اضغط إضافة";
            }
        }
        catch (System.Runtime.InteropServices.ExternalException)
        {
            // the clipboard is briefly locked by another app; try again on the next activation
        }
    }

    private static bool Passes(string filter, DownloadJob job) => filter switch
    {
        "ALL" => true,
        "ACTIVE" => job.Status is "DOWNLOADING" or "QUEUED",
        "DONE" => job.Status == "COMPLETED",
        "STOPPED" => job.Status is "PAUSED" or "FAILED" or "CANCELED",
        _ => job.Category == filter
    };

    private void RefreshRows()
    {
        var jobs = _downloads.Snapshot();
        var filter = (CategoryList.SelectedItem as ListBoxItem)?.Tag as string ?? "ALL";
        var visible = jobs.Where(j => Passes(filter, j)).ToList();

        for (var i = _rows.Count - 1; i >= 0; i--)
        {
            var id = _rows[i].Id;
            if (!visible.Any(j => j.Id == id)) _rows.RemoveAt(i);
        }

        foreach (var job in visible)
        {
            var row = _rows.FirstOrDefault(r => r.Id == job.Id);
            if (row is null)
            {
                row = new DownloadRow(job.Id);
                _rows.Insert(0, row);
            }
            row.Update(job);
        }

        var active = jobs.Count(j => j.Status is "DOWNLOADING" or "QUEUED");
        var done = jobs.Count(j => j.Status == "COMPLETED");
        var totalSpeed = _rows.Sum(r => r.SpeedBps);
        DownloadsStatusText.Text = jobs.Count == 0
            ? "لا توجد تنزيلات بعد. الصق رابطاً واضغط إضافة."
            : $"{active} قيد التنزيل  •  {done} مكتمل  •  السرعة الكلية: {Fmt.Bytes(totalSpeed)}/s";
    }

    private async void RunNetTestBtn_Click(object sender, RoutedEventArgs e)
    {
        RunNetTestBtn.IsEnabled = false;
        NetworkReportBox.Text = "جاري تشغيل قياسات TCP Ping و Jitter ومقارنة خوادم DNS فعلياً...";
        try
        {
            var sb = new StringBuilder();
            var pingRes = await RynNetworkOptimizer.MeasureTcpLatencyAsync("1.1.1.1", 443, 5);
            if (pingRes.IsSuccess && pingRes.Value is not null)
            {
                _lastLatency = pingRes.Value;
                sb.AppendLine($"الهدف: {pingRes.Value.Host}:{pingRes.Value.Port}");
                sb.AppendLine($"متوسط Ping: {pingRes.Value.AvgPingMs:F1} ms");
                sb.AppendLine($"تذبذب Jitter: {pingRes.Value.JitterMs:F1} ms");
                sb.AppendLine($"فقدان الحزم: {pingRes.Value.PacketLossPct:F1}%");
                sb.AppendLine();
            }

            var dnsRes = await _networkOptimizer.BenchmarkDnsResolversAsync();
            if (dnsRes.IsSuccess && dnsRes.Value is not null)
            {
                sb.AppendLine("مقارنة خوادم DNS الموثّقة (الأسرع أولاً):");
                foreach (var d in dnsRes.Value)
                {
                    var ms = d.Reachable && d.AvgLatencyMs.HasValue ? $"{d.AvgLatencyMs.Value:F1} ms" : "غير متاح";
                    sb.AppendLine($"- {d.Name} ({d.ResolverIp}): {ms}");
                }
            }
            NetworkReportBox.Text = sb.ToString();
        }
        finally
        {
            RunNetTestBtn.IsEnabled = true;
        }
    }

    private void AiSendBtn_Click(object sender, RoutedEventArgs e)
    {
        var q = AiInputBox.Text.Trim();
        if (string.IsNullOrWhiteSpace(q)) return;

        AiChatList.Items.Add($"أنت: {q}");
        var diag = LocalAiRulesEngine.DiagnoseNetworkOffline(
            _lastLatency?.AvgPingMs,
            _lastLatency?.JitterMs,
            _lastLatency?.PacketLossPct ?? 0.0);
        AiChatList.Items.Add($"RYN AI: {diag.TitleAr} — التوصية: {diag.RecommendedConnections} اتصالات تنزيل. ({diag.SuggestedAction})");
        AiInputBox.Clear();
    }

    protected override void OnClosed(EventArgs e)
    {
        _uiTimer.Stop();
        _downloads.Dispose(); // pauses running downloads and writes their final checkpoint
        _downloadEngine.Dispose();
        _networkOptimizer.Dispose();
        base.OnClosed(e);
    }
}
