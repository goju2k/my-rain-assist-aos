package com.goju.ribs.myrainassist.ui

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goju.ribs.myrainassist.brief.DailyBriefSettings
import com.goju.ribs.myrainassist.service.RainMonitorService

private val PageBg = Color(0xFFF3F6FA)
private val Divider = Color(0xFFEDF1F6)

/** "오전 7:30" */
fun koreanClock(hour: Int, minute: Int): String {
    val half = if (hour < 12) "오전" else "오후"
    val h = when (val h12 = hour % 12) { 0 -> 12; else -> h12 }
    return "$half $h:%02d".format(minute)
}

/** 설정 (web 톱니 button): for now just 매일 날씨 알림 — on/off and the time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(DailyBriefSettings.read(context)) }
    var picking by remember { mutableStateOf(false) }
    val exactAllowed = remember { context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }

    fun update(next: DailyBriefSettings) {
        settings = next
        DailyBriefSettings.save(context, next)
    }

    Column(modifier = modifier.background(PageBg)) {
        Surface {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("설정", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onBack) { Text("닫기", fontWeight = FontWeight.SemiBold) }
                }
                HorizontalDivider(color = Divider)
            }
        }
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("매일 날씨 알림", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "정한 시각에 내 위치의 기온·옷차림·비 소식을 알려 드려요",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = settings.enabled, onCheckedChange = { update(settings.copy(enabled = it)) })
                    }
                    if (settings.enabled) {
                        HorizontalDivider(color = Divider)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { picking = true }
                                .padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("알림 시각", fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text(
                                koreanClock(settings.hour, settings.minute),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            if (settings.enabled) {
                Text(
                    "예: 기온 3℃ · 패딩 추천 / 지금은 눈 소식이 없어요",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp, top = 10.dp),
                )
                OutlinedButton(
                    onClick = { RainMonitorService.requestDailyBriefNow(context) },
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text("지금 한 번 받아 보기") }
                if (!exactAllowed) {
                    Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "'알람 및 리마인더'를 허용하지 않으면 알림이 몇 분 늦게 올 수 있어요.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }) { Text("허용하러 가기") }
                    }
                }
            }
        }
    }

    if (picking) {
        val state = rememberTimePickerState(initialHour = settings.hour, initialMinute = settings.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("알림 시각") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    update(settings.copy(hour = state.hour, minute = state.minute))
                    picking = false
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("취소") } },
        )
    }
}
