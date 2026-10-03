package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.ai.AiKeyStore
import com.noop.notif.LocalBriefingCopy

@Composable
fun LocalBriefingScreen(vm: AppViewModel, onOpenCoach: () -> Unit, onOpenCoachSettings: () -> Unit, onOpenAlarms: () -> Unit) {
    val snapshot by vm.localBriefing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var review by remember { mutableStateOf(false) }
    val summary = LocalBriefingCopy.summary(context, snapshot?.recovery, snapshot?.sleepMinutes,
        if (review) snapshot?.strainTenths else null, snapshot?.streak ?: 0)
    ScreenScaffold(title = uiString(R.string.local_summary), subtitle = uiString(R.string.local_summary_detail)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            NoopButton(text = uiString(R.string.local_outlook), kind = NoopButtonKind.Secondary,
                onClick = { review = false }, modifier = Modifier.weight(1f))
            NoopButton(text = uiString(R.string.local_review), kind = NoopButtonKind.Secondary,
                onClick = { review = true }, modifier = Modifier.weight(1f))
        }
        MoreHubSection(uiString(if (review) R.string.local_review else R.string.local_outlook)) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                Text(summary, style = NoopType.body, color = Palette.textPrimary)
                snapshot?.day?.let { Text(it,
                    style = NoopType.footnote, color = Palette.textSecondary)
                }
                if (snapshot?.syncPending == true) Text(uiString(R.string.local_summary_pending),
                    style = NoopType.footnote, color = Palette.statusWarning)
                if (review) {
                    Text(uiString(R.string.local_review_plan_detail), style = NoopType.footnote, color = Palette.textSecondary)
                    NoopButton(text = uiString(R.string.nav_alarms), kind = NoopButtonKind.Secondary,
                        fullWidth = true, onClick = onOpenAlarms)
                }
            }
        }
        NoopButton(text = uiString(R.string.coach_settings), kind = NoopButtonKind.Secondary,
            fullWidth = true, onClick = onOpenCoachSettings)
        if (AiKeyStore.hasKey(context)) NoopButton(text = uiString(R.string.nav_coach),
            kind = NoopButtonKind.Secondary, fullWidth = true, onClick = onOpenCoach)
    }
}
