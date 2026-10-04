package com.noop.ui

import android.app.DatePickerDialog
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.noop.R
import com.noop.data.DailyMetric
import com.noop.data.WorkoutRow
import com.noop.data.WeeklyPlanCalendar
import com.noop.data.WeeklyPlanDay
import com.noop.data.WeeklyPlanEngine
import com.noop.data.WeeklyPlanJournalDay
import com.noop.data.WeeklyPlanPreferences
import com.noop.data.WeeklyPlanProgress
import com.noop.data.WeeklyPlanSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

internal enum class HomeRecordingState { Disconnected, Scanning, Backfill, Capturing, CapturingExperimental, ExperimentalHistory, ConnectedNoData, Idle }

internal fun homeRecordingState(
    connected: Boolean,
    scanning: Boolean,
    backfilling: Boolean,
    hrStreaming: Boolean,
    hasSessionHistory: Boolean,
    historySyncExperimental: Boolean = false,
): HomeRecordingState = when {
    !connected && scanning -> HomeRecordingState.Scanning
    !connected -> HomeRecordingState.Disconnected
    backfilling -> HomeRecordingState.Backfill
    hrStreaming && historySyncExperimental -> HomeRecordingState.CapturingExperimental
    hrStreaming -> HomeRecordingState.Capturing
    historySyncExperimental -> HomeRecordingState.ExperimentalHistory
    !hasSessionHistory -> HomeRecordingState.ConnectedNoData
    else -> HomeRecordingState.Idle
}

internal fun homeManualActivityEnd(dayKey: String, now: ZonedDateTime = ZonedDateTime.now()): Long =
    LocalDate.parse(dayKey).atTime(now.toLocalTime()).atZone(now.zone)
        .toInstant().toEpochMilli().coerceAtMost(now.toInstant().toEpochMilli())

internal fun homeActivityWindow(dayKey: String, zone: ZoneId = ZoneId.systemDefault()): LongRange {
    val date = LocalDate.parse(dayKey)
    return date.atStartOfDay(zone).toEpochSecond()..(date.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1)
}

@Composable
internal fun HomeChrome(
    day: LocalDate,
    anchor: LocalDate,
    dateLabel: String,
    offset: Int,
    streak: Int,
    battery: HeaderBatteryDisplay.State,
    connected: Boolean,
    onPick: (Int) -> Unit,
    onProfile: () -> Unit,
    onDevices: () -> Unit,
    recordingState: HomeRecordingState? = null,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        TopChrome(
            dateLabel = dateLabel,
            previousLabel = uiString(R.string.home_previous_day),
            nextLabel = uiString(R.string.home_next_day),
            profileLabel = uiString(R.string.home_profile),
            strapLabel = uiString(if (connected) R.string.home_device_connected else R.string.home_device_disconnected),
            avatarInitials = uiString(R.string.home_avatar),
            batteryPercent = (battery as? HeaderBatteryDisplay.State.Charge)?.pct?.roundToInt(),
            isConnected = connected,
            canGoNext = offset > 0,
            onPrevious = { onPick(offset + 1) },
            onNext = { onPick((offset - 1).coerceAtLeast(0)) },
            onDate = {
                DatePickerDialog(context, { _, year, month, date ->
                    val picked = LocalDate.of(year, month + 1, date)
                    onPick(ChronoUnit.DAYS.between(picked, anchor).toInt().coerceAtLeast(0))
                }, day.year, day.monthValue - 1, day.dayOfMonth).apply {
                    datePicker.maxDate = anchor.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.show()
            },
            onProfile = onProfile,
            onStrap = onDevices,
            streakCount = streak,
            streakLabel = uiPlural(R.plurals.settings_streak_run, streak, streak),
        )
        recordingState?.let { state ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { HomeRecordingStatus(state) }
        }
    }
}

@Composable
internal fun HomeDials(
    sleep: Double?,
    recovery: Double?,
    strain: Double?,
    onSleep: () -> Unit,
    onRecovery: () -> Unit,
    onStrain: () -> Unit,
) {
    val availableRecovery = homeScoreValue(recovery)
    val availableStrain = homeScoreValue(strain)
    val sleepValue = homeScoreValue(sleep)
    Row(Modifier.fillMaxWidth().padding(vertical = Metrics.space16),
        horizontalArrangement = Arrangement.spacedBy(Metrics.space8), verticalAlignment = Alignment.Top) {
        HomeDial(uiString(R.string.home_sleep), sleepValue?.roundToInt()?.toString(), "%", sleepValue,
            Palette.sleepPrimary, Modifier.weight(1f), onSleep)
        HomeDial(uiString(R.string.home_recovery), RecoveryStrainDetailLogic.recoveryPercent(availableRecovery)?.toString(), "%", availableRecovery,
            availableRecovery?.let { Palette.recoveryColor(it) } ?: Palette.ringTrack, Modifier.weight(1f), onRecovery)
        HomeDial(uiString(R.string.home_strain), availableStrain?.let { UnitFormatter.effortDisplay(it, EffortScale.WHOOP) }, "", availableStrain,
            Palette.strainPrimary, Modifier.weight(1f), onStrain)
    }
}

@Composable
private fun HomeDial(label: String, value: String?, unit: String, progress: Double?, color: Color,
    modifier: Modifier, onClick: () -> Unit) {
    val displayValue = value ?: uiString(R.string.home_no_value)
    val displayUnit = if (value != null) unit else ""
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        ScoreDial(label = uiString(R.string.home_dial_label, label), value = displayValue,
            unit = displayUnit, progress = progress?.div(100)?.toFloat(),
            color = color, size = ScoreDialSize.Compact,
            viewportWidth = LocalConfiguration.current.screenWidthDp.dp,
            accessibilityLabel = listOf(label, displayValue + displayUnit).joinToString(", "))
    }
}

@Composable
internal fun HomeRecordingStatus(state: HomeRecordingState) {
    val label = when (state) {
        HomeRecordingState.Disconnected -> R.string.home_status_disconnected
        HomeRecordingState.Scanning -> R.string.home_status_scanning
        HomeRecordingState.Backfill -> R.string.home_status_backfill
        HomeRecordingState.Capturing -> R.string.home_status_capture
        HomeRecordingState.CapturingExperimental -> R.string.home_status_capture_experimental
        HomeRecordingState.ExperimentalHistory -> R.string.recording_chip_detail_history_experimental
        HomeRecordingState.ConnectedNoData -> R.string.home_status_connected_no_data
        HomeRecordingState.Idle -> R.string.home_status_idle
    }
    StatusPill(uiString(label), Icons.Filled.Bluetooth,
        color = if (state == HomeRecordingState.Disconnected || state == HomeRecordingState.ConnectedNoData)
            Palette.textSecondary else Palette.positive)
}

@Composable
internal fun HomeGuidance(title: String, detail: String, onCoach: (() -> Unit)?) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    Box(Modifier.fillMaxWidth().padding(bottom = Metrics.space8)) {
        Box(Modifier.matchParentSize().padding(horizontal = Metrics.space16)
            .offset(y = Metrics.space8).background(Palette.surfaceOverlay, shape))
        Box(Modifier.matchParentSize().padding(horizontal = Metrics.space8)
            .offset(y = Metrics.space4).background(Palette.surfaceRaised, shape))
        Column(Modifier.fillMaxWidth().background(Palette.surfaceRaised, shape).padding(Metrics.cardPadding),
            verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = NoopType.headline, color = Palette.textPrimary, modifier = Modifier.weight(1f))
                if (onCoach != null) IconButton(onClick = onCoach, modifier = Modifier.size(Metrics.iconButton)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, uiString(R.string.nav_coach), tint = Palette.textPrimary)
                }
            }
            Text(detail, style = NoopType.body, color = Palette.textSecondary)
        }
    }
}

@Composable
internal fun HomeMonitorTiles(available: Int, stress: Double?, onHealth: () -> Unit, onStress: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.space12)) {
        MetricCard(label = uiString(R.string.home_health_monitor), value = uiString(R.string.home_metrics_count, available),
            detail = uiString(R.string.home_metrics_available), icon = Icons.Filled.MonitorHeart,
            color = if (available > 0) Palette.positive else Palette.textSecondary,
            modifier = Modifier.weight(1f).clickable(onClick = onHealth))
        MetricCard(label = uiString(R.string.home_stress_monitor),
            value = stress?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: uiString(R.string.home_no_value),
            detail = uiString(if (stress == null) R.string.home_stress_unavailable else R.string.home_stress_local),
            icon = Icons.Filled.TrackChanges, color = Palette.stressMedium,
            modifier = Modifier.weight(1f).clickable(onClick = onStress))
    }
}

@Composable
internal fun HomeDayHeader(dayLabel: String, onAdd: () -> Unit, onStart: (() -> Unit)? = null, startEnabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TrackedSectionHeader(uiString(R.string.home_my_day), microLabel = dayLabel, modifier = Modifier.weight(1f))
        Box {
            IconButton(onClick = { expanded = true }, modifier = Modifier.size(Metrics.iconButton)) {
                Icon(Icons.Filled.Add, uiString(R.string.home_add_activity), tint = Palette.textPrimary)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text(uiString(R.string.home_add_activity), style = NoopType.body) },
                    onClick = { expanded = false; onAdd() })
                if (onStart != null) DropdownMenuItem(
                    text = { Text(uiString(R.string.action_start_workout), style = NoopType.body) },
                    enabled = startEnabled,
                    onClick = { expanded = false; onStart() },
                )
            }
        }
    }
}

@Composable
internal fun HomeDayEvents(day: DailyMetric?, workouts: List<WorkoutRow>, onSleep: () -> Unit, onWorkout: (WorkoutRow) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        NoopCard(Modifier.clickable(onClick = onSleep)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                Icon(Icons.Filled.Bedtime, null, tint = Palette.sleepPrimary, modifier = Modifier.size(Metrics.space24))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                    Text(uiString(R.string.home_sleep), style = NoopType.headline, color = Palette.textPrimary)
                    Text(day?.totalSleepMin?.let { minutes ->
                        val total = minutes.roundToInt()
                        uiString(R.string.home_sleep_duration, total / 60, total % 60)
                    } ?: uiString(R.string.home_no_sleep_yet), style = NoopType.caption, color = Palette.textSecondary)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.textSecondary,
                    modifier = Modifier.size(Metrics.iconSmall))
            }
        }
        workouts.forEach { workout ->
            NoopCard(Modifier.clickable(onClickLabel = uiString(R.string.today_action_show_workout), role = Role.Button, onClick = { onWorkout(workout) })) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                        Text(WorkoutEditing.displaySport(workout.sport), style = NoopType.headline, color = Palette.textPrimary)
                        val start = java.time.Instant.ofEpochSecond(workout.startTs).atZone(ZoneId.systemDefault())
                        val end = java.time.Instant.ofEpochSecond(workout.endTs).atZone(ZoneId.systemDefault())
                        val format = DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(Locale.getDefault())
                        Text(uiString(R.string.home_activity_time, start.format(format), end.format(format)),
                            style = NoopType.caption, color = Palette.textSecondary)
                    }
                    Text(workout.strain?.takeIf { it.isFinite() && it in 0.0..100.0 }
                        ?.let { UnitFormatter.effortDisplay(it, EffortScale.WHOOP) } ?: uiString(R.string.home_no_value),
                        style = NoopType.title2, color = Palette.strainPrimary)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.textSecondary,
                        modifier = Modifier.size(Metrics.iconSmall))
                }
            }
        }
        if (workouts.isEmpty()) Text(uiString(R.string.home_no_activities), style = NoopType.caption, color = Palette.textSecondary)
    }
}

@Composable
internal fun HomePlanSummary(vm: AppViewModel, onOpen: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { WeeklyPlanPreferences(context) }
    val nativePreferences = remember(context) { NoopPrefs.of(context) }
    val daysRevision by vm.recentDays.collectAsStateWithLifecycle()
    val journalRevision by vm.repo.journalRevision.collectAsStateWithLifecycle()
    val activeStrap by vm.activeStrapIdFlow.collectAsStateWithLifecycle()
    val activeStrapId = effectiveActiveStrapId(activeStrap, vm.deviceId)
    var preferencesRevision by remember { mutableIntStateOf(0) }
    var resumeRevision by remember { mutableIntStateOf(0) }
    var today by remember { mutableStateOf(LocalDate.now().toString()) }
    var snapshot by remember { mutableStateOf<WeeklyPlanSnapshot?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(nativePreferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith("noop.weeklyPlan.") == true) preferencesRevision++
        }
        nativePreferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { nativePreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            resumeRevision++
            while (true) {
                today = LocalDate.now().toString()
                delay(60_000)
            }
        }
    }
    val weekStart = WeeklyPlanCalendar.weekStart(today) ?: today
    val hasPlan = preferences.hasPlan(weekStart)
    LaunchedEffect(daysRevision, journalRevision, activeStrapId, preferencesRevision, resumeRevision, today, weekStart) {
        val requestToday = today
        val requestWeek = weekStart
        val requestStrap = activeStrapId
        snapshot = null
        if (!preferences.hasPlan(requestWeek)) return@LaunchedEffect
        val loaded = runCatching {
            val days = vm.repo.daysMerged(requestStrap).map { WeeklyPlanDay(it.day, it.totalSleepMin, it.strain) }
            currentCoroutineContext().ensureActive()
            val imported = vm.repo.importedSourceIds(requestStrap).flatMap { vm.repo.journal(it, requestWeek, requestToday) }
            currentCoroutineContext().ensureActive()
            val native = vm.repo.journal(JOURNAL_DEVICE_ID, requestWeek, requestToday)
            currentCoroutineContext().ensureActive()
            val journal = mergeJournalEntries(imported, native).map { WeeklyPlanJournalDay(it.day, it.question, it.answeredYes) }
            WeeklyPlanEngine.snapshot(preferences.goals(requestWeek), requestWeek, requestToday, days, journal)
        }.getOrElse {
            if (it is CancellationException) throw it
            null
        }
        currentCoroutineContext().ensureActive()
        snapshot = loaded
    }
    val current = snapshot?.takeIf { hasPlan && it.weekStart == weekStart }
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
        TrackedSectionHeader(uiString(R.string.home_my_plan))
        NoopCard(Modifier.clickable(onClick = onOpen)) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                        Text(uiString(R.string.weekly_plan_title), style = NoopType.headline, color = Palette.textPrimary)
                        Text(uiString(R.string.weekly_plan_week_format, weekStart, WeeklyPlanCalendar.adding(6, weekStart) ?: weekStart),
                            style = NoopType.captionNumber, color = Palette.textSecondary)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.textPrimary,
                        modifier = Modifier.size(Metrics.iconSmall))
                }
                if (hasPlan) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(uiString(R.string.weekly_plan_overall), style = NoopType.body, color = Palette.textSecondary)
                        Text(current?.overallPercent?.let { uiString(R.string.weekly_plan_percent, it) } ?: "—",
                            style = NoopType.headline, color = Palette.textPrimary)
                    }
                    HomePlanProgress(R.string.weekly_plan_sleep, current?.sleep)
                    HomePlanProgress(R.string.weekly_plan_strain, current?.strain)
                    HomePlanProgress(R.string.weekly_plan_journal, current?.journal)
                } else {
                    Text(uiString(R.string.weekly_plan_no_saved), style = NoopType.body, color = Palette.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun HomePlanProgress(label: Int, progress: WeeklyPlanProgress?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(uiString(label), style = NoopType.caption, color = Palette.textSecondary)
        Text(if (progress?.percent != null) uiString(R.string.weekly_plan_days_progress, progress.completedDays, progress.targetDays) else "—",
            style = NoopType.captionNumber, color = Palette.textPrimary)
    }
}
