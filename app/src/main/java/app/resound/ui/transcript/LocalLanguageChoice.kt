package app.resound.ui.transcript

import androidx.compose.foundation.layout.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.resound.transcribe.local.LocalAsrLanguage
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.springClick

@Composable
internal fun LocalLanguageChoice(selected: LocalAsrLanguage, onSelect: (LocalAsrLanguage) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Box {
            Row(Modifier.fillMaxWidth().springClick { expanded = true }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("主要识别语言", style = RsType.body, color = Rs.colors.text, modifier = Modifier.weight(1f))
                Text("${selected.title} ▾", style = RsType.subhead, color = Rs.colors.accent)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                LocalAsrLanguage.entries.forEach { language ->
                    DropdownMenuItem(text = { Text(language.title) },
                        trailingIcon = { if (language == selected) Text("✓") },
                        onClick = { onSelect(language); expanded = false })
                }
            }
        }
        Text("带口音的普通话可选中文，减少误判成其他语言；经常切换语言可选自动识别。仅影响后续本地转写或手动重转。",
            style = RsType.caption, color = Rs.colors.textSecondary)
    }
}
