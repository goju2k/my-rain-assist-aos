package com.goju.ribs.myrainassist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goju.ribs.myrainassist.data.PrecipKind
import com.goju.ribs.myrainassist.data.RainEpisode
import com.goju.ribs.myrainassist.ui.theme.RainBlue
import com.goju.ribs.myrainassist.ui.theme.RainSky

private val PageBg = Color(0xFFF3F6FA)
private val SnowTint = Color(0xFFE6F0FF)
private val RainTint = Color(0xFFE3F4FF)
private val MissTint = Color(0xFFEEF1F4)
private val MissInk = Color(0xFF4A5560)
private val BarTrack = Color(0xFFEDF1F6)

/** "기록": the last 30 days of rain/snow at the user's location (see RainHistoryStore). */
@Composable
fun RainHistoryScreen(episodes: List<RainEpisode>, nowMs: Long, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val today = RainHistoryText.dateOf(nowMs)
    val byDay = episodes.sortedByDescending { it.startMs }.groupBy { RainHistoryText.dateOf(it.startMs) }
    Column(modifier = modifier.background(PageBg)) {
        Surface {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("비·눈 기록", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onBack) { Text("닫기", fontWeight = FontWeight.SemiBold) }
                }
                HorizontalDivider(color = BarTrack)
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { WeekSummary(episodes, nowMs) }
            if (byDay.isEmpty()) {
                item {
                    Text(
                        "아직 기록이 없어요\n내 위치에 비나 눈이 오면 여기에 남아요",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    )
                }
            }
            byDay.forEach { (date, dayEpisodes) ->
                item(key = "h$date") {
                    Text(
                        RainHistoryText.dayHeader(date, today),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
                    )
                }
                items(dayEpisodes, key = { "e${it.startMs}" }) { EpisodeCard(it, nowMs) }
            }
            item {
                Text(
                    "최근 30일까지 보관해요",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 28.dp),
                )
            }
        }
    }
}

@Composable
private fun WeekSummary(episodes: List<RainEpisode>, nowMs: Long) {
    val week = RainHistoryText.week(episodes, nowMs)
    val today = RainHistoryText.dateOf(nowMs)
    val max = week.maxOf { it.minutes }.coerceAtLeast(1)
    Column(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(RainBlue, RainSky)), RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text("최근 7일 · 내 위치", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
        Text(
            RainHistoryText.headline(episodes, nowMs),
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            week.forEach { bar ->
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        if (bar.minutes > 0) {
                            val fraction = (0.2f + 0.8f * bar.minutes / max).coerceAtMost(1f)
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(fraction)
                                    .background(Color.White, RoundedCornerShape(8.dp)),
                            )
                        }
                    }
                    Text(
                        RainHistoryText.weekdayLabel(bar.date, today),
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(e: RainEpisode, nowMs: Long) {
    val tint = when {
        e.missed -> MissTint
        e.kind != PrecipKind.RAIN -> SnowTint
        else -> RainTint
    }
    val icon = when {
        e.missed -> "☁️"
        e.kind == PrecipKind.SNOW -> "❄️"
        e.kind == PrecipKind.MIXED -> "🌨️"
        else -> "🌧️"
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp).fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(38.dp).background(tint, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Text(icon, fontSize = 18.sp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(RainHistoryText.title(e), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    RainHistoryText.detail(e, nowMs),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
                if (e.missed) {
                    Text(
                        "오지 않음",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MissInk,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .background(MissTint, RoundedCornerShape(999.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                } else {
                    DayBarLine(e, nowMs)
                }
            }
        }
    }
}

// Where in the day it fell: a 24h track with the spell marked.
@Composable
private fun DayBarLine(e: RainEpisode, nowMs: Long) {
    val (start, width) = RainHistoryText.dayFraction(e, nowMs)
    Row(
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .height(6.dp)
            .background(BarTrack, RoundedCornerShape(3.dp)),
    ) {
        if (start > 0f) Box(Modifier.weight(start))
        Box(Modifier.weight(width).fillMaxHeight().background(RainBlue, RoundedCornerShape(3.dp)))
        val rest = 1f - start - width
        if (rest > 0.0001f) Box(Modifier.weight(rest))
    }
}
