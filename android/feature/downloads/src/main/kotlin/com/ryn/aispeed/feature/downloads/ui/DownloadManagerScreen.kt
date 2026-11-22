package com.ryn.aispeed.feature.downloads.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ryn.aispeed.core.database.DownloadEntity

@Composable
fun DownloadManagerScreen(
    downloads: List<DownloadEntity>,
    onAddUrl: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var urlInput by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B1114))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "RYN Download Engine",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF10B981)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                placeholder = { Text("https://...") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    if (urlInput.isNotBlank()) {
                        onAddUrl(urlInput.trim())
                        urlInput = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
            ) {
                Text("Download", color = Color(0xFF041C14), fontWeight = FontWeight.Bold)
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(downloads, key = { it.id }) { item ->
                DownloadItemCard(item = item, onPause = onPause, onResume = onResume)
            }
        }
    }
}

@Composable
private fun DownloadItemCard(
    item: DownloadEntity,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit
) {
    val totalSize = item.fileSize
    val progress = if (totalSize != null && totalSize > 0L) {
        (item.downloadedBytes.toFloat() / totalSize.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111A1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = item.fileName,
                color = Color(0xFFECFDF5),
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${item.status} • ${item.category} • ${item.maxConnections} conn",
                color = Color(0xFF9CA3AF),
                style = MaterialTheme.typography.bodySmall
            )
            LinearProgressIndicator(
                progress = { progress },
                color = Color(0xFF10B981),
                trackColor = Color(0xFF1F2937),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.status == "DOWNLOADING") {
                    Button(onClick = { onPause(item.id) }) { Text("Pause") }
                } else if (item.status == "PAUSED" || item.status == "FAILED") {
                    Button(onClick = { onResume(item.id) }) { Text("Resume") }
                }
            }
        }
    }
}
