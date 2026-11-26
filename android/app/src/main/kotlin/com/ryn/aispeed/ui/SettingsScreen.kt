package com.ryn.aispeed.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(currentLanguage: String, onLanguageChange: (String) -> Unit) {
    val str = LocalStrings.current
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(str.settingsTitle, color = RynColors.Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(str.settingsLanguage, color = RynColors.Text, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("ar" to ArabicStrings.languageName, "en" to EnglishStrings.languageName).forEach { (code, name) ->
                if (code == currentLanguage) {
                    Button(
                        onClick = { onLanguageChange(code) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RynColors.Accent),
                        modifier = Modifier.weight(1f)
                    ) { Text(name, color = RynColors.OnAccent, fontWeight = FontWeight.Bold) }
                } else {
                    OutlinedButton(
                        onClick = { onLanguageChange(code) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text(name, color = RynColors.Accent) }
                }
            }
        }
        Text(str.settingsAbout, color = RynColors.Muted, fontSize = 13.sp)
    }
}
