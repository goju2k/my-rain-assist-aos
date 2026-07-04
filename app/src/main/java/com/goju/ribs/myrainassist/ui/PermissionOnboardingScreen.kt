package com.goju.ribs.myrainassist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class OnboardingStepInfo(val title: String, val rationale: String, val actionLabel: String)

@Composable
fun PermissionOnboardingScreen(
    step: OnboardingStepInfo,
    onRequestPermission: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = step.title, style = MaterialTheme.typography.headlineSmall)
        Text(text = step.rationale, modifier = Modifier.padding(top = 12.dp, bottom = 24.dp))
        Button(onClick = onRequestPermission) { Text(step.actionLabel) }
        OutlinedButton(onClick = onSkip, modifier = Modifier.padding(top = 8.dp)) { Text("건너뛰기") }
    }
}
