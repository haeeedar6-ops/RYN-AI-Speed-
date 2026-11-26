package com.ryn.aispeed.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ryn.aispeed.ai.ChatTurn
import com.ryn.aispeed.ai.GeminiClient
import com.ryn.aispeed.ai.SecureStringStore
import com.ryn.aispeed.core.security.KeystoreKeyManager
import com.ryn.aispeed.feature.ai.LocalAiRulesEngine
import com.ryn.aispeed.feature.network.LatencyReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val KEY_API = "gemini_api_key"
private const val KEY_MODEL = "gemini_model"

@Composable
fun RynChatScreen(lastLatency: LatencyReport?) {
    val str = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SecureStringStore(context, KeystoreKeyManager(context.applicationContext)) }
    val messages = remember { mutableStateListOf<ChatTurn>() }
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf(store.get(KEY_API).orEmpty()) }
    var model by remember { mutableStateOf(store.get(KEY_MODEL) ?: GeminiClient.DEFAULT_MODEL) }

    LaunchedEffect(messages.size, busy) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size)
    }

    fun systemPrompt(): String {
        val report = lastLatency
        val ping = report?.avgPingMs
        val net = if (report != null && ping != null) {
            "آخر قياس للشبكة: Ping ${ping.toInt()} ms، فقدان الحزم ${report.packetLossPct.toInt()}%."
        } else {
            ""
        }
        return "أنت «RYN AI» مساعد ذكي داخل تطبيق RYN-AI-Speed (مدير تنزيلات + VPN + متصفح). " +
            "أجب بنفس لغة المستخدم، وبـ${str.promptLanguage} افتراضياً، بإيجاز ووضوح. $net"
    }

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || busy) return
        messages.add(ChatTurn(true, text))
        input = ""
        if (apiKey.isBlank()) {
            val diag = LocalAiRulesEngine.diagnoseNetworkOffline(
                pingMs = lastLatency?.avgPingMs,
                jitterMs = lastLatency?.jitterMs,
                packetLossPct = lastLatency?.packetLossPct ?: 0.0
            )
            messages.add(ChatTurn(false, str.chatOfflineReply(diag.titleAr, diag.recommendedConnections)))
            return
        }
        busy = true
        val history = messages.toList()
        scope.launch {
            try {
                val reply = GeminiClient(apiKey, model).generate(history, systemPrompt())
                messages.add(ChatTurn(false, reply))
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                messages.add(ChatTurn(false, "⚠ ${GeminiClient.friendlyError(e)}"))
            } finally {
                busy = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("✦ RYN AI", color = RynColors.Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (messages.isNotEmpty()) {
                    Text("＋", color = RynColors.Text, fontSize = 22.sp, modifier = Modifier.clickable { messages.clear() })
                }
                Text("⚙", color = RynColors.Text, fontSize = 22.sp, modifier = Modifier.clickable { showSettings = true })
            }
        }

        if (messages.isEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(str.chatGreeting1, color = RynColors.Accent, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(str.chatGreeting2, color = RynColors.Muted, fontSize = 24.sp)
                Spacer(Modifier.height(24.dp))
                str.chatSuggestions.forEach { s ->
                    Box(
                        modifier = Modifier
                            .padding(vertical = 5.dp)
                            .background(RynColors.Card, RoundedCornerShape(18.dp))
                            .clickable { send(s) }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) { Text(s, color = RynColors.Text) }
                }
                if (apiKey.isBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text(str.chatNoKeyBanner, color = RynColors.Warn, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(messages.toList()) { turn ->
                    if (turn.fromUser) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Box(
                                modifier = Modifier
                                    .widthIn(max = 300.dp)
                                    .background(RynColors.CardHigh, RoundedCornerShape(20.dp))
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) { SelectionContainer { Text(turn.text, color = RynColors.Text) } }
                        }
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("✦", color = RynColors.Accent, fontSize = 20.sp)
                            SelectionContainer { RichText(turn.text) }
                        }
                    }
                }
                if (busy) {
                    item { Text(str.chatTyping, color = RynColors.Muted) }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(str.chatPlaceholder) },
                shape = RoundedCornerShape(28.dp),
                maxLines = 4,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { send(input) },
                enabled = !busy && input.isNotBlank(),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RynColors.Accent),
                modifier = Modifier.size(52.dp)
            ) { Text("➤", color = RynColors.OnAccent, fontSize = 20.sp) }
        }
    }

    if (showSettings) {
        var keyDraft by remember { mutableStateOf(apiKey) }
        var modelDraft by remember { mutableStateOf(model) }
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text(str.chatSettingsTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(str.chatKeyHelp, fontSize = 13.sp)
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it.trim() },
                        label = { Text(str.chatKeyLabel) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    OutlinedTextField(
                        value = modelDraft,
                        onValueChange = { modelDraft = it.trim() },
                        label = { Text(str.chatModelLabel) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    apiKey = keyDraft
                    model = modelDraft.ifBlank { GeminiClient.DEFAULT_MODEL }
                    if (keyDraft.isBlank()) store.remove(KEY_API) else store.put(KEY_API, keyDraft)
                    store.put(KEY_MODEL, model)
                    showSettings = false
                }) { Text(str.save) }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text(str.cancel) } }
        )
    }
}

/** Minimal Markdown: **bold** and "* item" bullets (enough for typical Gemini answers). */
@Composable
private fun RichText(text: String) {
    val annotated = remember(text) {
        val cleaned = text.replace(Regex("(?m)^\\s*[*-]\\s+"), "• ").replace("`", "")
        buildAnnotatedString {
            var last = 0
            for (m in Regex("\\*\\*(.+?)\\*\\*").findAll(cleaned)) {
                append(cleaned.substring(last, m.range.first))
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
                last = m.range.last + 1
            }
            append(cleaned.substring(last))
        }
    }
    Text(annotated, color = Color(0xFFECFDF5), lineHeight = 22.sp)
}
