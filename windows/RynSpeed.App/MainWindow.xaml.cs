using System.IO;
using System.Text;
using System.Windows;
using RynSpeed.AI;
using RynSpeed.Core;
using RynSpeed.Downloads;
using RynSpeed.Network;

namespace RynSpeed.App;

public partial class MainWindow : Window
{
    private readonly ModuleEventBus _eventBus = new();
    private readonly RynDownloadEngine _downloadEngine;
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
        _downloadEngine = new RynDownloadEngine(_eventBus, dlDir);
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

    private async void DownloadBtn_Click(object sender, RoutedEventArgs e)
    {
        var url = DownloadUrlBox.Text.Trim();
        if (string.IsNullOrWhiteSpace(url) || url == "https://") return;

        DownloadBtn.IsEnabled = false;
        DownloadsLogList.Items.Add($"[بدء] جاري فحص وتنزيل: {url}");
        StatusBannerText.Text = "جاري التنزيل متعدد الاتصالات...";
        try
        {
            var res = await _downloadEngine.DownloadAsync(url);
            if (res.IsSuccess)
            {
                DownloadsLogList.Items.Add($"[مكتمل] {res.Value.FilePath} (SHA-256: {res.Value.Sha256[..12]}...)");
                StatusBannerText.Text = "اكتمل التنزيل وتم التحقق من SHA-256 بنجاح";
            }
            else
            {
                DownloadsLogList.Items.Add($"[خطأ] {res.ErrorCode}: {res.ErrorMessage}");
                StatusBannerText.Text = $"فشل التنزيل: {res.ErrorCode}";
            }
        }
        finally
        {
            DownloadBtn.IsEnabled = true;
        }
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
        _downloadEngine.Dispose();
        _networkOptimizer.Dispose();
        base.OnClosed(e);
    }
}
