package com.ryn.aispeed.ui

import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ryn.aispeed.feature.browser.HardenedWebViewConfigurator
import java.net.URLEncoder

object BrowserInput {
    const val HOME_URL = "https://duckduckgo.com"

    /** Address-bar logic: URLs are opened (always over HTTPS), anything else becomes a search. */
    fun resolve(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return HOME_URL
        if (t.startsWith("https://")) return t
        if (t.startsWith("http://")) return "https://" + t.removePrefix("http://")
        val looksLikeHost = !t.contains(' ') && t.contains('.')
        return if (looksLikeHost) "https://$t" else "$HOME_URL/?q=" + URLEncoder.encode(t, "UTF-8")
    }
}

@Composable
fun RynBrowserScreen(onDownloadIntercepted: (String) -> Unit) {
    var addressInput by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    BackHandler(enabled = canBack) { webViewRef?.goBack() }
    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = addressInput,
            onValueChange = { addressInput = it },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            placeholder = { Text("ابحث أو اكتب عنوان موقع") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { webViewRef?.loadUrl(BrowserInput.resolve(addressInput)) }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(RynColors.Card)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavAction("◀", canBack) { webViewRef?.goBack() }
            NavAction("▶", canForward) { webViewRef?.goForward() }
            NavAction("⟳", true) { webViewRef?.reload() }
            NavAction("🏠", true) { webViewRef?.loadUrl(BrowserInput.HOME_URL) }
            NavAction("🧹", true) { HardenedWebViewConfigurator.wipeAllBrowsingData(webViewRef) }
        }
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                color = RynColors.Accent,
                trackColor = RynColors.CardHigh,
                modifier = Modifier.fillMaxWidth()
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    HardenedWebViewConfigurator.applySecuritySettings(this)
                    webViewClient = HardenedWebViewConfigurator.createHardenedWebViewClient(
                        onPageChanged = { url, _ ->
                            addressInput = url
                            canBack = canGoBack()
                            canForward = canGoForward()
                        },
                        onBlockedDangerousUrl = {}
                    )
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }

                        override fun onPermissionRequest(request: PermissionRequest?) {
                            request?.deny() // camera / mic / location prompts are never granted silently
                        }
                    }
                    HardenedWebViewConfigurator.attachDownloadInterceptor(this) { dl ->
                        if (dl.canEngineHandle) onDownloadIntercepted(dl.url)
                    }
                    loadUrl(BrowserInput.HOME_URL)
                    webViewRef = this
                }
            }
        )
    }
}

@Composable
private fun NavAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 20.sp,
        color = if (enabled) RynColors.Text else RynColors.Muted.copy(alpha = 0.4f),
        modifier = Modifier
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 4.dp)
    )
}
