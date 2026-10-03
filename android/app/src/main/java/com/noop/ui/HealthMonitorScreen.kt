package com.noop.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.HealthMonitorAssessment
import com.noop.analytics.Baselines
import com.noop.analytics.SkinTempDisplay
import com.noop.analytics.VitalBands
import com.noop.data.DailyMetric
import java.time.LocalDate
import java.time.ZonedDateTime

@Composable
fun HealthMonitorScreen(
    vm: AppViewModel,
    onVitalClick: (String) -> Unit = {},
    onOpenLiveHr: () -> Unit = {},
) {
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val day = rememberHealthMonitorDay(days)
    val readings = rememberHealthMonitorReadings(vm, days, day)
    val context = androidx.compose.ui.platform.LocalContext.current
    val skinPreference = UnitPrefs.skinTempPreferred(context)
    val skinKind = readings.last().vital.value?.let {
        if (VitalBands.isAbsoluteSkinTemp(it)) SkinTempDisplay.Kind.ABSOLUTE else SkinTempDisplay.Kind.DEVIATION
    } ?: skinPreference
    val reliability by vm.hrvReliabilityByDay.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    var reportDays by remember { mutableStateOf(30) }
    val report = remember(days, day, reportDays, skinKind, reliability) {
        healthMonitorReport(days, LocalDate.parse(day), reportDays, skinKind, reliability)
    }
    LazyScreenScaffold(
        title = stringResource(R.string.health_monitor_title),
        subtitle = stringResource(R.string.health_monitor_as_of, day),
    ) {
        item { HealthMonitorSummary(readings) }
        item {
            Text(stringResource(R.string.health_monitor_baseline_note), style = NoopType.caption,
                color = Palette.textSecondary)
        }
        readings.forEach { reading ->
            item(key = reading.vital.key) { HealthMonitorVitalCard(reading) { onVitalClick(reading.vital.key) } }
        }
        item { HealthMonitorLiveHr(vm, onOpenLiveHr) }
        item { HealthMonitorAlerts(vm) }
        item {
            HealthMonitorReportCard(report, reportDays, skinKind, healthMonitorRecoveryCount(days, day), sharing, onSelect = { reportDays = it },
                onShare = {
                    sharing = true
                    scope.launch {
                        try { HealthMonitorReportShare.export(context, report, skinKind) }
                        finally { sharing = false }
                    }
                })
        }
        item {
            Text(stringResource(R.string.health_monitor_wellness_note), style = NoopType.footnote,
                color = Palette.textTertiary)
        }
    }
}

@Composable
private fun rememberHealthMonitorDay(days: List<DailyMetric>): String {
    val now by produceState(initialValue = ZonedDateTime.now()) {
        while (true) {
            value = ZonedDateTime.now()
            delay(60_000)
        }
    }
    return healthMonitorDay(days, now)
}

@Composable
private fun rememberHealthMonitorReadings(vm: AppViewModel, days: List<DailyMetric>, day: String): List<HealthMonitorReading> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val tempUnit = UnitPrefs.temperature(context)
    val skinPreference = UnitPrefs.skinTempPreferred(context)
    val reliability by vm.hrvReliabilityByDay.collectAsStateWithLifecycle()
    val hrvEpoch = NoopPrefs.of(context).getLong(Baselines.hrvBaselineEpochKey, 0L)
    val recoveryEpoch = NoopPrefs.of(context).getLong(Baselines.recoveryBaselineEpochKey, 0L)
    return remember(day, days, tempUnit, skinPreference, hrvEpoch, recoveryEpoch, reliability) {
        healthMonitorReadings(day, days, tempUnit, skinPreference, hrvEpoch, recoveryEpoch, reliability)
    }
}

@Composable
internal fun HealthMonitorPreview(vm: AppViewModel, days: List<DailyMetric>, onClick: () -> Unit) {
    val day = rememberHealthMonitorDay(days)
    val readings = rememberHealthMonitorReadings(vm, days, day)
    NoopCard(Modifier.clickable(role = Role.Button, onClick = onClick)) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            HealthFeatureHeader(stringResource(R.string.health_monitor_title))
            HealthMonitorSummary(readings, insideCard = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                readings.forEach { reading ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                        Icon(statusIcon(reading.assessment.status), statusLabel(reading.assessment.status),
                            tint = statusColor(reading.assessment.status), modifier = Modifier.size(Metrics.iconSmall))
                        Text(stringResource(monitorPreviewLabel(reading.vital.key)), style = NoopType.footnote,
                            color = Palette.textSecondary)
                    }
                }
            }
            Text(stringResource(R.string.health_monitor_as_of, day), style = NoopType.footnote,
                color = Palette.textTertiary)
        }
    }
}

@Composable
private fun HealthMonitorSummary(readings: List<HealthMonitorReading>, insideCard: Boolean = false) {
    val count = readings.count { it.assessment.status == HealthMonitorAssessment.Status.WITHIN_RANGE }
    val compared = readings.count { it.assessment.status in monitorComparedStatuses }
    val color = when {
        readings.any { it.assessment.status == HealthMonitorAssessment.Status.FAR_OUTSIDE_RANGE } -> Palette.statusCritical
        readings.any { it.assessment.status == HealthMonitorAssessment.Status.OUTSIDE_RANGE } -> Palette.statusWarning
        compared == 5 -> Palette.statusPositive
        else -> Palette.textSecondary
    }
    val content: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
            Text(stringResource(R.string.health_monitor_range_count, count), style = NoopType.title1, color = color)
            Text(stringResource(R.string.health_monitor_compared_count, compared), style = NoopType.caption,
                color = Palette.textSecondary)
        }
    }
    if (insideCard) content() else NoopCard { content() }
}

internal val monitorComparedStatuses = setOf(HealthMonitorAssessment.Status.WITHIN_RANGE,
    HealthMonitorAssessment.Status.OUTSIDE_RANGE, HealthMonitorAssessment.Status.FAR_OUTSIDE_RANGE)

@Composable
private fun HealthMonitorVitalCard(reading: HealthMonitorReading, onClick: () -> Unit) {
    val vital = reading.vital
    val result = reading.assessment
    val displayValue = healthMonitorDisplayValue(vital.key, vital.value)
    NoopCard(Modifier.clickable(role = Role.Button, onClick = onClick)) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            ContributorRow(label = vital.label, value = displayValue?.let(vital.format) ?: "—",
                unit = if (displayValue != null) vital.unit else "",
                comparisonIcon = Icons.AutoMirrored.Filled.KeyboardArrowRight)
            StatusPill(statusLabel(result.status), statusIcon(result.status), statusColor(result.status))
            val lower = result.lower
            val upper = result.upper
            if (lower != null && upper != null) {
                Text(stringResource(R.string.health_monitor_personal_range, vital.format(lower), vital.format(upper), vital.unit),
                    style = NoopType.caption, color = Palette.textSecondary)
            } else if (result.status == HealthMonitorAssessment.Status.CALIBRATING) {
                Text(stringResource(R.string.health_monitor_baseline_progress, result.nights),
                    style = NoopType.caption, color = Palette.textSecondary)
            }
            if (result.status == HealthMonitorAssessment.Status.UNAVAILABLE) {
                Text(stringResource(monitorMissingLabel(vital.key)), style = NoopType.caption, color = Palette.textSecondary)
            }
            if (result.status == HealthMonitorAssessment.Status.UNVERIFIED) {
                Text(stringResource(R.string.health_monitor_value_unverified),
                    style = NoopType.caption, color = Palette.textSecondary)
            }
            vital.secondary?.let { Text(it, style = NoopType.footnote, color = Palette.textTertiary) }
        }
    }
}

@Composable
private fun HealthMonitorLiveHr(vm: AppViewModel, onClick: () -> Unit) {
    val live by vm.live.collectAsStateWithLifecycle()
    val bpm by vm.bpm.collectAsStateWithLifecycle()
    val value = if (live.connected) displayHr(bpm, live)?.takeIf { it in 30..220 } else null
    NoopCard(Modifier.clickable(role = Role.Button, onClick = onClick)) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
            HealthFeatureHeader(stringResource(R.string.health_monitor_live_hr))
            ContributorRow(stringResource(R.string.health_monitor_last_live), value?.toString() ?: "—",
                if (value == null) "" else "bpm", Icons.Filled.Favorite)
            Text(stringResource(if (live.connected) R.string.health_monitor_live_passive else R.string.health_monitor_disconnected),
                style = NoopType.caption, color = Palette.textSecondary)
        }
    }
}

@Composable
private fun HealthMonitorAlerts(vm: AppViewModel) {
    val enabled by vm.illnessWatchEnabled.collectAsStateWithLifecycle()
    val alert by vm.healthAlert.collectAsStateWithLifecycle()
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                Text(stringResource(R.string.health_monitor_alerts_title), style = NoopType.headline,
                    color = Palette.textPrimary, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = vm::setIllnessWatchEnabled,
                    colors = SwitchDefaults.colors(checkedThumbColor = Palette.surfaceBase,
                        checkedTrackColor = Palette.accent, uncheckedThumbColor = Palette.textSecondary,
                        uncheckedTrackColor = Palette.surfaceInset, uncheckedBorderColor = Palette.hairline))
            }
            Text(stringResource(R.string.health_monitor_alerts_detail), style = NoopType.caption,
                color = Palette.textSecondary)
            if (enabled && alert != null) StatusPill(stringResource(R.string.health_monitor_multi_signal_alert),
                Icons.Filled.ErrorOutline, Palette.statusWarning)
        }
    }
}

@Composable
internal fun HealthspanLandingCard(onClick: () -> Unit) {
    NoopCard(Modifier.clickable(role = Role.Button, onClick = onClick)) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            HealthFeatureHeader(stringResource(R.string.health_healthspan_title))
            Text(stringResource(R.string.health_healthspan_heading), style = NoopType.title2,
                color = Palette.statusPositive)
            Text(stringResource(R.string.health_healthspan_preview), style = NoopType.caption,
                color = Palette.textSecondary)
            StatusPill(stringResource(R.string.health_healthspan_local_estimates), Icons.Filled.Info, Palette.textSecondary)
        }
    }
}

@Composable
internal fun HealthFeatureCard(title: String, detail: String, color: Color, onClick: () -> Unit) {
    NoopCard(Modifier.clickable(role = Role.Button, onClick = onClick)) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
            HealthFeatureHeader(title)
            Text(detail, style = NoopType.caption, color = color)
        }
    }
}

@Composable
private fun HealthFeatureHeader(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = NoopType.headline, color = Palette.textPrimary, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.textSecondary,
            modifier = Modifier.size(Metrics.iconSmall))
    }
}

@Composable
private fun HealthMonitorReportCard(report: HealthMonitorReport, windowDays: Int, skinKind: SkinTempDisplay.Kind, recordedRecoveries: Int, sharing: Boolean,
    onSelect: (Int) -> Unit, onShare: () -> Unit) {
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            TrackedSectionHeader(stringResource(R.string.health_report_title))
            SegmentedPillControl(listOf(30, 180), windowDays,
                label = { uiString(R.string.health_report_days, it) }, onSelect = onSelect)
            Text(stringResource(R.string.health_report_window, report.start, report.end),
                style = NoopType.caption, color = Palette.textSecondary)
            Text(stringResource(R.string.health_report_preview), style = NoopType.caption, color = Palette.textSecondary)
            Text(stringResource(if (skinKind == SkinTempDisplay.Kind.ABSOLUTE) R.string.health_report_temp_absolute else R.string.health_report_temp_delta),
                style = NoopType.footnote, color = Palette.textTertiary)
            if (recordedRecoveries < 14) Text(stringResource(R.string.health_report_not_ready, recordedRecoveries),
                style = NoopType.caption, color = Palette.textSecondary)
            TextButton(onClick = onShare, enabled = recordedRecoveries >= 14 && !sharing) {
                Text(stringResource(R.string.health_report_share), color = if (recordedRecoveries >= 14) Palette.accent else Palette.textTertiary)
            }
        }
    }
}

@Composable
private fun statusLabel(status: HealthMonitorAssessment.Status): String = stringResource(when (status) {
    HealthMonitorAssessment.Status.UNAVAILABLE -> R.string.health_monitor_unavailable
    HealthMonitorAssessment.Status.UNVERIFIED -> R.string.health_monitor_unverified
    HealthMonitorAssessment.Status.CALIBRATING -> R.string.health_monitor_calibrating
    HealthMonitorAssessment.Status.WITHIN_RANGE -> R.string.health_monitor_within_range
    HealthMonitorAssessment.Status.OUTSIDE_RANGE -> R.string.health_monitor_outside_range
    HealthMonitorAssessment.Status.FAR_OUTSIDE_RANGE -> R.string.health_monitor_far_outside_range
})

private fun statusColor(status: HealthMonitorAssessment.Status): Color = when (status) {
    HealthMonitorAssessment.Status.WITHIN_RANGE -> Palette.statusPositive
    HealthMonitorAssessment.Status.OUTSIDE_RANGE -> Palette.statusWarning
    HealthMonitorAssessment.Status.FAR_OUTSIDE_RANGE -> Palette.statusCritical
    else -> Palette.textSecondary
}

private fun statusIcon(status: HealthMonitorAssessment.Status) = when (status) {
    HealthMonitorAssessment.Status.WITHIN_RANGE -> Icons.Filled.CheckCircle
    HealthMonitorAssessment.Status.OUTSIDE_RANGE, HealthMonitorAssessment.Status.FAR_OUTSIDE_RANGE -> Icons.Filled.ErrorOutline
    else -> Icons.Filled.Info
}

@StringRes
internal fun monitorShortLabel(key: String): Int = when (key) {
    "hrv" -> R.string.health_monitor_hrv
    "rhr" -> R.string.health_monitor_rhr
    "resp" -> R.string.health_monitor_resp
    "spo2" -> R.string.health_monitor_spo2
    else -> R.string.health_monitor_temp
}

@StringRes
internal fun monitorMissingLabel(key: String): Int = when (key) {
    "hrv" -> R.string.health_monitor_hrv_missing
    "rhr" -> R.string.health_monitor_rhr_missing
    "resp" -> R.string.health_monitor_resp_missing
    "spo2" -> R.string.health_monitor_spo2_missing
    else -> R.string.health_monitor_temp_missing
}

@StringRes
private fun monitorPreviewLabel(key: String): Int = when (key) {
    "hrv" -> R.string.health_monitor_hrv
    "rhr" -> R.string.health_monitor_preview_rhr
    "resp" -> R.string.health_monitor_preview_resp
    "spo2" -> R.string.health_monitor_preview_spo2
    else -> R.string.health_monitor_preview_temp
}
