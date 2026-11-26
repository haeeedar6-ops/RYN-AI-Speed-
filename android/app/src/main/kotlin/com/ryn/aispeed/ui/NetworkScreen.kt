package com.ryn.aispeed.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ryn.aispeed.feature.network.DnsBenchmarkEntry
import com.ryn.aispeed.feature.network.LatencyReport

@Composable
fun NetworkScreen(
    lastLatency: LatencyReport?,
    dnsEntries: List<DnsBenchmarkEntry>,
    isTesting: Boolean,
    onRunDiagnostics: () -> Unit
) {
    val str = LocalStrings.current
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(str.netTitle, color = RynColors.Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Card(
            colors = CardDefaults.cardColors(containerColor = RynColors.Card),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRunDiagnostics,
                    enabled = !isTesting,
                    colors = ButtonDefaults.buttonColors(containerColor = RynColors.Accent),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (isTesting) str.netRunning else str.netRun,
                        color = RynColors.OnAccent,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (lastLatency != null) {
                    Text(
                        str.netResult(
                            lastLatency.avgPingMs?.toInt() ?: -1,
                            lastLatency.jitterMs?.toInt() ?: 0,
                            lastLatency.packetLossPct.toInt()
                        ),
                        color = RynColors.Text
                    )
                }
                dnsEntries.forEach { entry ->
                    val ms = entry.avgLatencyMs
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${entry.name} (${entry.resolverIp})", color = RynColors.Text)
                        Text(
                            if (entry.reachable && ms != null) "${ms.toInt()} ms" else str.dnsUnavailable,
                            color = if (entry.reachable) RynColors.Accent else RynColors.Danger
                        )
                    }
                }
                Text(str.netNote, color = RynColors.Muted, fontSize = 12.sp)
            }
        }
    }
}
