package com.noop.ui

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import com.noop.alarm.WindDownScheduler
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.alarm.SleepPlannerSettings
import com.noop.alarm.WindDownStore
import com.noop.analytics.SleepPlan
import java.text.DateFormat
import java.util.Calendar

internal data class PlannerAlarmSnapshot(
    val wake: Calendar,
    val bedtime: Calendar,
    val reminder: Calendar,
    val epochSec: Long?,
    val plan: SleepPlan,
    val status: String,
    val countdownMinutes: Long?,
)

internal fun plannerAlarmSnapshot(
    nowMs: Long,
    enabled: Boolean,
    wakeMinutes: Int,
    weekdays: Set<Int>,
    overrides: Map<Int, Int>,
    settings: SleepPlannerSettings,
    leadMinutes: Int,
    sentEpoch: Long,
    sentAt: Long,
    sentDevice: String?,
    sentConnected: Boolean,
    reportedEpoch: Long,
    reportedAt: Long,
    reportedDevice: String?,
    activeDevice: String,
    rejectStreak: Int = 0,
    calendarFactory: () -> Calendar = { Calendar.getInstance() },
): PlannerAlarmSnapshot {
    val planEpoch = nextSmartAlarmEpochSec(
        wakeMinutes, if (enabled) weekdays else emptySet(), nowMs = nowMs, dayOverrides = overrides,
        skippedOccurrence = if (enabled) settings.skippedOccurrence else "",
        calendarFactory = calendarFactory,
    )
    val epoch = if (enabled) planEpoch else null
    val wake = calendarFactory().apply { timeInMillis = planEpoch?.times(1000L) ?: nowMs }
    val minute = wake.get(Calendar.HOUR_OF_DAY) * 60 + wake.get(Calendar.MINUTE)
    val plan = settings.plan(wake.get(Calendar.DAY_OF_WEEK), minute, leadMinutes)
    val bedtime = com.noop.analytics.SleepPlanner.bedtime(wake, plan.targetSleepMinutes)
    val reminder = (bedtime.clone() as Calendar).apply {
        timeInMillis = bedtime.timeInMillis - leadMinutes.coerceIn(0, 120) * 60_000L
    }
    val sent = epoch != null && sentEpoch == epoch && sentDevice == activeDevice && sentConnected
    val reported = sent && rejectStreak == 0 && reportedEpoch == epoch && reportedAt >= sentAt && reportedDevice == activeDevice
    val status = when {
        epoch == null -> "off"
        reported -> "reported"
        sent -> "sent"
        else -> "local"
    }
    return PlannerAlarmSnapshot(
        wake = wake,
        bedtime = bedtime,
        reminder = reminder,
        epochSec = epoch,
        plan = plan,
        status = status,
        countdownMinutes = if (reported) ((epoch!! * 1000L - nowMs + 59_999L) / 60_000L).coerceAtLeast(0) else null,
    )
}

@Composable
fun SmartAlarmScreen(vm: AppViewModel) {
    var showPhoneAlarm by remember { mutableStateOf(false) }
    if (showPhoneAlarm) {
        LegacyPhoneAlarmScreen(vm) { showPhoneAlarm = false }
        return
    }
    val context = LocalContext.current
    val settings by vm.sleepPlannerSettings.collectAsStateWithLifecycle()
    val enabled by vm.smartAlarmEnabled.collectAsStateWithLifecycle()
    val wakeMinutes by vm.smartAlarmMinutes.collectAsStateWithLifecycle()
    val weekdays by vm.smartAlarmWeekdays.collectAsStateWithLifecycle()
    val overrides by vm.smartAlarmDayOverrides.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val activeIsWhoop by vm.activeIsWhoop.collectAsStateWithLifecycle()
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val strapId by vm.activeStrapIdFlow.collectAsStateWithLifecycle()
    val windDown by vm.windDownEnabled.collectAsStateWithLifecycle()
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(10_000L)
        }
    }
    LaunchedEffect(days, strapId) { vm.refreshSleepPlannerInputs() }
    val prefs = NoopPrefs.of(context)
    val snapshot = plannerAlarmSnapshot(
        nowMs = nowMs, enabled = enabled && activeIsWhoop, wakeMinutes = wakeMinutes, weekdays = weekdays,
        overrides = overrides, settings = settings, leadMinutes = WindDownStore.from(context).leadMinutes,
        sentEpoch = prefs.getLong("alarm.lastArmSentEpoch", 0), sentAt = prefs.getLong("alarm.lastArmAt", 0),
        sentDevice = prefs.getString("alarm.lastArmDeviceId", null),
        sentConnected = prefs.getBoolean("alarm.lastArmConnected", false),
        reportedEpoch = prefs.getLong("alarm.lastReportedEpoch", 0),
        reportedAt = prefs.getLong("alarm.lastReportedAt", 0),
        reportedDevice = prefs.getString("alarm.lastReportedDeviceId", null), activeDevice = vm.activeStrapId,
        rejectStreak = prefs.getInt("alarm.rejectStreak", 0),
    )
    var draftEnabled by remember(enabled) { mutableStateOf(enabled) }
    var draftWake by remember(wakeMinutes) { mutableStateOf(wakeMinutes) }
    var draftWeekdays by remember(weekdays) { mutableStateOf(weekdays) }
    var draftOverrides by remember(overrides) { mutableStateOf(overrides) }
    var draftMode by remember(settings.alarmMode) { mutableStateOf(settings.alarmMode) }
    var result by remember { mutableStateOf<String?>(null) }
    var showDays by remember { mutableStateOf(false) }
    val clock = Calendar.getInstance().apply { timeInMillis = nowMs }
    val currentOccurrence = com.noop.analytics.PlannerAlarmPolicy.occurrenceKey(
        clock.get(Calendar.YEAR), clock.get(Calendar.MONTH) + 1,
        clock.get(Calendar.DAY_OF_MONTH), clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE),
    )
    val skippedPending = com.noop.analytics.PlannerAlarmPolicy.isSkipPending(settings.skippedOccurrence, currentOccurrence)
    val planWeekday = snapshot.wake.get(Calendar.DAY_OF_WEEK)
    var requestingDebtPermission by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (requestingDebtPermission) vm.setSleepPlannerSettings(settings.copy(debtReminderEnabled = granted))
        else vm.setWindDownEnabled(granted)
        if (!granted) result = "permission"
    }
    LazyScreenScaffold(title = stringResource(R.string.sleep_planner_title), subtitle = stringResource(R.string.sleep_planner_subtitle)) {
        item {
            NoopCard(tint = DomainTheme.Rest.color) {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
                    Overline(stringResource(R.string.sleep_planner_next_plan))
                    Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(snapshot.wake.time), style = NoopType.title2, color = Palette.textPrimary)
                    GoalChoices(settings.goals[planWeekday] ?: settings.goalPercent) {
                        vm.setSleepPlannerSettings(settings.copy(goals = settings.goals + (planWeekday to it)))
                    }
                    PlannerFigure(stringResource(R.string.sleep_planner_bedtime), plannerClock(snapshot.bedtime, snapshot.wake))
                    PlannerFigure(stringResource(R.string.sleep_planner_sleep_target), durationMinutes(snapshot.plan.targetSleepMinutes))
                    PlannerFigure(stringResource(R.string.sleep_planner_need), durationMinutes(snapshot.plan.needMinutes))
                    PlannerFigure(stringResource(R.string.sleep_planner_debt), durationMinutes(snapshot.plan.debtMinutes))
                    Text(stringResource(if (snapshot.plan.historyReady) R.string.sleep_planner_ready else R.string.sleep_planner_learning), style = NoopType.footnote, color = Palette.textSecondary)
                }
            }
        }
        item {
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
                    Overline(stringResource(R.string.sleep_planner_haptic))
                    PlannerToggle(stringResource(R.string.sleep_planner_enable), draftEnabled) { draftEnabled = it }
                    for ((mode, label) in listOf("exact" to R.string.sleep_planner_exact, "sleepGoal" to R.string.sleep_planner_goal_mode, "recovery" to R.string.sleep_planner_recovery_mode)) {
                        NoopButton(text = stringResource(label), kind = if (draftMode == mode) NoopButtonKind.Primary else NoopButtonKind.Secondary, fullWidth = true, modifier = Modifier.semantics { selected = draftMode == mode }, onClick = { draftMode = mode })
                    }
                    if (draftMode != "exact") Text(stringResource(R.string.sleep_planner_adaptive_unavailable), style = NoopType.footnote, color = Palette.textSecondary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(if (draftMode == "exact") R.string.sleep_planner_exact_time else R.string.sleep_planner_latest_time), style = NoopType.body, color = Palette.textPrimary)
                        Spacer(Modifier.weight(1f))
                        TimeChip(draftWake, stringResource(R.string.sleep_planner_wake)) { draftWake = it }
                    }
                    AlarmWeekdayPicker(draftWeekdays) { draftWeekdays = toggledSmartAlarmWeekday(it, draftWeekdays) }
                    NoopButton(text = stringResource(R.string.sleep_planner_day_plan), kind = NoopButtonKind.Secondary, fullWidth = true, onClick = { showDays = !showDays })
                    if (showDays) {
                        Text(stringResource(R.string.sleep_planner_default_goal), style = NoopType.footnote, color = Palette.textSecondary)
                        GoalChoices(settings.goalPercent) { vm.setSleepPlannerSettings(settings.copy(goalPercent = it)) }
                        for (day in SMART_ALARM_WEEKDAY_ORDER.filter { smartAlarmWeekdayIsSelected(it, draftWeekdays) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(smartAlarmWeekdayName(day), style = NoopType.body, color = Palette.textPrimary)
                                    Spacer(Modifier.weight(1f))
                                    TimeChip(draftOverrides[day] ?: draftWake, smartAlarmWeekdayName(day)) { minute -> draftOverrides = draftOverrides + (day to minute) }
                                }
                                GoalChoices(settings.goals[day] ?: settings.goalPercent) { goal -> vm.setSleepPlannerSettings(settings.copy(goals = settings.goals + (day to goal))) }
                                if (day in draftOverrides || day in settings.goals) NoopButton(
                                    text = stringResource(R.string.sleep_planner_use_default), kind = NoopButtonKind.Secondary,
                                    onClick = {
                                        draftOverrides = draftOverrides - day
                                        vm.setSleepPlannerSettings(settings.copy(goals = settings.goals - day))
                                    },
                                )
                            }
                        }
                    }
                    NoopButton(text = stringResource(R.string.sleep_planner_save), fullWidth = true, onClick = {
                        result = vm.saveSleepPlannerAlarm(draftEnabled, draftWake, draftWeekdays, draftOverrides, draftMode)
                    })
                    Text(stringResource(when (snapshot.status) {
                        "reported" -> R.string.sleep_planner_reported
                        "sent" -> R.string.sleep_planner_sent
                        "off" -> R.string.sleep_planner_off
                        else -> R.string.sleep_planner_local
                    }), style = NoopType.footnote, color = Palette.textSecondary)
                    if (snapshot.epochSec != null) PlannerFigure(
                        stringResource(R.string.sleep_planner_saved_deadline),
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(snapshot.wake.time),
                    )
                    snapshot.countdownMinutes?.let { Text(stringResource(R.string.sleep_planner_countdown, durationMinutes(it.toInt())), style = NoopType.footnote, color = Palette.textSecondary) }
                    result?.let {
                        Text(stringResource(when (it) {
                            "reconnect" -> R.string.sleep_planner_reconnect_error
                            "unsupported" -> R.string.sleep_planner_unsupported
                            "permission" -> R.string.sleep_planner_permission
                            "sent" -> R.string.sleep_planner_sent
                            "cancelRequested" -> R.string.sleep_planner_cancel_requested
                            "requested" -> R.string.sleep_planner_update_requested
                            "alreadySkipped" -> R.string.sleep_planner_skip_saved
                            else -> R.string.sleep_planner_send_error
                        }), style = NoopType.footnote, color = if (it == "sent") Palette.textSecondary else Palette.statusWarning)
                    }
                    if (!live.connected || !live.encryptedBond) NoopButton(text = stringResource(R.string.sleep_planner_reconnect), kind = NoopButtonKind.Secondary, fullWidth = true, onClick = { vm.connect() })
                    if (enabled && !skippedPending) {
                        NoopButton(text = stringResource(R.string.sleep_planner_skip), kind = NoopButtonKind.Secondary, fullWidth = true, onClick = { result = vm.skipNextSleepPlannerAlarm(nowMs) })
                        if (snapshot.status == "reported" && (snapshot.countdownMinutes ?: Long.MAX_VALUE) in 1L..60L) {
                            NoopButton(text = stringResource(R.string.sleep_planner_awake), kind = NoopButtonKind.Secondary, fullWidth = true, onClick = { result = vm.skipNextSleepPlannerAlarm(nowMs) })
                        }
                    }
                    if (skippedPending) {
                        Text(stringResource(R.string.sleep_planner_skipped, settings.skippedOccurrence.substringBefore('|')), style = NoopType.footnote, color = Palette.textSecondary)
                        NoopButton(text = stringResource(R.string.sleep_planner_restore), kind = NoopButtonKind.Secondary, fullWidth = true, onClick = { result = vm.restoreSleepPlannerOccurrence() })
                    }
                    if (enabled && live.batteryPct?.let { it < 20 } == true) Text(stringResource(R.string.sleep_planner_strap_battery), style = NoopType.footnote, color = Palette.statusWarning)
                    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                    if (enabled && scale > 0 && level >= 0 && level * 100 / scale <= 20) Text(stringResource(R.string.sleep_planner_phone_battery), style = NoopType.footnote, color = Palette.statusWarning)
                    Text(stringResource(R.string.sleep_planner_backup), style = NoopType.footnote, color = Palette.textTertiary)
                }
            }
        }
        item {
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                    Overline(stringResource(R.string.sleep_planner_reminders))
                    PlannerToggle(stringResource(R.string.sleep_planner_wind_down), windDown) { wanted ->
                        if (wanted && !WindDownScheduler.notificationsAllowed(context)) {
                            if (Build.VERSION.SDK_INT >= 33) {
                                requestingDebtPermission = false
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            else result = "permission"
                        } else vm.setWindDownEnabled(wanted)
                    }
                    PlannerFigure(stringResource(R.string.sleep_planner_reminder_time), plannerClock(snapshot.reminder, snapshot.wake))
                    PlannerToggle(stringResource(R.string.sleep_planner_debt_reminder), settings.debtReminderEnabled) { wanted ->
                        if (wanted && !WindDownScheduler.notificationsAllowed(context)) {
                            if (Build.VERSION.SDK_INT >= 33) {
                                requestingDebtPermission = true
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else result = "permission"
                        } else vm.setSleepPlannerSettings(settings.copy(debtReminderEnabled = wanted))
                    }
                    Text(stringResource(R.string.sleep_planner_reminder_help), style = NoopType.footnote, color = Palette.textSecondary)
                    if (!WindDownScheduler.notificationsAllowed(context)) {
                        Text(stringResource(R.string.sleep_planner_permission), style = NoopType.footnote, color = Palette.statusWarning)
                        NoopButton(text = stringResource(R.string.sleep_planner_notification_settings), kind = NoopButtonKind.Secondary, fullWidth = true, onClick = {
                            context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
                        })
                    }
                }
            }
        }
        item { NoopButton(text = stringResource(R.string.sleep_planner_phone_alarm), kind = NoopButtonKind.Secondary, fullWidth = true, onClick = { showPhoneAlarm = true }) }
    }
}

@Composable
private fun GoalChoices(selectedPercent: Int, onSelect: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        for ((percent, label) in listOf(100 to R.string.sleep_planner_peak, 85 to R.string.sleep_planner_perform, 70 to R.string.sleep_planner_get_by)) {
            NoopButton(text = stringResource(label), kind = if (selectedPercent == percent) NoopButtonKind.Primary else NoopButtonKind.Secondary, modifier = Modifier.weight(1f).semantics { this.selected = selectedPercent == percent }, onClick = { onSelect(percent) })
        }
    }
}

@Composable
private fun PlannerToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = NoopType.body, color = Palette.textPrimary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(
            checkedThumbColor = Palette.surfaceBase, checkedTrackColor = Palette.accent,
            uncheckedThumbColor = Palette.textTertiary, uncheckedTrackColor = Palette.surfaceInset,
            uncheckedBorderColor = Palette.hairline,
        ))
    }
}

@Composable
private fun PlannerFigure(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.space12)) {
        Text(label, style = NoopType.body, color = Palette.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = NoopType.headline, color = Palette.textPrimary)
    }
}

@Composable
private fun plannerClock(clock: Calendar, wake: Calendar): String {
    val wakeMidnight = (wake.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return "%02d:%02d".format(clock.get(Calendar.HOUR_OF_DAY), clock.get(Calendar.MINUTE)) +
        if (clock.timeInMillis < wakeMidnight.timeInMillis) " · " + stringResource(R.string.sleep_planner_previous_day) else ""
}

private fun durationMinutes(minutes: Int): String = durationText(minutes.toDouble())
