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
import com.noop.notif.LocalNotificationContext
import com.noop.notif.LocalRecordedReport

@Composable
fun LocalBriefingScreen(vm: AppViewModel, onOpenCoach: () -> Unit, onOpenCoachSettings: () -> Unit, onOpenAlarms: () -> Unit, notificationContext: LocalNotificationContext? = null) {
    val snapshot by vm.localBriefing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var review by remember(notificationContext?.identity) {
        mutableStateOf(notificationContext?.family in listOf("dayInReview", "strainReady", "streakSummary", "strainTarget"))
    }
    val current = snapshot?.let { LocalRecordedReport(it.day, it.recovery, it.sleepMinutes, it.strainTenths, it.streak) }
    val report = LocalRecordedReport.forDisplay(notificationContext, current)
    val summary = LocalBriefingCopy.summary(context, report?.recovery, report?.sleepMinutes,
        if (review) report?.strainTenths else null, report?.streak ?: 0)
    ScreenScaffold(title = uiString(R.string.local_summary), subtitle = uiString(R.string.local_summary_detail)) {
        notificationContext?.message?.let {
            NoopCard { Text(it, style = NoopType.body, color = Palette.textPrimary) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            NoopButton(text = uiString(R.string.local_outlook), kind = NoopButtonKind.Secondary,
                onClick = { review = false }, modifier = Modifier.weight(1f))
            NoopButton(text = uiString(R.string.local_review), kind = NoopButtonKind.Secondary,
                onClick = { review = true }, modifier = Modifier.weight(1f))
        }
        MoreHubSection(uiString(if (review) R.string.local_review else R.string.local_outlook)) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                Text(summary, style = NoopType.body, color = Palette.textPrimary)
                (report?.day ?: notificationContext?.day)?.let { Text(it,
                    style = NoopType.footnote, color = Palette.textSecondary)
                }
                if (notificationContext == null && snapshot?.syncPending == true) Text(uiString(R.string.local_summary_pending),
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

@Composable
fun LocalRecordedNoticeScreen(notificationContext: LocalNotificationContext) {
    ScreenScaffold(title = uiString(if (notificationContext.route == "weekly_plan")
        R.string.whoop_nav_weekly_plan else R.string.nav_workouts)) {
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                notificationContext.message?.let { Text(it, style = NoopType.body, color = Palette.textPrimary) }
                (notificationContext.weekKey ?: notificationContext.day)?.let {
                    Text(it, style = NoopType.footnote, color = Palette.textSecondary)
                }
                notificationContext.workoutStartSec?.let {
                    Text(java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString(),
                        style = NoopType.footnote, color = Palette.textSecondary)
                }
            }
        }
    }
}
