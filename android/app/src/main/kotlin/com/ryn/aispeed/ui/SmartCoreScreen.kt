package com.ryn.aispeed.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ryn.aispeed.feature.ai.LocalAiRulesEngine
import com.ryn.aispeed.feature.network.LatencyReport

@Composable
fun SmartCoreScreen(
    lastLatency: LatencyReport?,
    autoEnabled: Boolean,
    onToggleAuto: (Boolean) -> Unit
) {
    val str = LocalStrings.current
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(str.scTitle, color = RynColors.Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(str.scIntro, color = RynColors.Muted)

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

        Card(
            colors = CardDefaults.cardColors(containerColor = RynColors.Card),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(str.scAnalysis, color = RynColors.Text, fontWeight = FontWeight.Bold)
                val ping = lastLatency?.avgPingMs
                if (lastLatency == null || ping == null) {
                    Text(str.scNoData, color = RynColors.Muted)
                } else {
                    val diag = LocalAiRulesEngine.diagnoseNetworkOffline(
                        pingMs = ping,
                        jitterMs = lastLatency.jitterMs,
                        packetLossPct = lastLatency.packetLossPct
                    )
                    Text(diag.titleAr, color = RynColors.Accent, fontWeight = FontWeight.SemiBold)
                    Text(str.scRecommendation(diag.recommendedConnections), color = RynColors.Text)
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = RynColors.Card),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(str.scLevelsTitle, color = RynColors.Text, fontWeight = FontWeight.Bold)
                Text("• ${str.scLevelRead}", color = RynColors.Muted)
                Text("• ${str.scLevelSafe}", color = RynColors.Muted)
                Text("• ${str.scLevelProtected}", color = RynColors.Muted)
                Text(str.scManualWins, color = RynColors.Accent, fontSize = 13.sp)
            }
        }
    }
}
