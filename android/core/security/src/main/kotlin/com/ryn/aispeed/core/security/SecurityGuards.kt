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

    /**
     * Syntax-only validation (scheme + host). Performs NO network/DNS access, so it is safe to call
     * on the main thread (e.g. when a link is shared into the app).
     */
    fun validateUrlSyntax(rawUrl: String): URI {
        val uri = URI(rawUrl.trim())
        val scheme = uri.scheme?.lowercase()
            ?: throw SecurityException("INVALID_URL_SCHEME: Missing URL scheme")
        if (scheme !in ALLOWED_SCHEMES) {
            throw SecurityException("INVALID_URL_SCHEME: Only HTTP/HTTPS URLs are permitted")
        }
        uri.host?.takeIf { it.isNotBlank() }
            ?: throw SecurityException("INVALID_URL_SCHEME: Missing host")
        return uri
    }

    /** Full validation incl. DNS/SSRF check. Blocking: call only from a background thread. */
    fun validateDownloadUrl(rawUrl: String, allowLocalNetwork: Boolean = false): URI {
        val uri = validateUrlSyntax(rawUrl)
        if (!allowLocalNetwork) {
            for (addr in InetAddress.getAllByName(uri.host)) {
                if (isBlockedAddress(addr)) {
                    throw SecurityException("SSRF_BLOCKED: Private or loopback target blocked")
                }
            }
        }
        return uri
    }

    /** True for loopback, link-local, private (RFC1918), CGNAT, reserved, multicast and IPv6 ULA addresses. */
    fun isBlockedAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress ||
            addr.isLoopbackAddress ||
            addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress ||
            addr.isMulticastAddress
        ) {
            return true
        }
        val b = addr.address
        return when (b.size) {
            4 -> {
                val o0 = b[0].toInt() and 0xFF
                val o1 = b[1].toInt() and 0xFF
                val o2 = b[2].toInt() and 0xFF
                o0 == 0 ||
                    (o0 == 100 && o1 in 64..127) ||
                    (o0 == 192 && o1 == 0 && o2 == 0) ||
                    (o0 == 198 && (o1 == 18 || o1 == 19)) ||
                    o0 >= 240
            }
            16 -> (b[0].toInt() and 0xFE) == 0xFC
            else -> true
        }
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
