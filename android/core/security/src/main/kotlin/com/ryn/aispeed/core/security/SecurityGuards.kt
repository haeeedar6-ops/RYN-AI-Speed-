package com.ryn.aispeed.core.security

import java.io.File
import java.net.InetAddress
import java.net.URI

/**
 * Security guards against SSRF, path traversal, Unicode BiDi spoofing, and secret leakage in logs.
 */
object UrlAndPathValidator {
    private val ALLOWED_SCHEMES = setOf("http", "https")
    private val WINDOWS_RESERVED = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )
    private val BIDI_AND_CONTROL = Regex("[\\u0000-\\u001F\\u007F\\u202A-\\u202E\\u2066-\\u2069]")

    fun validateDownloadUrl(rawUrl: String, allowLocalNetwork: Boolean = false): URI {
        val uri = URI(rawUrl.trim())
        val scheme = uri.scheme?.lowercase()
            ?: throw SecurityException("INVALID_URL_SCHEME: Missing URL scheme")
        require(scheme in ALLOWED_SCHEMES) {
            "INVALID_URL_SCHEME: Only HTTP/HTTPS URLs are permitted"
        }
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: throw SecurityException("INVALID_URL_SCHEME: Missing host")

        if (!allowLocalNetwork) {
            val addresses = InetAddress.getAllByName(host)
            for (addr in addresses) {
                if (addr.isAnyLocalAddress ||
                    addr.isLoopbackAddress ||
                    addr.isLinkLocalAddress ||
                    addr.isSiteLocalAddress ||
                    addr.isMulticastAddress
                ) {
                    throw SecurityException("SSRF_BLOCKED: Private or loopback target blocked")
                }
            }
        }
        return uri
    }

    fun sanitizeFileName(rawName: String, fallback: String = "download.bin"): String {
        val cleaned = rawName
            .replace('\\', '/')
            .substringAfterLast('/')
            .replace(BIDI_AND_CONTROL, "")
            .replace(Regex("[<>:\"|?*]"), "_")
            .trim()
            .trimStart('.')

        if (cleaned.isBlank()) return fallback
        val stem = cleaned.substringBefore('.').uppercase()
        return if (stem in WINDOWS_RESERVED) "_$cleaned" else cleaned.take(180)
    }

    fun resolveSafeTargetFile(rootDir: File, rawFileName: String): File {
        val safeName = sanitizeFileName(rawFileName)
        val canonicalRoot = rootDir.canonicalFile
        val target = File(canonicalRoot, safeName).canonicalFile
        val rootPath = canonicalRoot.path.trimEnd(File.separatorChar) + File.separatorChar
        if (!target.path.startsWith(rootPath)) {
            throw SecurityException("UNSAFE_FILENAME: Path traversal attempt blocked")
        }
        return target
    }
}

object LogRedactor {
    private val JWT = Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
    private val BEARER = Regex("(?i)(Bearer\\s+)[A-Za-z0-9._\\-~+/]+=*")
    private val QS = Regex("(?i)([?&](?:token|key|secret|sig|password|auth|api_key)=)[^&\\s]+")
    private val WG_KEY = Regex("(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{43}=(?![A-Za-z0-9+/=])")

    fun redact(input: String): String = input
        .replace(JWT, "[REDACTED_JWT]")
        .replace(BEARER, "$1[REDACTED_TOKEN]")
        .replace(QS, "$1[REDACTED]")
        .replace(WG_KEY, "[REDACTED_KEY]")
}
