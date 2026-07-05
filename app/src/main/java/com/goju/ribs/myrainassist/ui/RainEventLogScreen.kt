package com.goju.ribs.myrainassist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.goju.ribs.myrainassist.notification.RainEventLogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TIME_FORMAT = SimpleDateFormat("MM/dd HH:mm:ss", Locale.KOREA)

private fun stateLabel(state: String): String = when (state) {
    "INCOMING" -> "예보"
    "ACTIVE" -> "강수 시작"
    "STOPPED" -> "강수 종료"
    else -> state
}

@Composable
fun RainEventLogScreen(entries: List<RainEventLogEntry>, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("닫기") }
            Text("알림 기록", style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider()
        if (entries.isEmpty()) {
            Text(
                "아직 기록된 알림이 없어요",
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                textAlign = TextAlign.Center,
            )
            return@Column
        }
        LazyColumn {
            items(entries) { entry ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text(TIME_FORMAT.format(Date(entry.timestampEpochMs)), style = MaterialTheme.typography.bodySmall)
                        Text(stateLabel(entry.state), style = MaterialTheme.typography.labelMedium)
                    }
                    Text(entry.message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
                }
                HorizontalDivider()
            }
        }
    }
}
