package com.goju.ribs.myrainassist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.goju.ribs.myrainassist.notification.LastCycleSnapshot
import com.goju.ribs.myrainassist.notification.RainEventLogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val KST = TimeZone.getTimeZone("Asia/Seoul")
private val TIME_FORMAT = SimpleDateFormat("MM/dd HH:mm:ss", Locale.KOREA)
private val TM_DISPLAY_FORMAT = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).apply { timeZone = KST }
// tm (e.g. "202607220005") is KST wall-clock, not a timezone-neutral epoch — see RadarModels.kt.
private val TM_PARSE_FORMAT = SimpleDateFormat("yyyyMMddHHmm", Locale.KOREA).apply { timeZone = KST }

private fun formatTm(tm: String): String = runCatching {
    TM_DISPLAY_FORMAT.format(TM_PARSE_FORMAT.parse(tm)!!)
}.getOrDefault(tm)

private fun stateLabel(state: String): String = when (state) {
    "NONE" -> "감지된 비 없음"
    "INCOMING" -> "예보"
    "ACTIVE" -> "강수 시작"
    "STOPPED" -> "강수 종료"
    "MISSED" -> "예보 취소"
    "SKIP_LOCATION" -> "위치 실패"
    "SKIP_FETCH" -> "레이더 실패"
    "SKIP_FORECAST" -> "범위 밖"
    else -> state
}

@Composable
fun RainEventLogScreen(
    entries: List<RainEventLogEntry>,
    lastCycle: LastCycleSnapshot?,
    onBack: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("알림 기록", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onBack) { Text("닫기") }
            }
            if (lastCycle != null) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "마지막 체크: ${TIME_FORMAT.format(Date(lastCycle.timestampEpochMs))}  (상태: ${stateLabel(lastCycle.state)})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "레이더 프레임: ${formatTm(lastCycle.latestFrameTm)} (tm=${lastCycle.latestFrameTm})  지연 ${lastCycle.lagMinutes}분  프레임 ${lastCycle.frameCount}개",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "ETA=${lastCycle.etaMinutes ?: "-"}분  최단거리=${lastCycle.nearestRainDistanceKm?.let { "%.1f".format(it) } ?: "-"}km  강도=${lastCycle.intensityMmh?.let { "%.1f".format(it) } ?: "-"}mm/h",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            if (entries.isEmpty()) {
                Text(
                    "아직 기록된 알림이 없어요",
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = TextAlign.Center,
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(entries) { entry ->
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text(TIME_FORMAT.format(Date(entry.timestampEpochMs)), style = MaterialTheme.typography.bodySmall)
                                Text(stateLabel(entry.state), style = MaterialTheme.typography.labelMedium)
                            }
                            Text(entry.message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
                            if (entry.debug != null) {
                                Text(
                                    entry.debug.toString(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
        if (entries.isNotEmpty()) {
            Surface(
                onClick = onClear,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.errorContainer,
                shadowElevation = 4.dp,
            ) {
                Text(
                    "기록 지우기",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}
