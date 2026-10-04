package com.noop.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.Baselines
import com.noop.analytics.DayCycleIntelligenceIntegration
import com.noop.analytics.DayCycleMode
import com.noop.analytics.HrZones
import com.noop.analytics.RestScorer
import com.noop.analytics.SkinTempDisplay
import com.noop.data.DailyMetric
import com.noop.data.HrBucket
import com.noop.data.WorkoutRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun RecoveryDetailScreen(
    vm: AppViewModel,
    dayKey: String? = null,
    onOpenInsights: (() -> Unit)? = null,
    onBack: () -> Unit,
) {
    val viewportWidth = LocalConfiguration.current.screenWidthDp.dp
    val today by vm.today.collectAsStateWithLifecycle()
    val chargeBaselines by vm.chargeBaselines.collectAsStateWithLifecycle()
    val hrvRegimeEpoch by vm.hrvRegimeEpoch.collectAsStateWithLifecycle()
    val registryId by vm.activeStrapIdFlow.collectAsStateWithLifecycle()
    val activeId = registryId ?: vm.activeStrapId
    val days = detailDays(vm, activeId)
    val selectedKey = dayKey ?: today?.day ?: logicalDayNow().toString()
    val date = detailDate(selectedKey)
    val selected = days.firstOrNull { it.day == selectedKey }
    val score = selected?.recovery?.takeIf { RecoveryStrainDetailLogic.recoveryPercent(it) != null }
    val context = LocalContext.current
    val epoch = maxOf(NoopPrefs.of(context).getLong(Baselines.hrvBaselineEpochKey, 0L).toDouble(), hrvRegimeEpoch)
    val calibration = if (selectedKey == (today?.day ?: logicalDayNow().toString())) recoveryCalibrationNights(
        chargeBaselines?.hrvHistory?.values.orEmpty(), chargeBaselines?.hrvHistory?.dayKeys.orEmpty(),
        score != null, epoch,
    ) else null
    var sleepPerformance by remember(selectedKey, activeId) { mutableStateOf<List<Pair<String, Double>>>(emptyList()) }
    var showInsights by remember { mutableStateOf(false) }
    LaunchedEffect(selectedKey, days, activeId) {
        sleepPerformance = runCatching {
            vm.repo.resolvedSeries("sleep_performance", "my-whoop", date.minusDays(30).toString(), selectedKey,
                strapDeviceId = activeId).values
        }.getOrDefault(emptyList())
    }
    if (showInsights) {
        BackHandler { showInsights = false }
        Column {
            DetailHeader(stringResource(R.string.d2b_behavior_insights), null) { showInsights = false }
            InsightsScreen(vm)
        }
        return
    }
    BackHandler(onBack = onBack)
    val color = score?.let(Palette::recoveryColor) ?: Palette.textTertiary
    LazyScreenScaffold(title = null, topPadding = Metrics.space8, rowSpacing = Metrics.space12,
        topBackground = { Box(Modifier.fillMaxSize().background(Palette.canvasGradient)) }, fullBleedBackground = true) {
        item { DetailHeader(uiString(R.string.d2b_recovery), detailDateLabel(date), onBack) }
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                ScoreDial(uiString(R.string.d2b_recovery), detailRecoveryNumber(score), "%",
                    score?.div(100)?.toFloat(), color,
                    accessibilityLabel = score?.let { value -> listOf(uiString(R.string.d2b_recovery),
                        detailRecoveryNumber(value) + "%", uiString(when {
                            value < 34 -> R.string.d2b_recovery_low; value < 67 -> R.string.d2b_recovery_medium; else -> R.string.d2b_recovery_high
                        })).joinToString(", ") }, viewportWidth = viewportWidth)
                if (score == null) StatusPill(if (calibration != null)
                    uiString(R.string.d2b_calibrating, calibration, Baselines.minNightsSeed)
                    else uiString(R.string.d2b_not_available), color = color)
            }
        }
        if (score == null) item {
            InsightCallout(uiString(if (calibration != null) R.string.d2b_calibration_note else R.string.d2b_missing_recovery))
        }
        item {
            NoopCard {
                Column {
                    DetailContributor(uiString(R.string.d2b_hrv), selected?.avgHrv, "ms", days, selectedKey,
                        { it.avgHrv }, higherFavorable = true, icon = Icons.Filled.MonitorHeart)
                    DetailDivider()
                    DetailContributor(uiString(R.string.d2b_resting_hr), selected?.restingHr?.toDouble(), "bpm", days, selectedKey,
                        { it.restingHr?.toDouble() }, higherFavorable = false, decimals = 0, icon = Icons.Filled.FavoriteBorder)
                    DetailDivider()
                    DetailContributor(uiString(R.string.d2b_respiration), selected?.respRateBpm, uiString(R.string.d2b_breaths_min),
                        days, selectedKey, { it.respRateBpm }, icon = Icons.Filled.Air)
                    DetailDivider()
                    val sleepByDay = sleepPerformance.toMap()
                    val sleep = sleepByDay[selectedKey] ?: selected?.let { RestScorer.restFromDaily(it) }
                    DetailContributor(uiString(R.string.d2b_sleep_performance), sleep, "%", days, selectedKey,
                        { sleepByDay[it.day] ?: RestScorer.restFromDaily(it) }, decimals = 0, icon = Icons.Filled.Bedtime)
                    selected?.spo2Pct?.takeIf { it.isFinite() }?.let {
                        DetailDivider()
                        DetailContributor(uiString(R.string.d2b_blood_oxygen), it, "%", days, selectedKey,
                            { row -> row.spo2Pct }, icon = Icons.Filled.Bloodtype)
                    }
                    selected?.let { row ->
                        SkinTempDisplay.leadReading(row.skinTempC, row.skinTempDevC, UnitPrefs.skinTempPreferred(context))?.let { reading ->
                            val kind = if (reading.kind == SkinTempDisplay.Kind.DEVIATION) SkinTempDisplay.kind(reading.value) else reading.kind
                            val unit = UnitPrefs.temperature(context)
                            val value = if (kind == SkinTempDisplay.Kind.ABSOLUTE) UnitFormatter.temperatureFromCelsius(reading.value, unit)
                                else UnitFormatter.temperatureDeltaFromCelsius(reading.value, unit)
                            DetailDivider()
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                                DetailContributorRow(uiString(R.string.d2b_skin_temperature), value, icon = Icons.Filled.Thermostat)
                                Text(uiString(if (kind == SkinTempDisplay.Kind.ABSOLUTE) R.string.d2b_absolute_temperature else R.string.d2b_temperature_deviation),
                                    style = NoopType.caption, color = Palette.textSecondary, textAlign = TextAlign.End)
                            }
                        }
                    }
                    DetailComparisonLegend()
                }
            }
        }
        item {
            InsightCallout(uiString(R.string.d2b_behavior_note), uiString(R.string.d2b_behavior_insights), {
                onOpenInsights?.invoke() ?: run { showInsights = true }
            })
        }
        item { DetailTrend(days, selectedKey) }
        item { Text(uiString(R.string.d2b_comparison_note), style = NoopType.footnote, color = Palette.textTertiary) }
    }
}

@Composable
fun StrainDetailScreen(
    vm: AppViewModel,
    dayKey: String? = null,
    effortOverride: Double? = null,
    windowDayKey: String? = null,
    onBack: () -> Unit,
) {
    val viewportWidth = LocalConfiguration.current.screenWidthDp.dp
    val today by vm.today.collectAsStateWithLifecycle()
    val registryId by vm.activeStrapIdFlow.collectAsStateWithLifecycle()
    val activeId = registryId ?: vm.activeStrapId
    val days = detailDays(vm, activeId)
    val selectedKey = dayKey ?: today?.day ?: logicalDayNow().toString()
    val selected = days.firstOrNull { it.day == selectedKey }
    val openedActiveId = remember { activeId }
    val effort = (effortOverride.takeIf { openedActiveId == activeId } ?: selected?.strain)?.takeIf { it.isFinite() && it in 0.0..100.0 }
    val strain = effort?.let { UnitFormatter.effortValue(it, EffortScale.WHOOP) }
    val strainDisplay = effort?.let { UnitFormatter.effortDisplay(it, EffortScale.WHOOP) }
    val recovery = selected?.recovery?.takeIf { RecoveryStrainDetailLogic.recoveryPercent(it) != null }
    val band = optimalStrainRange(recovery)
    val target = RecoveryStrainDetailLogic.targetStatus(strainDisplay, band?.low, band?.high)
    val allWorkouts by vm.workouts.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val profile = remember(context) { ProfileStore.from(context) }
    val mode = NoopPrefs.dayCycleMode(context)
    var hr by remember(selectedKey, windowDayKey, activeId, mode) { mutableStateOf<List<HrBucket>>(emptyList()) }
    var zones by remember(selectedKey, windowDayKey, activeId, mode) { mutableStateOf<List<Double>?>(null) }
    var belowZone1 by remember(selectedKey, windowDayKey, activeId, mode) { mutableStateOf<Double?>(null) }
    var window by remember(selectedKey, windowDayKey, activeId, mode) { mutableStateOf<LongRange?>(null) }
    var activity by remember { mutableStateOf<WorkoutRow?>(null) }
    var showGuide by remember { mutableStateOf(false) }
    var workoutsLoaded by remember(activeId) { mutableStateOf(false) }
    LaunchedEffect(activeId) {
        vm.loadWorkouts().join()
        workoutsLoaded = true
    }
    LaunchedEffect(selectedKey, windowDayKey, days, activeId, mode, live.lastSyncAt, live.syncChunksThisSession) {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis() / 1000
        val currentLogicalDay = logicalDay(java.time.Instant.ofEpochSecond(now).atZone(zone))
        val anchor = windowDayKey?.let(::detailDate) ?: if (dayKey == null) currentLogicalDay else detailDate(selectedKey)
        val nextMarkerKey = detailDate(selectedKey).plusDays(1).toString()
        val markers = if (mode == DayCycleMode.SLEEP_ONSET) runCatching {
            vm.repo.metricSeriesComputedUnion(activeId, DayCycleIntelligenceIntegration.ONSET_KEY,
                selectedKey, nextMarkerKey)
        }.getOrDefault(emptyList()) else emptyList()
        val resolvedWindow = RecoveryStrainDetailLogic.strainWindow(
            anchor.atStartOfDay(zone).toEpochSecond(), anchor.plusDays(1).atStartOfDay(zone).toEpochSecond(),
            anchor == currentLogicalDay, mode == DayCycleMode.SLEEP_ONSET,
            RecoveryStrainDetailLogic.timestampSeconds(markers.firstOrNull { it.day == selectedKey }?.value),
            RecoveryStrainDetailLogic.timestampSeconds(markers.firstOrNull { it.day == nextMarkerKey }?.value), now)
        window = resolvedWindow
        hr = if (resolvedWindow == null) emptyList() else runCatching {
            vm.repo.hrBucketsUnion(activeId, resolvedWindow.first, resolvedWindow.last, 300)
        }.getOrDefault(emptyList())
        val samples = if (resolvedWindow == null) emptyList() else runCatching {
            vm.repo.hrSamplesUnion(activeId, resolvedWindow.first, resolvedWindow.last, limit = 200_000)
        }.getOrDefault(emptyList())
        val split = samples.takeIf { it.isNotEmpty() }?.let { HrZones.timeInZone(it, profile.hrZoneSet) }
        zones = split?.seconds?.map { it / 60 }
        belowZone1 = split?.belowZone1?.div(60)
    }
    activity?.let { row ->
        ActivityDetailScreen(vm, row) { activity = null }
        return
    }
    BackHandler(onBack = onBack)
    val workouts = if (workoutsLoaded) allWorkouts.filter { row -> window?.let { row.startTs in it } == true } else emptyList()
    LazyScreenScaffold(title = null, topPadding = Metrics.space8, rowSpacing = Metrics.space12,
        topBackground = { Box(Modifier.fillMaxSize().background(Palette.canvasGradient)) }, fullBleedBackground = true) {
        item { DetailHeader(uiString(R.string.d2b_strain), detailDateLabel(detailDate(selectedKey)), onBack) }
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                ScoreDial(uiString(R.string.d2b_strain), strainDisplay ?: uiString(R.string.home_no_value), progress = strain?.div(21)?.toFloat(),
                    color = Palette.strainPrimary, target = band?.let { it.low / 21f },
                    targetRange = band?.let { it.low / 21f..it.high / 21f }, viewportWidth = viewportWidth)
                StatusPill(uiString(when (target) {
                    RecoveryStrainDetailLogic.TargetStatus.Unavailable -> if (band == null) R.string.d2b_target_unavailable else R.string.d2b_strain_unavailable
                    RecoveryStrainDetailLogic.TargetStatus.Under -> R.string.d2b_target_under
                    RecoveryStrainDetailLogic.TargetStatus.Optimal -> R.string.d2b_target_optimal
                    RecoveryStrainDetailLogic.TargetStatus.Over -> R.string.d2b_target_over
                }), color = when (target) { RecoveryStrainDetailLogic.TargetStatus.Unavailable -> Palette.textTertiary
                    RecoveryStrainDetailLogic.TargetStatus.Over -> Palette.statusWarning; else -> Palette.strainPrimary })
                if (band != null) Text(uiString(R.string.d2b_optimal_band, band.low, band.high), style = NoopType.body,
                    color = Palette.textSecondary)
            }
        }
        item { DetailStrainSummary(zones, selected, days, selectedKey) }
        if (effort == null) item { InsightCallout(uiString(R.string.d2b_missing_strain)) }
        if (band == null) item { InsightCallout(uiString(R.string.d2b_missing_target)) }
        item {
            InsightCallout(uiString(R.string.d2b_target_note),
                uiString(R.string.l10n_scoring_guide_screen_how_your_scores_work_21a0e2be), { showGuide = true })
        }
        item { TrackedSectionHeader(uiString(R.string.d2b_activities)) }
        if (workouts.isEmpty()) item { DetailEmpty(uiString(R.string.d2b_no_activities)) }
        workouts.forEach { row ->
            item(key = "${row.deviceId}|${row.startTs}|${row.sport}") {
                NoopCard(Modifier.clickable { activity = row }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                            Text(WorkoutEditing.displaySport(row.sport), style = NoopType.title2, color = Palette.textPrimary)
                            Text(detailWorkoutTime(row), style = NoopType.caption, color = Palette.textSecondary)
                        }
                        Text(detailNumber(row.strain?.takeIf { it.isFinite() && it in 0.0..100.0 }?.let { UnitFormatter.effortValue(it, EffortScale.WHOOP) }),
                            style = NoopType.chartValueLarge, color = Palette.strainPrimary)
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, uiString(R.string.d2b_activity_details),
                            tint = Palette.textSecondary, modifier = Modifier.size(Metrics.iconSmall))
                    }
                }
            }
        }
        item { DetailHrChart(hr, uiString(R.string.d2b_day_heart_rate), 300) }
        item { DetailZones(zones, uiString(R.string.d2b_strap_zones_note), belowZone1) }
        if (effort != null) item {
            Text(uiString(R.string.d2b_effort_explanation, detailNumber(effort)),
                style = NoopType.footnote, color = Palette.textTertiary)
        }
    }
    if (showGuide) DetailFullScreenDialog(onDismiss = { showGuide = false }) {
        ScoringGuideScreen(onClose = { showGuide = false }, initialSection = ScoreSection.EFFORT)
    }
}

@Composable
fun ActivityDetailScreen(vm: AppViewModel, row: WorkoutRow, onBack: () -> Unit) {
    val viewportWidth = LocalConfiguration.current.screenWidthDp.dp
    val registryId by vm.activeStrapIdFlow.collectAsStateWithLifecycle()
    val activeId = registryId ?: vm.activeStrapId
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    val current = workouts.firstOrNull { it.deviceId == row.deviceId && it.startTs == row.startTs && it.sport == row.sport } ?: row
    var hr by remember(current, activeId) { mutableStateOf<List<HrBucket>>(emptyList()) }
    var zones by remember(current, activeId) { mutableStateOf<List<Double>?>(null) }
    var importedZones by remember(current, activeId) { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    var moreDetails by remember { mutableStateOf(false) }
    val source = WorkoutEditing.classify(current.source)
    val editable = source == WorkoutSource.MANUAL || source == WorkoutSource.DETECTED
    val effort = current.strain?.takeIf { it.isFinite() && it in 0.0..100.0 }
    LaunchedEffect(current, activeId) {
        hr = vm.workoutHrBuckets(current.startTs, current.endTs, current.source, current.deviceId, activeId)
        val duration = current.durationS ?: (current.endTs - current.startTs).toDouble()
        val imported = RecoveryStrainDetailLogic.zoneDistribution(parseZonePercents(current.zonesJSON), duration)
        val distribution = imported ?: RecoveryStrainDetailLogic.zoneDistribution(null, duration,
            vm.workoutZoneMinutes(current.startTs, current.endTs, current.source, current.deviceId, activeId))
        zones = distribution?.minutes
        importedZones = distribution?.imported ?: false
    }
    BackHandler(onBack = onBack)
    LazyScreenScaffold(title = null, topPadding = Metrics.space8, rowSpacing = Metrics.space12,
        topBackground = { Box(Modifier.fillMaxSize().background(Palette.canvasGradient)) }, fullBleedBackground = true) {
        item { DetailHeader(WorkoutEditing.displaySport(current.sport), detailWorkoutTime(current), onBack) }
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                val strain = effort?.let { UnitFormatter.effortValue(it, EffortScale.WHOOP) }
                ScoreDial(uiString(R.string.d2b_activity_strain), detailNumber(strain), progress = strain?.div(21)?.toFloat(), color = Palette.strainPrimary, viewportWidth = viewportWidth)
                Text(uiString(R.string.d2b_duration, detailDuration(current.durationS, (current.endTs - current.startTs).toDouble())),
                    style = NoopType.body, color = Palette.textSecondary)
            }
        }
        if (source != WorkoutSource.DETECTED) item {
            Text(uiString(R.string.d2b_trace_source_note), style = NoopType.footnote, color = Palette.textTertiary)
        }
        item { DetailHrChart(hr, uiString(R.string.d2b_activity_heart_rate), ((current.endTs - current.startTs) / 120).coerceIn(15, 300)) }
        item {
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                    DetailContributorRow(uiString(R.string.d2b_average_hr), detailNumber(current.avgHr?.toDouble(), 0), "bpm")
                    DetailContributorRow(uiString(R.string.d2b_maximum_hr), detailNumber(current.maxHr?.toDouble(), 0), "bpm")
                    val traceMean = hr.map { it.avgBpm }.takeIf { it.isNotEmpty() }?.average()
                    if ((current.strain != null || !current.zonesJSON.isNullOrEmpty()) && current.avgHr != null &&
                        traceMean != null && kotlin.math.abs(current.avgHr - traceMean) > 3.0) {
                        Text(uiString(R.string.d2b_average_disclosure),
                            style = NoopType.caption, color = Palette.textSecondary)
                    }
                    current.distanceM?.takeIf { it.isFinite() && it >= 0 }?.let { distance ->
                        DetailContributorRow(uiString(R.string.d2b_distance), UnitFormatter.distanceFromMeters(distance, UnitPrefs.distanceSystem(LocalContext.current)))
                    }
                    DetailContributorRow(uiString(R.string.d2b_recorded_energy),
                        detailWholeNumber(current.energyKcal), "kcal")
                    if (current.energyKcal != null) Text(uiString(R.string.d2b_energy_note),
                        style = NoopType.caption, color = Palette.textSecondary)
                }
            }
        }
        item { DetailZones(zones, uiString(if (importedZones) R.string.d2b_imported_zones_note else R.string.d2b_strap_zones_note)) }
        effort?.let { stored -> item { InsightCallout(uiString(R.string.d2b_effort_explanation, detailNumber(stored))) } }
        item {
            TextButton(onClick = { edit = true }, modifier = Modifier.fillMaxWidth()) {
                Text(uiString(if (editable) R.string.d2b_edit_activity else R.string.d2b_copy_activity),
                    style = NoopType.body, color = Palette.textPrimary)
            }
        }
        item {
            TextButton(onClick = { moreDetails = true }, modifier = Modifier.fillMaxWidth()) {
                Text(uiString(R.string.d2b_more_details), style = NoopType.body, color = Palette.textPrimary)
            }
        }
    }
    if (edit) ManualWorkoutDialog(
        editing = if (editable) current else WorkoutEditing.asManualCopy(current), isCopy = !editable,
        onDismiss = { edit = false }, onSave = { saved, replacing ->
            vm.saveManualWorkout(saved, replacing, asCopy = !editable)
            edit = false
            onBack()
        },
    )
    if (moreDetails) WorkoutDetailSheet(vm, current, onDismiss = { moreDetails = false }, expandedDetails = true)
}

@Composable
private fun detailDays(vm: AppViewModel, activeId: String): List<DailyMetric> = key(activeId) {
    val flow = remember(vm, activeId) { vm.repo.daysMergedFlow(activeId) }
    val days by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    days
}

@Composable
private fun DetailHeader(title: String, subtitle: String?, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        IconButton(onClick = onBack, modifier = Modifier.size(Metrics.iconButton)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.d2b_back), tint = Palette.textPrimary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
            Text(title, style = NoopType.title2, color = Palette.textPrimary)
            if (subtitle != null) Text(subtitle, style = NoopType.caption, color = Palette.textSecondary)
        }
    }
}

@Composable
private fun DetailContributor(
    label: String, current: Double?, unit: String, days: List<DailyMetric>, selectedKey: String,
    value: (DailyMetric) -> Double?, higherFavorable: Boolean? = null, decimals: Int = 1, icon: ImageVector? = null,
) {
    val mean = RecoveryStrainDetailLogic.priorMean(days.map { it.day }, days.map(value),
        detailDate(selectedKey).minusDays(30).toString(), selectedKey)
    val delta = RecoveryStrainDetailLogic.comparisonDelta(current, mean, decimals)
    val favorable = if (delta != null && delta != 0.0 && higherFavorable != null) (delta > 0) == higherFavorable else null
    DetailContributorRow(label, detailNumber(current, decimals), unit, icon = icon,
        comparison = mean?.let { detailNumber(it, decimals) + " " + unit },
        comparisonIcon = when { delta == null || delta == 0.0 -> Icons.Filled.Circle
            delta > 0 -> Icons.Filled.ArrowDropUp; else -> Icons.Filled.ArrowDropDown },
        comparisonColor = when (favorable) { true -> Palette.positive; false -> Palette.statusWarning; null -> Palette.textSecondary },
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = listOf(label, detailNumber(current, decimals) + " " + unit,
                detailComparison(current, mean, unit, decimals)).joinToString(", ")
        })
}

@Composable
private fun DetailDivider() = HorizontalDivider(thickness = Metrics.divider, color = Palette.hairline)

@Composable
private fun DetailComparisonLegend() {
    Row(Modifier.fillMaxWidth().background(Palette.surfaceBase).padding(Metrics.space8),
        horizontalArrangement = Arrangement.spacedBy(Metrics.space8), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.ArrowDropUp, null, tint = Palette.positive, modifier = Modifier.size(Metrics.iconSmall))
        Icon(Icons.Filled.ArrowDropDown, null, tint = Palette.statusWarning, modifier = Modifier.size(Metrics.iconSmall))
        Text(uiString(R.string.d2b_prior_mean_context), style = NoopType.caption, color = Palette.textSecondary)
    }
}

@Composable
private fun DetailStrainSummary(minutes: List<Double>?, row: DailyMetric?, days: List<DailyMetric>, selectedKey: String) {
    fun duration(indices: IntRange): String {
        val values = minutes?.takeIf { it.size == 5 } ?: return uiString(R.string.d2b_no_value)
        return detailDuration(indices.fold(0.0) { total, index -> total + values[index] } * 60)
    }
    NoopCard {
        Column {
            DetailContributorRow(uiString(R.string.d2b_zone_group_low), duration(0..2), icon = Icons.Filled.FavoriteBorder)
            DetailDivider()
            DetailContributorRow(uiString(R.string.d2b_zone_group_high), duration(3..4), icon = Icons.Filled.MonitorHeart)
            DetailDivider()
            DetailContributorRow(uiString(R.string.d2b_strength_duration), uiString(R.string.d2b_no_value), icon = Icons.Filled.FitnessCenter)
            DetailDivider()
            DetailContributor(uiString(R.string.today_metric_steps), row?.steps?.toDouble(), "", days, selectedKey,
                { it.steps?.toDouble() }, decimals = 0, icon = Icons.Filled.DirectionsWalk)
            DetailComparisonLegend()
        }
    }
}

private fun detailComparison(current: Double?, mean: Double?, unit: String, decimals: Int): String {
    if (mean == null) return uiString(R.string.d2b_no_comparison)
    val comparison = uiString(R.string.d2b_prior_mean, detailNumber(mean, decimals), unit)
    if (current == null || !current.isFinite()) return comparison
    val delta = RecoveryStrainDetailLogic.comparisonDelta(current, mean, decimals) ?: return comparison
    return uiString(R.string.d2b_prior_delta, if (delta > 0) "+${detailNumber(delta, decimals)}" else detailNumber(delta, decimals), unit, comparison)
}

@Composable
private fun DetailTrend(days: List<DailyMetric>, selectedKey: String) {
    var count by remember { mutableStateOf(7) }
    val selectedDate = detailDate(selectedKey)
    val start = selectedDate.minusDays((count - 1).toLong()).toString()
    val points = days.filter { it.day >= start && it.day <= selectedKey }.mapNotNull { row ->
        row.recovery?.takeIf { RecoveryStrainDetailLogic.recoveryPercent(it) != null }?.let { row.day to it }
    }.sortedBy { it.first }
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            TrackedSectionHeader(uiString(R.string.d2b_trend))
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                listOf(7, 30).forEach { daysCount ->
                    TextButton(onClick = { count = daysCount }) {
                        Text(uiString(R.string.d2b_days, daysCount), style = NoopType.overline,
                            color = if (count == daysCount) Palette.textPrimary else Palette.textTertiary)
                    }
                }
            }
            Text(uiString(R.string.d2b_date_window, detailDateLabel(detailDate(start)), detailDateLabel(selectedDate)),
                style = NoopType.caption, color = Palette.textSecondary)
            if (points.isEmpty()) DetailEmpty(uiString(R.string.d2b_no_trend)) else {
                val timestamps = points.map { detailDate(it.first).atStartOfDay(ZoneId.systemDefault()).toEpochSecond() }
                val summary = uiString(R.string.trends_trend_a11y, listOf(
                    uiString(R.string.d2b_labeled_percent, uiString(R.string.explore_latest), detailRecoveryNumber(points.last().second)),
                    uiString(R.string.d2b_labeled_percent, uiString(R.string.trends_min), detailRecoveryNumber(points.minOf { it.second })),
                    uiString(R.string.d2b_labeled_percent, uiString(R.string.trends_max), detailRecoveryNumber(points.maxOf { it.second })),
                ).joinToString(", "))
                Box(Modifier.clearAndSetSemantics { contentDescription = summary }) {
                    LineChart(points.map { it.second }, Modifier.fillMaxWidth().height(Metrics.trendStripHeight),
                        color = Palette.recoveryColor(points.last().second),
                        selectionEnabled = true, selectionLabels = points.map { detailDateLabel(detailDate(it.first)) },
                        timestamps = timestamps, segmentIds = hrGapSegmentIds(timestamps, 86400), showsPoints = true,
                        formatValue = { value -> "${detailRecoveryNumber(value)}%" },
                        yDomain = 0.0..100.0)
                }
            }
        }
    }
}

@Composable
private fun DetailHrChart(hr: List<HrBucket>, title: String, bucketSeconds: Long) {
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            TrackedSectionHeader(title)
            if (hr.isEmpty()) DetailEmpty(uiString(R.string.d2b_no_hr)) else {
                val is24Hour = ClockPrefs.uses24Hour(LocalContext.current)
                Text(uiString(R.string.d2b_hr_window, clockTimeLabel(hr.first().bucket, is24Hour), clockTimeLabel(hr.last().bucket, is24Hour)),
                    style = NoopType.caption, color = Palette.textSecondary)
                LineChart(hr.map { it.avgBpm }, Modifier.fillMaxWidth().height(Metrics.chartHeight),
                    color = Palette.strainPrimary, selectionEnabled = true, timestamps = hr.map { it.bucket },
                    segmentIds = hrGapSegmentIds(hr.map { it.bucket }, bucketSeconds), formatValue = { "${detailNumber(it, 0)} bpm" })
            }
        }
    }
}

@Composable
private fun DetailZones(minutes: List<Double>?, note: String, belowZone1: Double? = null) {
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            TrackedSectionHeader(uiString(R.string.d2b_hr_zones))
            val validMinutes = minutes?.takeIf { it.size == 5 && it.all { value -> RecoveryStrainDetailLogic.wholeNumber(value) != null } }
            val total = validMinutes?.sum() ?: 0.0
            if (validMinutes == null) DetailEmpty(uiString(R.string.d2b_no_zones)) else {
                Text(uiString(R.string.d2b_classified_zone_share), style = NoopType.caption, color = Palette.textSecondary)
                if (total > 0) SegmentBar(validMinutes.mapIndexed { index, value -> Palette.hrZoneColor(index + 1) to (value / total).toFloat() },
                    Modifier.fillMaxWidth(), height = Metrics.segmentBarHeight)
                validMinutes.forEachIndexed { index, value ->
                    DetailContributorRow(uiString(R.string.d2b_zone, index + 1), detailWholeNumber(value), uiString(R.string.d2b_minutes),
                        comparison = if (total > 0) "${detailNumber(value / total * 100, 0)}%" else null,
                        comparisonColor = Palette.hrZoneColor(index + 1))
                }
            }
            belowZone1?.takeIf { it > 0 && RecoveryStrainDetailLogic.wholeNumber(it) != null }?.let {
                DetailContributorRow(uiString(R.string.d2b_below_zone_one), detailWholeNumber(it), uiString(R.string.d2b_minutes))
            }
            Text(note, style = NoopType.caption, color = Palette.textSecondary)
        }
    }
}

@Composable
private fun DetailEmpty(text: String) {
    Text(text, style = NoopType.body, color = Palette.textSecondary, modifier = Modifier.padding(vertical = Metrics.space8))
}

private fun detailDate(key: String): LocalDate = runCatching { LocalDate.parse(key) }.getOrDefault(logicalDayNow())
private fun detailDateLabel(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
@Composable
private fun detailWorkoutTime(row: WorkoutRow): String {
    val is24Hour = ClockPrefs.uses24Hour(LocalContext.current)
    return uiString(R.string.d2b_activity_time,
        detailDateLabel(Instant.ofEpochSecond(row.startTs).atZone(ZoneId.systemDefault()).toLocalDate()),
        clockTimeLabel(row.startTs, is24Hour), clockTimeLabel(row.endTs, is24Hour))
}
private fun detailNumber(value: Double?, decimals: Int = 1): String = RecoveryStrainDetailLogic.comparisonValue(value, decimals)
    ?.let { String.format(Locale.getDefault(), "%.${decimals}f", it) } ?: uiString(R.string.d2b_no_value)
private fun detailRecoveryNumber(score: Double?): String =
    detailNumber(RecoveryStrainDetailLogic.recoveryPercent(score)?.toDouble(), 0)
private fun detailWholeNumber(value: Double?): String = RecoveryStrainDetailLogic.wholeNumber(value)
    ?.let { String.format(Locale.getDefault(), "%d", it) } ?: uiString(R.string.d2b_no_value)
private fun detailDuration(seconds: Double?, fallbackSeconds: Double? = null): String {
    val minutes = RecoveryStrainDetailLogic.durationMinutes(seconds, fallbackSeconds) ?: return uiString(R.string.d2b_no_value)
    return if (minutes >= 60) uiString(R.string.d2b_hours_minutes, minutes / 60, minutes % 60) else uiString(R.string.d2b_duration_minutes, minutes)
}
