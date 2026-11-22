using System.Collections.ObjectModel;
using System.Security.Cryptography;
using System.Text.RegularExpressions;
using Microsoft.Web.WebView2.Core;
using RynSpeed.Security;

namespace RynSpeed.Browser;

public sealed record BrowserTabModel(string Id, string Title, string Url, bool IsLoading = false);

public sealed record InterceptedBrowserDownload(
    string Url,
    string SuggestedFileName,
    string? MimeType,
    long? TotalBytes,
    bool CanEngineHandle);

public sealed class BrowserTabManager
{
    public ObservableCollection<BrowserTabModel> Tabs { get; } =
    [
        new BrowserTabModel(Guid.NewGuid().ToString("N"), "New Tab", "https://duckduckgo.com")
    ];

    public string ActiveTabId { get; private set; }

    public BrowserTabManager()
    {
        ActiveTabId = Tabs[0].Id;
    }

    public BrowserTabModel OpenTab(string url = "https://duckduckgo.com")
    {
        var tab = new BrowserTabModel(Guid.NewGuid().ToString("N"), "New Tab", url);
        Tabs.Add(tab);
        ActiveTabId = tab.Id;
        return tab;
    }

    public void CloseTab(string tabId)
    {
        var existing = Tabs.FirstOrDefault(t => t.Id == tabId);
        if (existing is null) return;
        Tabs.Remove(existing);
        if (Tabs.Count == 0)
        {
            var fresh = new BrowserTabModel(Guid.NewGuid().ToString("N"), "New Tab", "https://duckduckgo.com");
            Tabs.Add(fresh);
            ActiveTabId = fresh.Id;
        }
        else if (ActiveTabId == tabId)
        {
            ActiveTabId = Tabs[^1].Id;
        }
    }
}

/// <summary>
/// Hardened WebView2 Security & Download Interception Controller:
/// - Dedicated isolated UserDataFolder per app profile
/// - Default-deny sensitive permissions (Camera, Microphone, Geolocation, ClipboardRead)
/// - Never logs passwords or form inputs
/// - Routes eligible HTTP/HTTPS downloads to RynDownloadEngine while preserving native fallback for blob/protected streams
/// </summary>
public static class HardenedWebView2Policy
{
    public static Task<CoreWebView2Environment> CreateIsolatedEnvironmentAsync(string appDataRoot)
    {
        var isolatedProfile = Path.Combine(appDataRoot, "BrowserProfile");
        Directory.CreateDirectory(isolatedProfile);
        return CoreWebView2Environment.CreateAsync(userDataFolder: isolatedProfile);
    }

    public static void ApplySecurityGuards(
        CoreWebView2 coreWebView,
        Func<string, CoreWebView2PermissionKind, bool> onPermissionPrompt,
        Action<InterceptedBrowserDownload> onDownloadDetected)
    {
        coreWebView.Settings.IsPasswordAutosaveEnabled = false;
        coreWebView.Settings.IsGeneralAutofillEnabled = false;
        coreWebView.Settings.AreDevToolsEnabled = false;
        coreWebView.Settings.IsStatusBarEnabled = true;

        coreWebView.NavigationStarting += (_, args) =>
        {
            if (!Uri.TryCreate(args.Uri, UriKind.Absolute, out var uri) ||
                (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps))
            {
                args.Cancel = true;
            }
        };

        coreWebView.PermissionRequested += (_, args) =>
        {
            var approved = onPermissionPrompt(args.Uri, args.PermissionKind);
            args.State = approved ? CoreWebView2PermissionState.Allow : CoreWebView2PermissionState.Deny;
        };

        coreWebView.DownloadStarting += (_, args) =>
        {
            var uri = args.DownloadOperation.Uri ?? string.Empty;
            var canHandle = uri.StartsWith("http://", StringComparison.OrdinalIgnoreCase) ||
                            uri.StartsWith("https://", StringComparison.OrdinalIgnoreCase);

            var suggested = UrlAndPathValidator.SanitizeFileName(
                Path.GetFileName(args.ResultFilePath ?? string.Empty));

            if (canHandle)
            {
                // Hand off to RynDownloadEngine cleanly; cancel default browser download dialog
                args.Cancel = true;
            }

            onDownloadDetected(new InterceptedBrowserDownload(
                Url: uri,
                SuggestedFileName: suggested,
                MimeType: args.DownloadOperation.MimeType,
                TotalBytes: args.DownloadOperation.TotalBytesToReceive,
                CanEngineHandle: canHandle));
        };
    }

    public static async Task WipeAllBrowsingDataAsync(CoreWebView2 coreWebView)
    {
        await coreWebView.Profile.ClearBrowsingDataAsync().ConfigureAwait(false);
    }
}

/// <summary>
/// Safe clipboard URL detector and authenticated local extension bridge contract.
/// Uses a per-session random 256-bit token so malicious websites cannot trigger downloads via CSRF.
/// </summary>
public static partial class ExternalBrowserIntegration
{
    [GeneratedRegex(@"https?://[^\s<>""']+", RegexOptions.IgnoreCase)]
    private static partial Regex UrlRegex();

    public static string GenerateExtensionSessionToken() =>
        Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(32));

    public static async Task<Uri?> TryExtractSafeClipboardUrlAsync(string? clipboardText, CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(clipboardText)) return null;
        var match = UrlRegex().Match(clipboardText);
        if (!match.Success) return null;
        try
        {
            return await UrlAndPathValidator
                .ValidateDownloadUrlAsync(match.Value, allowLocalNetwork: false, ct)
                .ConfigureAwait(false);
        }
        catch
        {
            return null;
        }
    }
}
