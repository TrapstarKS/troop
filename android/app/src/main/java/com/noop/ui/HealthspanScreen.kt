package com.noop.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.noop.R
import com.noop.analytics.HealthspanPresentation
import com.noop.analytics.HealthspanHistory
import com.noop.data.DailyMetric
import com.noop.data.MetricSeriesRow
import com.noop.widget.StressWidgetProducer
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

@Composable
fun HealthspanScreen(vm: AppViewModel, onCoach: (() -> Unit)? = null) {
    var selectedPillar by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Int?>(null) }
    val days = healthspanDays(vm)
    val bodyAge = healthspanSeries(vm, "body_age")
    var referenceDay by remember { mutableStateOf(LocalDate.now()) }
    val today = LocalDate.now()
    val earliestDay = today.minusDays(HealthspanHistory.oldestReferenceOffset(days.mapNotNull { runCatching { ChronoUnit.DAYS.between(LocalDate.parse(it.day), today).toInt() }.getOrNull() }).toLong())
    LaunchedEffect(earliestDay) { referenceDay = maxOf(referenceDay, earliestDay) }
    val profile = ProfileStore.from(LocalContext.current.applicationContext)
    val dateOfBirth = Instant.ofEpochMilli(profile.dateOfBirthMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    val chronologicalAge = Period.between(dateOfBirth, referenceDay).years.toDouble().coerceAtLeast(0.0)
    val samples = remember(bodyAge, referenceDay) {
        bodyAge.mapNotNull { row ->
            runCatching { ChronoUnit.DAYS.between(LocalDate.parse(row.day), referenceDay).toInt() }
                .getOrNull()?.let { HealthspanPresentation.AgeSample(it, row.value) }
        }
    }
    val recoveryOffsets = healthspanRecoveryOffsets(days, referenceDay)
    val snapshot = remember(samples, recoveryOffsets, chronologicalAge) {
        HealthspanPresentation.snapshot(samples, recoveryOffsets, chronologicalAge)
    }
    val latestDay = bodyAge.lastOrNull {
        it.day <= referenceDay.toString() && it.value.isFinite() && it.value in 20.0..90.0
    }?.day
    val ageText = snapshot.age?.let(::healthspanNumber) ?: "—"
    val stateText = if (snapshot.age != null) stringResource(R.string.healthspan_age_comparison, healthspanNumber(snapshot.age - chronologicalAge))
        else healthspanEligibilityDetail(snapshot.eligibility)

    selectedPillar?.let { pillar ->
        HealthspanContributorFlow(vm, pillar, referenceDay, { selectedPillar = null }, onCoach)
        return
    }

    LazyScreenScaffold(title = stringResource(R.string.healthspan_title), subtitle = stringResource(R.string.healthspan_subtitle)) {
        item {
            HealthDateNavigation(referenceDay, 7, true, earliestDay) { referenceDay = it }
        }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                HealthspanHalo(ageText, stateText, chronologicalAge)
                Text(stateText, style = NoopType.subhead, color = Palette.textSecondary, textAlign = TextAlign.Center)
                if (snapshot.age != null && latestDay != null) {
                    Text(stringResource(R.string.healthspan_estimate_date, healthDateLabel(LocalDate.parse(latestDay))),
                        style = NoopType.caption, color = Palette.textSecondary)
                }
            }
        }
        item { HealthspanPace(snapshot.pace) }
        item {
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                    TrackedSectionHeader(stringResource(R.string.healthspan_how))
                    Text(stringResource(R.string.healthspan_method), style = NoopType.body, color = Palette.textSecondary)
                    Text(stringResource(R.string.healthspan_profile_source), style = NoopType.body, color = Palette.textSecondary)
                    Text(stringResource(R.string.healthspan_initial_method), style = NoopType.body, color = Palette.textSecondary)
                }
            }
        }
        if (snapshot.eligibility.state == HealthspanHistory.State.ready) {
            item { HealthspanAgeTrend(bodyAge, referenceDay) }
        }
        item {
            HealthspanPillarCards { selectedPillar = it }
        }
        item { HealthSupportingMetricCards(vm) }
    }
}

@Composable
fun HealthspanPreviewCard(vm: AppViewModel, onClick: () -> Unit) {
    val days = healthspanDays(vm)
    val bodyAge = healthspanSeries(vm, "body_age")
    val referenceDay = LocalDate.now()
    val age = ProfileStore.from(LocalContext.current.applicationContext).age.toDouble()
    val samples = remember(bodyAge, referenceDay) {
        bodyAge.mapNotNull { row ->
            runCatching { ChronoUnit.DAYS.between(LocalDate.parse(row.day), referenceDay).toInt() }
                .getOrNull()?.let { HealthspanPresentation.AgeSample(it, row.value) }
        }
    }
    val snapshot = HealthspanPresentation.snapshot(samples, healthspanRecoveryOffsets(days, referenceDay), age)
    val detail = if (snapshot.age != null) stringResource(R.string.healthspan_preview_detail)
        else healthspanEligibilityDetail(snapshot.eligibility)
    MetricCard(label = stringResource(R.string.healthspan_title), value = snapshot.age?.let(::healthspanNumber) ?: "—",
        unit = if (snapshot.age != null) stringResource(R.string.healthspan_years) else "", detail = detail,
        color = Palette.positive, icon = Icons.Filled.FavoriteBorder,
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.healthspan_open), onClick = onClick))
}

@Composable
fun HealthSupportingMetricCards(vm: AppViewModel) {
    val days = healthspanDays(vm)
    val computedVo2 = healthspanSeries(vm, "vo2max_est")
    val selectedStrap by vm.activeStrapIdFlow.collectAsStateWithLifecycle()
    val strapId = selectedStrap ?: vm.activeStrapId
    val lifecycleOwner = LocalLifecycleOwner.current
    val reference = LocalDate.now()
    val vo2 = computedVo2.lastOrNull { it.day in reference.minusDays(179).toString()..reference.toString() && it.value.isFinite() && it.value > 0 }
    var importedVo2 by remember(strapId) { mutableStateOf<Pair<String, Double>?>(null) }
    var steps by remember(strapId) { mutableStateOf<HealthspanPresentation.StepSample?>(null) }
    LaunchedEffect(vm, strapId, days, vo2, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    val through = LocalDate.now()
                    withContext(Dispatchers.Default) {
                        val imported = if (vo2 == null) vm.repo.resolvedSeries("vo2max", "apple-health", through.minusDays(179).toString(),
                            through.toString(), strapDeviceId = strapId).points.lastOrNull { it.value.isFinite() && it.value > 0 } else null
                        val from = through.minusDays(30).toString()
                        val recordedSteps = vm.repo.resolvedSeries("steps", "my-whoop", from, through.toString(), strapDeviceId = strapId).points
                        val importedSteps = listOf("apple-health", "health-connect").flatMap { source ->
                            vm.repo.appleDaily(source, from, through.toString()).mapNotNull { row ->
                                row.steps?.let { HealthspanPresentation.StepSample(row.day, it.toDouble(), source) }
                            }
                        }
                        val stepReading = HealthspanPresentation.latestSteps(
                            measured = recordedSteps.map { HealthspanPresentation.StepSample(it.day, it.value, it.source) },
                            imported = importedSteps, fromDay = from, throughDay = through.toString())
                        (imported?.let { it.day to it.value }) to stepReading
                    }.let { (vo2Reading, stepReading) ->
                        importedVo2 = vo2Reading
                        steps = stepReading
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    importedVo2 = null
                    steps = null
                }
                delay(StressWidgetProducer.RESCORE_INTERVAL_MS)
            }
        }
    }
    val vo2Reading = vo2?.let { it.day to it.value } ?: importedVo2
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        MetricCard(stringResource(R.string.healthspan_vo2), vo2Reading?.second?.let(::healthspanNumber) ?: "—", unit = "ml/kg/min",
            detail = vo2Reading?.let { stringResource(if (vo2 != null) R.string.healthspan_estimate_date else R.string.healthspan_recorded_date,
                healthDateLabel(LocalDate.parse(it.first))) } ?: stringResource(R.string.healthspan_no_value), color = Palette.positive)
        MetricCard(stringResource(R.string.healthspan_steps), steps?.count?.let { NumberFormat.getIntegerInstance().format(it) } ?: "—",
            detail = steps?.let {
                val date = stringResource(R.string.healthspan_recorded_date, healthDateLabel(LocalDate.parse(it.day)))
                if (it.source in listOf("apple-health", "health-connect")) stringResource(R.string.l10n_data_sources_screen_imported_434eb26f) + " · " + date else date
            }
                ?: stringResource(R.string.healthspan_no_value), color = Palette.strain100)
    }
}

@Composable
private fun HealthspanHalo(value: String, state: String, chronologicalAge: Double) {
    val label = stringResource(R.string.healthspan_body_age)
    val description = listOf(label, value, stringResource(R.string.healthspan_uncertainty),
        stringResource(R.string.healthspan_chronological), chronologicalAge.toInt().toString(), state).joinToString(", ")
    Box(Modifier.fillMaxWidth().height(Metrics.detailDial + Metrics.space24), contentAlignment = Alignment.Center) {
        Box(Modifier.size(Metrics.detailDial).clearAndSetSemantics { contentDescription = description }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val diameter = size.minDimension
                val radius = diameter / 2
                drawCircle(Brush.radialGradient(listOf(Palette.surfaceBase, Palette.positive.copy(alpha = StrandAlpha.chartFillSoft),
                    Palette.positive.copy(alpha = StrandAlpha.chartMarker)), radius = radius), radius)
                drawCircle(Palette.positive.copy(alpha = StrandAlpha.selectedBorder), radius - Metrics.chartLineWidth.toPx(),
                    style = Stroke(Metrics.chartLineWidth.toPx()))
                for (i in 0 until 160) {
                    val angle = i * 2.3999632297
                    val particleRadius = diameter * (0.27f + 0.22f * ((i * 37) % 101) / 100f)
                    val dot = diameter * (0.003f + 0.006f * (i % 5) / 4f)
                    val point = center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * particleRadius
                    val alpha = StrandAlpha.chartMarker + (StrandAlpha.selectedBorder - StrandAlpha.chartMarker) * (i % 4) / 3f
                    drawCircle(Palette.positive.copy(alpha = alpha), dot / 2, point + Offset(dot / 2, dot / 2))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                Text(value, style = NoopType.dialValueFull, color = Palette.textPrimary)
                Overline(label, color = Palette.textPrimary)
                Text(stringResource(R.string.healthspan_uncertainty), style = NoopType.caption, color = Palette.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space6)) {
                    Text(stringResource(R.string.healthspan_chronological), style = NoopType.caption, color = Palette.textSecondary)
                    Text(chronologicalAge.toInt().toString(), style = NoopType.captionNumber, color = Palette.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun HealthspanAgeTrend(rows: List<MetricSeriesRow>, reference: LocalDate) {
    val firstDay = reference.minusDays(179)
    val spanDays = ChronoUnit.DAYS.between(firstDay, reference).toFloat()
    val points = remember(rows, reference) {
        rows.filter { it.day in firstDay.toString()..reference.toString() && it.value.isFinite() && it.value in 20.0..90.0 }
            .mapNotNull { row -> runCatching { LocalDate.parse(row.day) to row.value }.getOrNull() }
            .sortedBy { it.first }
    }
    var selected by remember(points, reference) { mutableStateOf<Pair<LocalDate, Double>?>(null) }
    val reading = selected ?: points.lastOrNull()
    val description = reading?.let { stringResource(R.string.healthspan_trend_reading, healthDateLabel(it.first), healthspanNumber(it.second)) }
        ?: stringResource(R.string.healthspan_age_unavailable)
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            TrackedSectionHeader(stringResource(R.string.healthspan_trend))
            if (points.isEmpty()) {
                InsetChartPlaceholder(stringResource(R.string.healthspan_age_unavailable))
            } else {
                fun select(x: Float, width: Float) {
                    val day = firstDay.toEpochDay() + (spanDays * (x / width).coerceIn(0f, 1f)).roundToInt()
                    selected = points.minByOrNull { abs(it.first.toEpochDay() - day) }
                }
                Canvas(Modifier.fillMaxWidth().height(Metrics.compactChartHeight).clearAndSetSemantics { contentDescription = description }
                    .pointerInput(points, reference) { detectTapGestures { select(it.x, size.width.toFloat()) } }
                    .pointerInput(points, reference) { detectHorizontalDragGestures(onDragStart = { select(it.x, size.width.toFloat()) }) { change, _ ->
                        change.consume()
                        select(change.position.x, size.width.toFloat())
                    } }) {
                    val lower = points.minOf { it.second } - 5
                    val upper = points.maxOf { it.second } + 5
                    fun position(point: Pair<LocalDate, Double>): Offset = Offset(
                        (ChronoUnit.DAYS.between(firstDay, point.first) / spanDays) * size.width,
                        ((upper - point.second) / (upper - lower)).toFloat() * size.height)
                    for (fraction in listOf(0f, 0.5f, 1f)) {
                        drawLine(Palette.hairline, Offset(0f, fraction * size.height), Offset(size.width, fraction * size.height), Metrics.chartGridWidth.toPx())
                    }
                    points.zipWithNext().forEach { (left, right) ->
                        if (ChronoUnit.DAYS.between(left.first, right.first) <= 14) {
                            drawLine(Palette.positive, position(left), position(right), Metrics.chartLineWidth.toPx())
                        }
                    }
                    points.forEach { drawCircle(Palette.positive, Metrics.chartLineWidth.toPx(), position(it)) }
                    reading?.let { drawCircle(Palette.textPrimary, Metrics.space4.toPx(), position(it)) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(healthDateLabel(firstDay), style = NoopType.caption, color = Palette.textSecondary)
                    Text(healthDateLabel(reference), style = NoopType.caption, color = Palette.textSecondary)
                }
                Text(description, style = NoopType.caption, color = Palette.textSecondary)
            }
        }
    }
}

@Composable
private fun HealthspanPace(pace: Double?) {
    val description = if (pace != null) stringResource(R.string.healthspan_pace_accessible, healthspanNumber(pace))
        else stringResource(R.string.healthspan_pace_unavailable)
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Overline(stringResource(R.string.healthspan_pace))
            Text(if (pace == null) "—" else stringResource(R.string.healthspan_pace_value, healthspanNumber(pace)),
                style = NoopType.tileValueLarge, color = Palette.textPrimary)
            Canvas(Modifier.fillMaxWidth().height(Metrics.motionStripHeight).clearAndSetSemantics { contentDescription = description }) {
                for (tick in 0..40) {
                    val x = size.width * tick / 40f
                    val inset = if (tick % 10 == 0) 0f else size.height / 4
                    drawLine(Palette.hairlineStrong, Offset(x, inset), Offset(x, size.height), Metrics.chartLineWidth.toPx())
                }
                pace?.let {
                    val x = ((it + 1) / 4).coerceIn(0.0, 1.0).toFloat() * size.width
                    drawLine(Palette.positive, Offset(x, 0f), Offset(x, size.height), Metrics.space4.toPx())
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("−1.0×", style = NoopType.captionNumber, color = Palette.textSecondary)
                Text("1.0×", style = NoopType.captionNumber, color = Palette.textSecondary)
                Text("3.0×", style = NoopType.captionNumber, color = Palette.textSecondary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.healthspan_slower), style = NoopType.caption, color = Palette.textSecondary)
                Text(stringResource(R.string.healthspan_faster), style = NoopType.caption, color = Palette.textSecondary)
            }
            Text(description, style = NoopType.caption, color = Palette.textSecondary)
        }
    }
}

@Composable
internal fun HealthDateNavigation(day: LocalDate, stepDays: Long = 1, weekly: Boolean = false, earliestDay: LocalDate = LocalDate.now().minusDays(3999), onSelect: (LocalDate) -> Unit) {
    val context = LocalContext.current
    val today = LocalDate.now()
    val label = if (weekly) stringResource(R.string.healthspan_week_range, healthDateLabel(day.minusDays(6)), healthDateLabel(day))
        else healthDateLabel(day)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onSelect(maxOf(earliestDay, day.minusDays(stepDays))) }, enabled = day > earliestDay) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.healthspan_previous), tint = Palette.textPrimary)
        }
        Text(label, style = NoopType.headline, color = Palette.textPrimary, textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).clickable(onClickLabel = stringResource(R.string.healthspan_pick_date)) {
                DatePickerDialog(context, { _, year, month, date -> onSelect(LocalDate.of(year, month + 1, date)) },
                    day.year, day.monthValue - 1, day.dayOfMonth).apply {
                    datePicker.maxDate = System.currentTimeMillis()
                    datePicker.minDate = earliestDay.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.show()
            }.padding(vertical = Metrics.space12))
        IconButton(onClick = { onSelect(minOf(day.plusDays(stepDays), today)) }, enabled = day < today) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.healthspan_next),
                tint = if (day < today) Palette.textPrimary else Palette.textTertiary)
        }
    }
}

@Composable
private fun healthspanDays(vm: AppViewModel): List<DailyMetric> {
    val selectedStrap by vm.activeStrapIdFlow.collectAsStateWithLifecycle()
    val strapId = selectedStrap ?: vm.activeStrapId
    return key(vm, strapId) {
        val days by remember(vm, strapId) {
            vm.repo.daysMergedRangeFlow(strapId, LocalDate.now().minusDays(3999).toString(), LocalDate.now().toString())
        }.collectAsStateWithLifecycle(initialValue = emptyList())
        days
    }
}

@Composable
private fun healthspanSeries(vm: AppViewModel, metricKey: String): List<MetricSeriesRow> {
    val selectedStrap by vm.activeStrapIdFlow.collectAsStateWithLifecycle()
    val strapId = selectedStrap ?: vm.activeStrapId
    return key(vm, strapId, metricKey) {
        val series by remember(vm, strapId, metricKey) {
            vm.repo.metricSeriesComputedUnionFlow(strapId, metricKey, "0000-01-01", "9999-12-31")
        }.collectAsStateWithLifecycle(initialValue = emptyList())
        series
    }
}

internal fun healthspanNumber(value: Double): String = NumberFormat.getNumberInstance().apply {
    minimumFractionDigits = 1
    maximumFractionDigits = 1
}.format(value)

internal fun healthDateLabel(day: LocalDate): String = day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

@Composable
internal fun healthDuration(minutes: Int): String = stringResource(R.string.healthspan_duration, minutes / 60, minutes % 60)

private fun healthspanRecoveryOffsets(days: List<DailyMetric>, reference: LocalDate): List<Int> = days.mapNotNull { row ->
    row.recovery?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return@mapNotNull null
    runCatching { ChronoUnit.DAYS.between(LocalDate.parse(row.day), reference).toInt() }.getOrNull()
}

@Composable
internal fun healthspanEligibilityDetail(eligibility: HealthspanHistory.Eligibility): String = when (eligibility.state) {
    HealthspanHistory.State.adultOnly -> stringResource(R.string.healthspan_adult_only)
    HealthspanHistory.State.initialCalibration -> stringResource(R.string.healthspan_initial_calibration, eligibility.initialDays.coerceAtMost(90))
    HealthspanHistory.State.recentCoverage -> stringResource(R.string.healthspan_calibrating, eligibility.recoveryDays)
    HealthspanHistory.State.unavailable -> stringResource(R.string.healthspan_age_unavailable)
    HealthspanHistory.State.ready -> stringResource(R.string.healthspan_preview_detail)
}
