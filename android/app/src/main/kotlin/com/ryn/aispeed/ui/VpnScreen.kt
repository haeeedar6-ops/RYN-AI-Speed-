package com.ryn.aispeed.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ryn.aispeed.feature.network.DnsBenchmarkEntry
import com.ryn.aispeed.feature.network.LatencyReport
import com.ryn.aispeed.feature.vpn.CountryCatalog
import com.ryn.aispeed.feature.vpn.VpnProfile
import com.ryn.aispeed.feature.vpn.VpnUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_CONFIG_BYTES = 20_000

private fun readImportedFile(context: Context, uri: Uri): Pair<String, String> {
    val name = context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        ?: "server"
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalArgumentException("تعذّر قراءة الملف")
    require(bytes.size <= MAX_CONFIG_BYTES) { "الملف كبير جداً ليكون إعداد WireGuard" }
    return name to String(bytes, Charsets.UTF_8)
}

@Composable
fun VpnScreen(
    lastLatency: LatencyReport?,
    dnsEntries: List<DnsBenchmarkEntry>,
    isTesting: Boolean,
    onRunDiagnostics: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stack = remember { VpnRuntime.get(context) }
    val state by stack.controller.state.collectAsState()
    var profiles by remember { mutableStateOf(stack.store.list()) }
    var expandedCountry by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = pendingAction
        pendingAction = null
        if (result.resultCode == Activity.RESULT_OK) action?.invoke() else message = "تم رفض صلاحية VPN من النظام"
    }

    fun withVpnPermission(action: () -> Unit) {
        val intent = VpnService.prepare(context)
        if (intent == null) {
            action()
        } else {
            pendingAction = action
            permissionLauncher.launch(intent)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            var imported = 0
            val errors = mutableListOf<String>()
            for (uri in uris) {
                try {
                    val (name, text) = withContext(Dispatchers.IO) { readImportedFile(context, uri) }
                    stack.store.importConfig(name, text)
                    imported++
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    errors.add(e.message ?: "ملف غير صالح")
                }
            }
            profiles = stack.store.list()
            message = when {
                errors.isEmpty() -> "تمت إضافة $imported سيرفر"
                imported == 0 -> "فشل الاستيراد: ${errors.first()}"
                else -> "تمت إضافة $imported وفشل ${errors.size}: ${errors.first()}"
            }
        }
    }

    val countries = profiles.groupBy { it.countryCode }.toList().sortedBy { CountryCatalog.nameAr(it.first) }
    val connected = state is VpnUiState.Connected
    val connecting = state is VpnUiState.Connecting

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(8.dp)) }

        // ---- Status hero (Proton-style) ----
        item {
            val (title, subtitle, color) = when (val s = state) {
                is VpnUiState.Connected -> Triple(
                    "محمي",
                    "${CountryCatalog.flagEmoji(s.countryCode)} ${s.name}  •  IP: ${s.ip ?: "—"}",
                    RynColors.Accent
                )
                is VpnUiState.Connecting -> Triple("جاري الاتصال...", s.label, RynColors.Warn)
                is VpnUiState.Failed -> Triple("فشل الاتصال", s.message, RynColors.Danger)
                VpnUiState.Disconnected -> Triple("غير محمي", "اتصالك مكشوف. اضغط اتصال سريع", RynColors.Danger)
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = RynColors.Card),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(color.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (connected) "🔒" else "🔓", fontSize = 40.sp)
                    }
                    Text(title, color = color, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = RynColors.Muted)
                    val s = state
                    if (s is VpnUiState.Connected) {
                        val exit = s.exitCountry?.let { "${CountryCatalog.flagEmoji(it)} ${CountryCatalog.nameAr(it)}" } ?: "—"
                        Text("الخروج من: $exit  •  زمن التحقق: ${s.latencyMs ?: 0} ms", color = RynColors.Text)
                        s.warning?.let { Text(it, color = RynColors.Warn) }
                    }
                }
            }
        }

        // ---- Quick connect / disconnect ----
        item {
            Button(
                onClick = {
                    if (connected) {
                        scope.launch { stack.controller.disconnect() }
                    } else if (profiles.isEmpty()) {
                        message = "أضف سيرفرات WireGuard أولاً (زر الإضافة بالأسفل)"
                    } else {
                        withVpnPermission { scope.launch { stack.controller.connectAuto(profiles) } }
                    }
                },
                enabled = !connecting,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (connected) RynColors.Danger else RynColors.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = when {
                        connecting -> "جاري الاتصال..."
                        connected -> "قطع الاتصال"
                        else -> "⚡ اتصال سريع (تلقائي)"
                    },
                    color = if (connected) Color.White else RynColors.OnAccent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
        }

        message?.let { msg ->
            item { Text(msg, color = RynColors.Warn) }
        }

        // ---- Countries ----
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("الدول (${countries.size})", color = RynColors.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                    Text("＋ إضافة سيرفر (.conf)", color = RynColors.Accent)
                }
            }
        }

        if (countries.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = RynColors.Card), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("لا توجد سيرفرات بعد", color = RynColors.Text, fontWeight = FontWeight.Bold)
                        Text(
                            "الـVPN يحتاج سيرفرات WireGuard حقيقية بعدة دول. خياراتك: " +
                                "(1) اشتراك بمزوّد يعطيك ملفات WireGuard مثل Mullvad، أو " +
                                "(2) سيرفرك الخاص. حمّل ملفات .conf ثم اضغط «إضافة سيرفر». " +
                                "سمِّ الملف بكود الدولة (us-nyc.conf أو se-sto.conf) ليظهر العلم والاسم.",
                            color = RynColors.Muted
                        )
                    }
                }
            }
        }

        items(countries) { (code, servers) ->
            CountryCard(
                code = code,
                servers = servers,
                expanded = expandedCountry == code,
                busy = connecting,
                onToggle = { expandedCountry = if (expandedCountry == code) null else code },
                onConnect = { profile ->
                    withVpnPermission { scope.launch { stack.controller.connect(profile) } }
                },
                onDelete = { profile ->
                    stack.store.delete(profile.id)
                    profiles = stack.store.list()
                }
            )
        }

        // ---- Kill switch ----
        item {
            Card(colors = CardDefaults.cardColors(containerColor = RynColors.Card), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("قاطع الاتصال (Kill Switch)", color = RynColors.Text, fontWeight = FontWeight.Bold)
                    Text(
                        "أندرويد يوفّر قاطعاً حقيقياً: فعّل «VPN دائم التشغيل» و«حظر الاتصالات بدون VPN» من إعدادات النظام.",
                        color = RynColors.Muted
                    )
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("فتح إعدادات VPN في النظام", color = RynColors.Accent) }
                }
            }
        }

        // ---- Diagnostics ----
        item {
            Card(colors = CardDefaults.cardColors(containerColor = RynColors.Card), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("فحص الشبكة الحقيقي", color = RynColors.Text, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = onRunDiagnostics,
                        enabled = !isTesting,
                        colors = ButtonDefaults.buttonColors(containerColor = RynColors.Accent),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isTesting) "جاري الفحص..." else "فحص Ping و DNS", color = RynColors.OnAccent, fontWeight = FontWeight.Bold)
                    }
                    if (lastLatency != null) {
                        val ping = lastLatency.avgPingMs
                        Text(
                            "Ping: ${ping?.toInt() ?: -1} ms • Jitter: ${lastLatency.jitterMs?.toInt() ?: 0} ms • فقدان الحزم: ${lastLatency.packetLossPct.toInt()}%",
                            color = RynColors.Text
                        )
                    }
                    dnsEntries.forEach { entry ->
                        val ms = entry.avgLatencyMs
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${entry.name} (${entry.resolverIp})", color = RynColors.Text)
                            Text(
                                if (entry.reachable && ms != null) "${ms.toInt()} ms" else "غير متاح",
                                color = if (entry.reachable) RynColors.Accent else RynColors.Danger
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun CountryCard(
    code: String,
    servers: List<VpnProfile>,
    expanded: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onConnect: (VpnProfile) -> Unit,
    onDelete: (VpnProfile) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = RynColors.Card),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(CountryCatalog.flagEmoji(code), fontSize = 28.sp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(CountryCatalog.nameAr(code), color = RynColors.Text, fontWeight = FontWeight.SemiBold)
                    Text("${servers.size} سيرفر", color = RynColors.Muted, fontSize = 13.sp)
                }
                Button(
                    onClick = { onConnect(servers.first()) },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = RynColors.Accent)
                ) { Text("اتصال", color = RynColors.OnAccent, fontWeight = FontWeight.Bold) }
                Text(if (expanded) "⌃" else "⌄", color = RynColors.Muted, fontSize = 20.sp)
            }
            if (expanded) {
                servers.forEach { server ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(RynColors.CardHigh)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(server.name, color = RynColors.Text)
                            Text(
                                server.endpoint + if (server.fullTunnel) "" else "  •  split-tunnel",
                                color = RynColors.Muted,
                                fontSize = 12.sp
                            )
                        }
                        TextButton(onClick = { onConnect(server) }, enabled = !busy) {
                            Text("اتصال", color = RynColors.Accent)
                        }
                        TextButton(onClick = { onDelete(server) }) {
                            Text("حذف", color = RynColors.Danger)
                        }
                    }
                }
            }
        }
    }
}
