package com.goju.ribs.myrainassist.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

/** What the update prompt shows; [None] draws nothing. */
sealed interface UpdateUi {
    data object None : UpdateUi
    data class Available(val latest: LatestApp) : UpdateUi
    data class Downloading(val latest: LatestApp, val progress: Float) : UpdateUi
    data class Failed(val latest: LatestApp) : UpdateUi
}

@Composable
fun UpdateDialog(
    state: UpdateUi,
    currentVersionName: String,
    onUpdate: (LatestApp) -> Unit,
    onLater: () -> Unit,
) {
    when (state) {
        UpdateUi.None -> Unit
        is UpdateUi.Available -> AlertDialog(
            onDismissRequest = onLater,
            title = { Text("새 버전이 있어요") },
            text = {
                Text("v$currentVersionName → v${state.latest.versionName} (${sizeMb(state.latest)}MB)\n받은 뒤 설치 화면에서 '설치'를 눌러 주세요.")
            },
            confirmButton = { TextButton(onClick = { onUpdate(state.latest) }) { Text("업데이트") } },
            dismissButton = { TextButton(onClick = onLater) { Text("나중에") } },
        )
        is UpdateUi.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("v${state.latest.versionName} 받는 중") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("${(state.progress * 100).toInt()}%")
                }
            },
            confirmButton = {},
        )
        is UpdateUi.Failed -> AlertDialog(
            onDismissRequest = onLater,
            title = { Text("업데이트를 받지 못했어요") },
            text = { Text("네트워크를 확인한 뒤 다시 시도해 주세요.") },
            confirmButton = { TextButton(onClick = { onUpdate(state.latest) }) { Text("다시 시도") } },
            dismissButton = { TextButton(onClick = onLater) { Text("닫기") } },
        )
    }
}

private fun sizeMb(latest: LatestApp): String =
    String.format(Locale.US, "%.0f", latest.sizeBytes / (1024.0 * 1024.0))
