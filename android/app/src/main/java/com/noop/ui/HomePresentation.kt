package com.noop.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import com.noop.R
import com.noop.data.DailyMetric
import com.noop.data.WorkoutRow
import java.time.LocalDate
import java.time.ZoneId
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
        )
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space4)) {
            Icon(Icons.Filled.LocalFireDepartment, null, tint = Palette.textSecondary,
                modifier = Modifier.size(Metrics.iconSmall))
            Text(uiPlural(R.plurals.settings_streak_run, streak, streak), style = NoopType.captionNumber, color = Palette.textSecondary)
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
    Row(Modifier.fillMaxWidth().padding(vertical = Metrics.space16),
        horizontalArrangement = Arrangement.spacedBy(Metrics.space8), verticalAlignment = Alignment.Top) {
        HomeDial(uiString(R.string.home_sleep), sleep?.roundToInt()?.toString(), "%", sleep,
            Palette.sleepPrimary, Modifier.weight(1f), onSleep)
        HomeDial(uiString(R.string.home_recovery), recovery?.roundToInt()?.toString(), "%", recovery,
            recovery?.let { Palette.recoveryColor(it) } ?: Palette.recoveryHigh, Modifier.weight(1f), onRecovery)
        HomeDial(uiString(R.string.home_strain), strain?.let { UnitFormatter.effortDisplay(it, EffortScale.WHOOP) }, "", strain,
            Palette.strainPrimary, Modifier.weight(1f), onStrain)
    }
}

@Composable
private fun HomeDial(label: String, value: String?, unit: String, progress: Double?, color: Color,
    modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        ScoreDial(label = uiString(R.string.home_dial_label, label), value = value ?: uiString(R.string.home_no_value),
            unit = if (value != null) unit else "", progress = progress?.div(100)?.toFloat(),
            color = color, size = ScoreDialSize.Compact)
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
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
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
internal fun HomeDayHeader(dayLabel: String, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TrackedSectionHeader(uiString(R.string.home_my_day), microLabel = dayLabel, modifier = Modifier.weight(1f))
        IconButton(onClick = onAdd, modifier = Modifier.size(Metrics.iconButton)) {
            Icon(Icons.Filled.Add, uiString(R.string.home_add_activity), tint = Palette.textPrimary)
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
            NoopCard(Modifier.clickable(onClick = { onWorkout(workout) })) {
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
                    workout.strain?.let {
                        Text(UnitFormatter.effortDisplay(it, EffortScale.WHOOP), style = NoopType.title2, color = Palette.strainPrimary)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.textSecondary,
                        modifier = Modifier.size(Metrics.iconSmall))
                }
            }
        }
        if (workouts.isEmpty()) Text(uiString(R.string.home_no_activities), style = NoopType.caption, color = Palette.textSecondary)
    }
}

@Composable
internal fun HomePlanSummary(onOpen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
        TrackedSectionHeader(uiString(R.string.home_my_plan))
        NoopCard(Modifier.clickable(onClick = onOpen)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                    Text(uiString(R.string.home_weekly_plan), style = NoopType.headline, color = Palette.textPrimary)
                    Text(uiString(R.string.home_plan_summary), style = NoopType.body, color = Palette.textSecondary)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.textPrimary,
                    modifier = Modifier.size(Metrics.iconSmall))
            }
        }
    }
}
