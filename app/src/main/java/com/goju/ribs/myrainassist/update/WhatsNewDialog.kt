package com.goju.ribs.myrainassist.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Shown once after an update: what changed in the versions the user skipped over, newest first. */
@Composable
fun WhatsNewDialog(entries: List<ReleaseNotes.Entry>, onDismiss: () -> Unit) {
    if (entries.isEmpty()) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("v${entries.first().versionName}로 업데이트됐어요") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                entries.forEach { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (entries.size > 1) {
                            Text("v${entry.versionName}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        entry.lines.forEach { line -> Text("• $line") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("확인") } },
    )
}
