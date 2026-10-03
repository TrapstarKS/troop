package com.noop.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.HealthspanHistory
import com.noop.analytics.HealthspanPresentation
import com.noop.data.WhoopRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

internal enum class HealthspanDriver(val pillar: Int, val titleId: Int, val weekly: Boolean = false) {
    sleepDuration(0, R.string.healthspan_sleep_duration), sleepConsistency(0, R.string.healthspan_sleep_consistency),
    lowZones(1, R.string.healthspan_low_zones, true), highZones(1, R.string.healthspan_high_zones, true),
    strength(1, R.string.healthspan_strength_time, true), steps(1, R.string.healthspan_steps),
    restingHR(2, R.string.healthspan_resting_hr), vo2(2, R.string.healthspan_vo2_driver), leanMass(2, R.string.healthspan_lean_mass);
    val unit: String get() = when (this) {
        sleepDuration, lowZones, highZones, strength -> "min"
        sleepConsistency -> "%"
        steps -> ""
        restingHR -> "bpm"
        vo2 -> "ml/kg/min"
        leanMass -> "kg"
    }
}

@Composable
internal fun HealthspanPillarCards(onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        TrackedSectionHeader(stringResource(R.string.healthspan_supporting))
        for (pillar in 0..2) {
            NoopCard(modifier = Modifier.clickable { onSelect(pillar) }) {
                Text(healthspanPillarTitle(pillar), style = NoopType.headline, color = Palette.textPrimary)
            }
        }
        Text(stringResource(R.string.healthspan_local_impact), style = NoopType.caption, color = Palette.textSecondary)
    }
}

@Composable
internal fun HealthspanContributorFlow(vm: AppViewModel, pillar: Int, reference: LocalDate,
                                      onBack: () -> Unit, onCoach: (() -> Unit)?) {
    var selectedName by rememberSaveable(pillar, reference) { mutableStateOf<String?>(null) }
    val driver = selectedName?.let { HealthspanDriver.valueOf(it) }
    BackHandler { if (driver != null) selectedName = null else onBack() }
    if (driver == null) {
        LazyScreenScaffold(title = healthspanPillarTitle(pillar), subtitle = healthDateLabel(reference)) {
            item { TextButton(onClick = onBack) { Text(stringResource(R.string.healthspan_back)) } }
            HealthspanDriver.entries.filter { it.pillar == pillar }.forEach { metric ->
                item {
                    NoopCard(modifier = Modifier.clickable { selectedName = metric.name }) {
                        Text(stringResource(metric.titleId), style = NoopType.headline, color = Palette.textPrimary)
                    }
                }
            }
            item { Text(stringResource(R.string.healthspan_compare_note), style = NoopType.body, color = Palette.textSecondary) }
            item { Text(stringResource(R.string.healthspan_targets_unavailable), style = NoopType.caption, color = Palette.textSecondary) }
        }
    } else {
        HealthspanContributorDetail(vm, driver, reference, { selectedName = null }, onCoach)
    }
}

@Composable
private fun HealthspanContributorDetail(vm: AppViewModel, driver: HealthspanDriver, reference: LocalDate,
                                       onBack: () -> Unit, onCoach: (() -> Unit)?) {
    val selectedStrap by vm.activeStrapIdFlow.collectAsStateWithLifecycle()
    val strapId = selectedStrap ?: vm.activeStrapId
    val context = androidx.compose.ui.platform.LocalContext.current
    val profile = ProfileStore.from(context.applicationContext)
    val unitSystem = UnitPrefs.system(context)
    fun formattedValue(value: Double): String = if (driver == HealthspanDriver.leanMass) UnitFormatter.massFromKilograms(value, unitSystem) else healthspanNumber(value) + " " + driver.unit
    var samples by remember(vm, strapId, reference, driver) { mutableStateOf(emptyList<HealthspanHistory.Sample>()) }
    var windowDays by rememberSaveable(driver, reference) { mutableStateOf(180) }
    var selectedOffset by remember(samples, windowDays) { mutableStateOf<Int?>(null) }
    LaunchedEffect(vm, strapId, reference, driver) {
        try {
            samples = withContext(Dispatchers.Default) {
                healthspanContributorSamples(vm.repo, strapId, reference, profile.hrMax.toDouble(), profile.sex, NoopPrefs.effortMethod(context))[driver].orEmpty()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { samples = emptyList() }
    }
    val comparison = HealthspanHistory.comparison(samples, driver.weekly)
    val points = HealthspanHistory.points(samples, windowDays)
    val reading = HealthspanHistory.selected(points, selectedOffset ?: 0, windowDays)
    LazyScreenScaffold(title = stringResource(driver.titleId), subtitle = healthDateLabel(reference)) {
        item { TextButton(onClick = onBack) { Text(stringResource(R.string.healthspan_back)) } }
        item {
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                    HealthspanComparisonRow(stringResource(R.string.healthspan_average_30), comparison.recentTenths, comparison.recentCount, ::formattedValue)
                    HealthspanComparisonRow(stringResource(R.string.healthspan_average_180), comparison.longTermTenths, comparison.longTermCount, ::formattedValue)
                    Text(stringResource(if (driver.weekly) R.string.healthspan_logged_weekly else R.string.healthspan_recorded_mean), style = NoopType.caption, color = Palette.textSecondary)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                TextButton(onClick = { windowDays = 30 }, enabled = windowDays != 30) { Text(stringResource(R.string.healthspan_range_30)) }
                TextButton(onClick = { windowDays = 180 }, enabled = windowDays != 180) { Text(stringResource(R.string.healthspan_range_180)) }
            }
        }
        item {
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                    if (points.isEmpty()) Text(stringResource(R.string.healthspan_contributor_empty), style = NoopType.body, color = Palette.textSecondary)
                    else {
                        fun select(x: Float, width: Float) { selectedOffset = ((1 - (x / width).coerceIn(0f, 1f)) * (windowDays - 1)).roundToInt() }
                        Canvas(Modifier.fillMaxWidth().height(Metrics.compactChartHeight)
                            .pointerInput(points, windowDays) { detectTapGestures { select(it.x, size.width.toFloat()) } }
                            .pointerInput(points, windowDays) { detectHorizontalDragGestures(onDragStart = { select(it.x, size.width.toFloat()) }) { change, _ ->
                                change.consume(); select(change.position.x, size.width.toFloat())
                            } }) {
                            val low = points.minOf { it.value } - 1
                            val high = points.maxOf { it.value } + 1
                            fun position(point: HealthspanHistory.Sample) = Offset((1 - point.daysAgo.toFloat() / (windowDays - 1)) * size.width,
                                ((high - point.value) / (high - low)).toFloat() * size.height)
                            points.forEach { drawCircle(Palette.positive, Metrics.chartLineWidth.toPx(), position(it)) }
                            reading?.let { drawCircle(Palette.textPrimary, Metrics.space4.toPx(), position(it)) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(healthDateLabel(reference.minusDays((windowDays - 1).toLong())), style = NoopType.caption, color = Palette.textSecondary)
                            Text(healthDateLabel(reference), style = NoopType.caption, color = Palette.textSecondary)
                        }
                        reading?.let {
                            Text(healthDateLabel(reference.minusDays(it.daysAgo.toLong())) + " · " + formattedValue(it.value),
                                style = NoopType.caption, color = Palette.textSecondary)
                        }
                    }
                }
            }
        }
        item {
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                    Text(stringResource(R.string.healthspan_age_impact), style = NoopType.headline, color = Palette.textPrimary)
                    Text(stringResource(R.string.healthspan_impact_unavailable), style = NoopType.body, color = Palette.textSecondary)
                    Text(stringResource(R.string.healthspan_targets_unavailable), style = NoopType.body, color = Palette.textSecondary)
                    Text(stringResource(R.string.healthspan_review_guidance), style = NoopType.body, color = Palette.textSecondary)
                }
            }
        }
        if (BottomBarStyleStore.coachEnabled) {
            item { TextButton(onClick = { onCoach?.invoke() }, enabled = onCoach != null) { Text(stringResource(R.string.healthspan_open_coach)) } }
            item { Text(stringResource(R.string.healthspan_coach_consent), style = NoopType.caption, color = Palette.textSecondary) }
        }
    }
}

@Composable
private fun HealthspanComparisonRow(title: String, tenths: Int?, count: Int, formatValue: (Double) -> String) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
        Overline(title)
        Text(tenths?.let { formatValue(it / 10.0) } ?: stringResource(R.string.healthspan_no_value), style = NoopType.tileValueLarge, color = Palette.textPrimary)
        Text(stringResource(R.string.healthspan_observation_count, count), style = NoopType.caption, color = Palette.textSecondary)
    }
}

private suspend fun healthspanContributorSamples(repo: WhoopRepository, strapId: String, reference: LocalDate,
                                                hrMax: Double, sex: String, method: com.noop.analytics.StrainScorer.Method): Map<HealthspanDriver, List<HealthspanHistory.Sample>> {
    val from = maxOf(reference.minusDays(179), LocalDate.now().minusDays(3999)).toString()
    val to = reference.toString()
    val result = mutableMapOf<HealthspanDriver, List<HealthspanHistory.Sample>>()
    fun offset(day: String): Int? = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(day), reference).toInt() }.getOrNull()
    for ((driver, key, source) in listOf(Triple(HealthspanDriver.sleepDuration, "sleep_total_min", "my-whoop"),
        Triple(HealthspanDriver.sleepConsistency, "sleep_consistency", "my-whoop"), Triple(HealthspanDriver.restingHR, "rhr", "my-whoop"),
        Triple(HealthspanDriver.leanMass, "lean_mass", "apple-health"))) {
        result[driver] = repo.resolvedSeries(key, source, from, to, strapDeviceId = strapId).points.mapNotNull { p -> offset(p.day)?.let { HealthspanHistory.Sample(it, p.value) } }
    }
    val estimated = repo.resolvedSeries("vo2max_est", "my-whoop", from, to, strapDeviceId = strapId)
    val imported = repo.resolvedSeries("vo2max", "apple-health", from, to, strapDeviceId = strapId)
    val vo2 = mutableMapOf<String, Double>()
    for (point in imported.points + estimated.points) if (point.value.isFinite() && point.value > 0) vo2[point.day] = point.value
    result[HealthspanDriver.vo2] = vo2.keys.sorted().mapNotNull { day -> offset(day)?.let { HealthspanHistory.Sample(it, vo2.getValue(day)) } }
    val measured = repo.resolvedSeries("steps", "my-whoop", from, to, strapDeviceId = strapId).points.map { HealthspanPresentation.StepSample(it.day, it.value, it.source) }
    val importedSteps = listOf("apple-health", "health-connect").flatMap { source -> repo.appleDaily(source, from, to).mapNotNull { p ->
        p.steps?.let { HealthspanPresentation.StepSample(p.day, it.toDouble(), source) }
    } }
    result[HealthspanDriver.steps] = (measured + importedSteps).map { it.day }.distinct().sorted().mapNotNull { day ->
        HealthspanPresentation.latestSteps(measured, importedSteps, day, day)?.let { p -> offset(day)?.let { HealthspanHistory.Sample(it, p.count) } }
    }
    val workouts = stressMonitorWorkoutRows(repo, strapId, System.currentTimeMillis() / 1000L, hrMax, sex, method)
    val activity = HealthspanHistory.activitySeries(workouts.mapNotNull { row ->
        val day = Instant.ofEpochSecond(row.startTs).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        if (day < from || day > to || row.endTs <= row.startTs) null else offset(day)?.let {
            HealthspanHistory.Activity(it, row.durationS ?: (row.endTs - row.startTs).toDouble(), parseZonePercents(row.zonesJSON), HealthspanHistory.isStrength(row.sport, row.source))
        }
    })
    result[HealthspanDriver.lowZones] = activity[0]; result[HealthspanDriver.highZones] = activity[1]; result[HealthspanDriver.strength] = activity[2]
    return result
}

@Composable
private fun healthspanPillarTitle(pillar: Int): String = stringResource(when (pillar) {
    0 -> R.string.healthspan_sleep
    1 -> R.string.healthspan_strain
    else -> R.string.healthspan_fitness
})
