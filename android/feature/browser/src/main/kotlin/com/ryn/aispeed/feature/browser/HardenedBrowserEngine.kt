package com.ryn.aispeed.feature.browser

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ryn.aispeed.core.security.UrlAndPathValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Tab",
    val url: String = "https://duckduckgo.com",
    val isLoading: Boolean = false
)

data class InterceptedDownload(
    val url: String,
    val suggestedFileName: String,
    val mimeType: String?,
    val contentLength: Long,
    val canEngineHandle: Boolean
)

/**
 * Multi-tab state holder for the Built-in Browser.
 */
class BrowserTabManager {
    private val _tabs = MutableStateFlow(listOf(BrowserTab()))
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow(_tabs.value.first().id)
    val activeTabId: StateFlow<String> = _activeTabId.asStateFlow()

    fun openTab(url: String = "https://duckduckgo.com"): BrowserTab {
        val tab = BrowserTab(url = url)
        _tabs.value = _tabs.value + tab
        _activeTabId.value = tab.id
        return tab
    }

    fun closeTab(tabId: String) {
        val remaining = _tabs.value.filterNot { it.id == tabId }
        if (remaining.isEmpty()) {
            val fresh = BrowserTab()
            _tabs.value = listOf(fresh)
            _activeTabId.value = fresh.id
        } else {
            _tabs.value = remaining
            if (_activeTabId.value == tabId) _activeTabId.value = remaining.last().id
        }
    }

    fun selectTab(tabId: String) {
        if (_tabs.value.any { it.id == tabId }) _activeTabId.value = tabId
    }

    fun updateTab(tabId: String, url: String? = null, title: String? = null, isLoading: Boolean? = null) {
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id != tabId) tab
            else tab.copy(
                url = url ?: tab.url,
                title = title ?: tab.title,
                isLoading = isLoading ?: tab.isLoading
            )
        }
    }
}

/**
 * Hardened Android WebView configurator:
 * - Disables all file:// and content:// access to prevent local file exfiltration
 * - Enforces Safe Browsing and HTTPS-only mixed-content policy
 * - Default-denies Camera/Microphone/Location unless explicitly approved by user
 * - Routes eligible HTTP/HTTPS downloads to RynDownloadEngine without intercepting passwords/sessions
 */
object HardenedWebViewConfigurator {
    private val ALLOWED_NAV_SCHEMES = setOf("http", "https")

    @SuppressLint("SetJavaScriptEnabled")
    fun applySecuritySettings(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            safeBrowsingEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            saveFormData = false
            @Suppress("DEPRECATION")
            savePassword = false
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
    }

    fun createHardenedWebViewClient(
        onPageChanged: (url: String, loading: Boolean) -> Unit,
        onBlockedDangerousUrl: (url: String) -> Unit
    ): WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val uri = request?.url ?: return true
            val scheme = uri.scheme?.lowercase()
            if (scheme !in ALLOWED_NAV_SCHEMES) {
                onBlockedDangerousUrl(uri.toString())
                return true
            }
            return false
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (!url.isNullOrBlank()) onPageChanged(url, false)
        }
    }

    fun createHardenedChromeClient(
        onPermissionPrompt: (origin: String, resources: Array<String>, decision: (Boolean) -> Unit) -> Unit
    ): WebChromeClient = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest?) {
            if (request == null) return
            val origin = request.origin?.toString() ?: "unknown"
            onPermissionPrompt(origin, request.resources) { granted ->
                if (granted) request.grant(request.resources) else request.deny()
            }
        }
    }

    fun attachDownloadInterceptor(
        webView: WebView,
        onDownloadDetected: (InterceptedDownload) -> Unit
    ) {
        webView.setDownloadListener { url, _, contentDisposition, mimeType, contentLength ->
            val lower = url.trim().lowercase()
            val canHandle = lower.startsWith("http://") || lower.startsWith("https://")
            val rawName = contentDisposition
                ?.let { Regex("filename\\*?=(?:UTF-8''|\"?)([^\";]+)\"?", RegexOption.IGNORE_CASE).find(it)?.groupValues?.getOrNull(1) }
                ?: url.substringBefore('?').substringAfterLast('/').ifBlank { "download.bin" }
            val safeName = UrlAndPathValidator.sanitizeFileName(rawName)
            onDownloadDetected(
                InterceptedDownload(
                    url = url,
                    suggestedFileName = safeName,
                    mimeType = mimeType,
                    contentLength = contentLength,
                    canEngineHandle = canHandle
                )
            )
        }
    }

    fun wipeAllBrowsingData(webView: WebView?) {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        webView?.clearCache(true)
        webView?.clearHistory()
        webView?.clearFormData()
    }
}

/**
 * Extracts and validates HTTP/HTTPS URLs shared via Android Share Intent or clipboard.
 */
object ExternalLinkExtractor {
    private val URL_REGEX = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)

    fun extractFirstSafeUrl(sharedText: String?): String? {
        if (sharedText.isNullOrBlank()) return null
        val candidate = URL_REGEX.find(sharedText)?.value ?: return null
        // Syntax-only on purpose: this runs on the main thread, where a DNS lookup would crash with
        // NetworkOnMainThreadException. The SSRF/DNS check happens later inside RynDownloadEngine (IO thread).
        return runCatching { UrlAndPathValidator.validateUrlSyntax(candidate).toString() }.getOrNull()
    }
}
