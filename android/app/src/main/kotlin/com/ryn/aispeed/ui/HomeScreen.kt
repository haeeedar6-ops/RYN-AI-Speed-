package com.ryn.aispeed.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ryn.aispeed.feature.network.LatencyReport
import com.ryn.aispeed.feature.vpn.CountryCatalog
import com.ryn.aispeed.feature.vpn.VpnUiState

@Composable
fun HomeScreen(
    downloadsCount: Int,
    lastLatency: LatencyReport?,
    banner: String,
    autoEnabled: Boolean,
    onToggleAuto: (Boolean) -> Unit,
    onGo: (Section) -> Unit
) {
    val str = LocalStrings.current
    val context = LocalContext.current
    val stack = remember { VpnRuntime.get(context) }
    val state by stack.controller.state.collectAsState()
    val profiles = remember(state) { stack.store.list() }
    val activeCountry = (state as? VpnUiState.Connected)?.countryCode
    val markers = profiles.map { it.countryCode }.distinct().map { MapMarker(it, it == activeCountry) }

    val (title, color) = when (state) {
        is VpnUiState.Connected -> str.vpnProtected to RynColors.Accent
        is VpnUiState.Connecting -> str.vpnConnectingTitle to RynColors.Warn
        is VpnUiState.Failed -> str.vpnFailedTitle to RynColors.Danger
        VpnUiState.Disconnected -> str.vpnUnprotected to RynColors.Danger
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("RYN-AI-Speed", color = RynColors.Accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(str.tagline, color = RynColors.Muted)

        Card(
            colors = CardDefaults.cardColors(containerColor = RynColors.Card),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WorldMap(
                    markers = markers,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(title, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        val connected = state as? VpnUiState.Connected
                        Text(
                            if (connected != null) {
                                "${CountryCatalog.flagEmoji(connected.countryCode)} ${connected.name}  •  ${connected.ip ?: "—"}"
                            } else {
                                str.mapCaption
                            },
                            color = RynColors.Muted
                        )
                    }
                    Button(
                        onClick = { onGo(Section.VPN) },
                        colors = ButtonDefaults.buttonColors(containerColor = RynColors.Accent)
                    ) { Text(str.goVpn, color = RynColors.OnAccent, fontWeight = FontWeight.Bold) }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard(str.statDownloads(downloadsCount), Modifier.weight(1f))
            val ping = lastLatency?.avgPingMs
            StatCard(
                if (lastLatency != null && ping != null) str.statPing(ping.toInt(), lastLatency.packetLossPct.toInt()) else str.statNoTest,
                Modifier.weight(1f)
            )
            StatCard(str.statServers(profiles.size), Modifier.weight(1f))
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = RynColors.Card),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(str.autoTitle, color = RynColors.Text, fontWeight = FontWeight.SemiBold)
                    Text(str.autoHint, color = RynColors.Muted, fontSize = 13.sp)
                }
                Switch(checked = autoEnabled, onCheckedChange = onToggleAuto)
            }
        }

        Text(banner, color = RynColors.Muted, fontSize = 13.sp)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { onGo(Section.DOWNLOADS) },
                colors = ButtonDefaults.buttonColors(containerColor = RynColors.Accent),
                modifier = Modifier.weight(1f)
            ) { Text(str.goDownloads, color = RynColors.OnAccent, fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = { onGo(Section.NETWORK) }, modifier = Modifier.weight(1f)) {
                Text(str.goNetwork, color = RynColors.Accent)
            }
        }
    }
}

@Composable
private fun StatCard(text: String, modifier: Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = RynColors.Card),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        Text(
            text,
            color = RynColors.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(12.dp)
        )
    }
}
