using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text.RegularExpressions;

namespace RynSpeed.Security;

public static partial class UrlAndPathValidator
{
    private static readonly HashSet<string> WindowsReservedNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    };

    [GeneratedRegex(@"[\u0000-\u001F\u007F\u202A-\u202E\u2066-\u2069]")]
    private static partial Regex ControlAndBiDiRegex();

    [GeneratedRegex(@"[<>:""/\\|?*]")]
    private static partial Regex InvalidCharsRegex();

    public static async Task<Uri> ValidateDownloadUrlAsync(
        string rawUrl,
        bool allowLocalNetwork = false,
        CancellationToken ct = default)
    {
        if (!Uri.TryCreate(rawUrl.Trim(), UriKind.Absolute, out var uri) ||
            (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps) ||
            string.IsNullOrWhiteSpace(uri.Host))
        {
            throw new UnauthorizedAccessException("INVALID_URL_SCHEME: Only HTTP/HTTPS URLs are permitted.");
        }

        if (!allowLocalNetwork)
        {
            var addresses = await Dns.GetHostAddressesAsync(uri.Host, ct).ConfigureAwait(false);
            foreach (var ip in addresses)
            {
                if (IsPrivateOrLoopback(ip))
                {
                    throw new UnauthorizedAccessException("SSRF_BLOCKED: Private or loopback target blocked.");
                }
            }
        }
        return uri;
    }

    private static bool IsPrivateOrLoopback(IPAddress ip)
    {
        if (IPAddress.IsLoopback(ip) || ip.IsIPv6LinkLocal || ip.IsIPv6SiteLocal || ip.IsIPv6Multicast)
            return true;

        var bytes = ip.AddressFamily == AddressFamily.InterNetworkV6 && ip.IsIPv4MappedToIPv6
            ? ip.MapToIPv4().GetAddressBytes()
            : ip.GetAddressBytes();

        if (bytes.Length == 4)
        {
            return bytes[0] == 10 ||
                   bytes[0] == 127 ||
                   bytes[0] == 0 ||
                   (bytes[0] == 169 && bytes[1] == 254) ||
                   (bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31) ||
                   (bytes[0] == 192 && bytes[1] == 168);
        }
        return false;
    }

    public static string SanitizeFileName(string rawName, string fallback = "download.bin")
    {
        var leaf = rawName.Replace('\\', '/').Split('/').LastOrDefault() ?? string.Empty;
        var cleaned = InvalidCharsRegex().Replace(ControlAndBiDiRegex().Replace(leaf, ""), "_").Trim().TrimStart('.');
        if (string.IsNullOrWhiteSpace(cleaned))
            return fallback;

        var stem = Path.GetFileNameWithoutExtension(cleaned);
        if (WindowsReservedNames.Contains(stem))
            cleaned = "_" + cleaned;

        return cleaned.Length > 180 ? cleaned[..180] : cleaned;
    }

    public static string ResolveSafeTargetPath(string rootDirectory, string rawFileName)
    {
        var safeName = SanitizeFileName(rawFileName);
        var fullRoot = Path.GetFullPath(rootDirectory).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        var target = Path.GetFullPath(Path.Combine(fullRoot, safeName));
        if (!target.StartsWith(fullRoot, StringComparison.OrdinalIgnoreCase))
        {
            throw new UnauthorizedAccessException("UNSAFE_FILENAME: Path traversal attempt blocked.");
        }
        return target;
    }
}

public static partial class LogRedactor
{
    [GeneratedRegex(@"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+")]
    private static partial Regex JwtRegex();

    [GeneratedRegex(@"(?i)(Bearer\s+)[A-Za-z0-9._\-~+/]+=*")]
    private static partial Regex BearerRegex();

    [GeneratedRegex(@"(?i)([?&](?:token|key|secret|sig|password|auth|api_key)=)[^&\s]+")]
    private static partial Regex SensitiveQueryRegex();

    public static string Redact(string input)
    {
        var step1 = JwtRegex().Replace(input, "[REDACTED_JWT]");
        var step2 = BearerRegex().Replace(step1, "$1[REDACTED_TOKEN]");
        return SensitiveQueryRegex().Replace(step2, "$1[REDACTED]");
    }
}

/// <summary>
/// Seals the 256-bit SQLCipher database passphrase using Windows DPAPI (CurrentUser scope).
/// </summary>
public sealed class DpapiSecretStore(string vaultDirectory)
{
    private readonly string _keyFilePath = Path.Combine(vaultDirectory, "ryn_db_key.dpapi");

    public string GetOrCreateDatabasePassphraseHex()
    {
        Directory.CreateDirectory(vaultDirectory);
        if (File.Exists(_keyFilePath))
        {
            var protectedBytes = File.ReadAllBytes(_keyFilePath);
            var raw = ProtectedData.Unprotect(protectedBytes, null, DataProtectionScope.CurrentUser);
            return Convert.ToHexString(raw);
        }

        var freshKey = RandomNumberGenerator.GetBytes(32);
        var sealedKey = ProtectedData.Protect(freshKey, null, DataProtectionScope.CurrentUser);
        File.WriteAllBytes(_keyFilePath, sealedKey);
        return Convert.ToHexString(freshKey);
    }
}
