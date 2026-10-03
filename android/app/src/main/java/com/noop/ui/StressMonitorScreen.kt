package com.noop.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.noop.R
import com.noop.analytics.DaytimeStress
import com.noop.analytics.HealthspanPresentation
import com.noop.data.WhoopRepository
import com.noop.data.WorkoutRow
import com.noop.widget.StressWidgetProducer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class StressRecordedEvent(val start: Long, val end: Long, val sleep: Boolean)

private data class StressMonitorData(
    val window: StressLocalDayWindow,
    val daytime: DaytimeStress.Result,
    val observationFrom: Long?,
    val observationTo: Long?,
    val events: List<StressRecordedEvent>,
    val dailyScore: Double?,
    val personalBaseline: Boolean,
    val readFailed: Boolean = false,
)

@Composable
fun StressMonitorScreen(vm: AppViewModel, onBreathe: () -> Unit) {
    var showHistory by remember { mutableStateOf(false) }
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    val selectedStrap by vm.activeStrapIdFlow.collectAsStateWithLifecycle()
    val strapId = selectedStrap ?: vm.activeStrapId
    val context = LocalContext.current
    val personalBaseline = NoopPrefs.stressPersonalBaseline(context)
    val lifecycleOwner = LocalLifecycleOwner.current
    var selectedDay by remember { mutableStateOf(LocalDate.now()) }
    var data by remember(selectedDay, strapId) { mutableStateOf<StressMonitorData?>(null) }
    var nowSeconds by remember { mutableLongStateOf(System.currentTimeMillis() / 1000L) }
    var selectedTimestamp by remember(selectedDay, strapId) { mutableStateOf<Long?>(null) }

    BackHandler(enabled = showHistory) { showHistory = false }
    if (showHistory) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { showHistory = false }, modifier = Modifier.padding(horizontal = Metrics.screenPadding, vertical = Metrics.space8)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = Palette.textPrimary, modifier = Modifier.size(Metrics.space24))
                    Text(stringResource(R.string.l10n_onboarding_screen_back_b52b36b7), style = NoopType.headline, color = Palette.textPrimary)
                }
            }
            Box(Modifier.weight(1f)) { StressScreen(vm, onBreathe) }
        }
        return
    }

    LaunchedEffect(vm) { vm.loadWorkouts() }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                nowSeconds = System.currentTimeMillis() / 1000L
                delay(60_000)
            }
        }
    }
    LaunchedEffect(vm, strapId, selectedDay, days, workouts, personalBaseline, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                val now = System.currentTimeMillis() / 1000L
                val window = stressLocalDayWindow(selectedDay, ZoneId.systemDefault())
                try {
                    data = loadStressMonitorData(vm, strapId, selectedDay, workouts, personalBaseline, now)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    data = StressMonitorData(window, DaytimeStress.Result.EMPTY, null, null, emptyList(), null, false, readFailed = true)
                }
                if (selectedDay == LocalDate.now()) delay(StressWidgetProducer.RESCORE_INTERVAL_MS)
            } while (selectedDay == LocalDate.now())
        }
    }

    LazyScreenScaffold(title = stringResource(R.string.stress_monitor_title), subtitle = stringResource(R.string.stress_monitor_subtitle)) {
        item { HealthDateNavigation(selectedDay) { selectedDay = it } }
        item { StressMonitorHero(data, nowSeconds, selectedTimestamp) }
        item { StressMonitorTimeline(data, selectedTimestamp) { selectedTimestamp = it } }
        item {
            InsightCallout(stringResource(R.string.stress_monitor_breathe_body),
                actionLabel = stringResource(R.string.stress_monitor_breathe), onAction = onBreathe)
        }
        item { StressMonitorZones(data) }
        val snapshot = data
        if (snapshot?.dailyScore != null) {
            item {
                MetricCard(stringResource(R.string.stress_monitor_daily), healthspanNumber(snapshot.dailyScore), unit = "/ 3",
                    detail = stringResource(R.string.stress_monitor_daily_detail, healthDateLabel(selectedDay)),
                    color = stressMonitorColor(snapshot.dailyScore), icon = Icons.Filled.MonitorHeart)
            }
        }
        item {
            Text(stringResource(R.string.stress_monitor_method), style = NoopType.caption, color = Palette.textSecondary)
        }
        item {
            TextButton(onClick = { showHistory = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.stress_monitor_history), style = NoopType.headline, color = Palette.textPrimary)
            }
        }
    }
}

@Composable
fun StressMonitorPreviewCard(vm: AppViewModel, onClick: () -> Unit) {
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val selectedStrap by vm.activeStrapIdFlow.collectAsStateWithLifecycle()
    val strapId = selectedStrap ?: vm.activeStrapId
    val lifecycleOwner = LocalLifecycleOwner.current
    var daily by remember(strapId) { mutableStateOf<Pair<String, Double>?>(null) }
    LaunchedEffect(vm, strapId, days, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    val today = LocalDate.now()
                    daily = withContext(Dispatchers.Default) {
                        readStoredStress(vm, strapId, today.minusDays(179), today).entries.maxByOrNull { it.key }?.let { it.key to it.value }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    daily = null
                }
                delay(StressWidgetProducer.RESCORE_INTERVAL_MS)
            }
        }
    }
    val reading = daily
    MetricCard(stringResource(R.string.stress_monitor_title), reading?.second?.let(::healthspanNumber) ?: "—",
        unit = if (reading != null) "/ 3" else "",
        detail = reading?.let { stringResource(R.string.stress_monitor_daily_preview, healthDateLabel(LocalDate.parse(it.first))) }
            ?: stringResource(R.string.stress_monitor_no_daily),
        icon = Icons.Filled.MonitorHeart, color = reading?.second?.let(::stressMonitorColor) ?: Palette.textSecondary,
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.stress_monitor_open), onClick = onClick))
}

private suspend fun loadStressMonitorData(
    vm: AppViewModel,
    strapId: String,
    selectedDay: LocalDate,
    workouts: List<WorkoutRow>,
    personalBaseline: Boolean,
    now: Long,
): StressMonitorData = withContext(Dispatchers.Default) {
    val zone = ZoneId.systemDefault()
    val window = stressLocalDayWindow(selectedDay, zone)
    val end = minOf(window.toEpochSecondInclusive, now)
    val hr = vm.repo.hrSamplesUnion(strapId, window.fromEpochSecond, end, limit = 200_000)
    val observationFrom = hr.minOfOrNull { it.ts }
    val observationTo = hr.maxOfOrNull { it.ts }
    val rr = if (hr.size >= DaytimeStress.minHourHrSamples) vm.repo.rrIntervalsUnion(strapId, window.fromEpochSecond, end, limit = 200_000) else emptyList()
    val gravity = if (hr.size >= DaytimeStress.minHourHrSamples) vm.repo.gravitySamplesUnion(strapId, window.fromEpochSecond, end, limit = 200_000) else emptyList()
    val mode = if (hr.size >= DaytimeStress.minHourHrSamples) selectedDaytimeStressMode(vm.repo, strapId, selectedDay, zone, personalBaseline)
        else DaytimeStress.ScoringMode.DayRelative
    val daytime = if (hr.size >= DaytimeStress.minHourHrSamples) {
        DaytimeStress.analyze(hr, rr, gravity, window.offsetSeconds.toLong(), mode, includeTimeline = true)
    } else DaytimeStress.Result.EMPTY
    val sleepFrom = selectedDay.minusDays(2).atStartOfDay(zone).toEpochSecond()
    val importedSleep = vm.repo.sleepSessionsUnion(strapId, sleepFrom, end)
    val computedSleep = vm.repo.computedSleepSessionsUnion(strapId, sleepFrom, end)
    val sleep = WhoopRepository.mergeSleep(importedSleep, computedSleep)
    val events = (sleep.map { StressRecordedEvent(it.effectiveStartTs, it.endTs, true) } +
        workouts.map { StressRecordedEvent(it.startTs, it.endTs, false) })
        .filter { it.start < end && it.end > window.fromEpochSecond && it.end > it.start }
    val stored = readStoredStress(vm, strapId, selectedDay, selectedDay)
    val dailyScore = stored[selectedDay.toString()]
    StressMonitorData(window, daytime, observationFrom, observationTo, events, dailyScore, mode is DaytimeStress.ScoringMode.BaselineRelative)
}

private suspend fun readStoredStress(vm: AppViewModel, strapId: String, from: LocalDate, to: LocalDate): Map<String, Double> =
    vm.repo.resolvedSeries("stress", "my-whoop", from.toString(), to.toString(), strapDeviceId = strapId)
        .points.filter { it.value.isFinite() && it.value in 0.0..3.0 }.associate { it.day to it.value }

@Composable
private fun StressMonitorHero(data: StressMonitorData?, nowSeconds: Long, selectedTimestamp: Long?) {
    val latest = data?.daytime?.timeline?.lastOrNull { it.level?.let { value -> value.isFinite() && value in 0.0..3.0 } == true }
    val selected = selectedTimestamp?.let { stamp -> data?.daytime?.timeline?.minByOrNull { abs(it.startTs - stamp) } }
    val latestEnd = latest?.let { minOf(it.startTs + DaytimeStress.bucketSeconds, data?.observationTo ?: it.startTs) }
    val stale = data?.window?.day == LocalDate.now() && latestEnd != null && nowSeconds - latestEnd > StressWidgetProducer.RESCORE_INTERVAL_MS / 1000L
    val current = selected ?: latest.takeUnless { stale }
    val score = current?.level
    val band = stressMonitorBandLabel(score)
    val state = when {
        data == null -> stringResource(R.string.stress_monitor_loading)
        data.readFailed -> stringResource(R.string.stress_monitor_read_failed)
        current?.level == null -> stringResource(R.string.stress_monitor_no_current)
        else -> stringResource(if (selected != null) R.string.stress_monitor_selected_window_time else R.string.stress_monitor_window,
            stressMonitorTime(current.startTs), stressMonitorTime(minOf(current.startTs + DaytimeStress.bucketSeconds, data.observationTo ?: current.startTs)))
    }
    val description = stringResource(R.string.stress_monitor_gauge_accessible, score?.let(::healthspanNumber) ?: "—", band, state)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
        Box(Modifier.size(Metrics.detailDial).clearAndSetSemantics { contentDescription = description }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val width = Metrics.detailDialStroke.toPx()
                val origin = Offset(width / 2, width / 2)
                val arcSize = Size(size.width - width, size.height - width)
                drawArc(Palette.ringTrack, 150f, 240f, false, origin, arcSize, style = Stroke(width, cap = StrokeCap.Round))
                for (segment in 0 until 120) {
                    drawArc(stressMonitorColor((segment + 0.5) / 40), 150f + segment * 2, 2.2f, false,
                        origin, arcSize, style = Stroke(Metrics.compactDialStroke.toPx()))
                }
                score?.let {
                    val angle = Math.toRadians(150 + it / 3 * 240)
                    val center = Offset(size.width / 2, size.height / 2)
                    val radius = (size.minDimension - width) / 2
                    val vector = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                    drawLine(Palette.textPrimary, center + vector * (radius - width), center + vector * (radius + width / 2),
                        Metrics.space4.toPx(), cap = StrokeCap.Round)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                Text(score?.let(::healthspanNumber) ?: "—", style = NoopType.dialValueFull, color = Palette.textPrimary)
                Overline(band, color = score?.let(::stressMonitorColor) ?: Palette.textSecondary)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Metrics.space24), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0.0", style = NoopType.captionNumber, color = Palette.textSecondary)
            Text("3.0", style = NoopType.captionNumber, color = Palette.textSecondary)
        }
        Text(state, style = NoopType.caption, color = Palette.textSecondary, textAlign = TextAlign.Center)
        if (data != null && !data.readFailed && current?.level == null) {
            Text(stringResource(if (selected?.maskedForActivity == true) R.string.stress_monitor_activity_masked
                else if (selected != null) R.string.stress_monitor_gap else R.string.stress_monitor_calibrating),
                style = NoopType.caption, color = Palette.textSecondary, textAlign = TextAlign.Center)
        }
        data?.observationTo?.let { last ->
            Text(stringResource(if (stale) R.string.stress_monitor_stale else R.string.stress_monitor_recorded, stressMonitorTime(last)),
                style = NoopType.caption, color = if (stale) Palette.stressHigh else Palette.textSecondary)
        }
    }
}

@Composable
private fun StressMonitorTimeline(data: StressMonitorData?, selectedTimestamp: Long?, onSelectTimestamp: (Long?) -> Unit) {
    val points = data?.daytime?.timeline.orEmpty()
    val selected = selectedTimestamp?.let { stamp -> points.minByOrNull { abs(it.startTs - stamp) } }
    val selectedText = selected?.let {
        val value = it.level?.let(::healthspanNumber) ?: stringResource(if (it.maskedForActivity) R.string.stress_monitor_activity_masked else R.string.stress_monitor_gap)
        stringResource(R.string.stress_monitor_selected, stressMonitorTime(it.startTs), value)
    } ?: stringResource(R.string.stress_monitor_inspect)
    val accessibility = stringResource(R.string.stress_monitor_timeline_accessible, points.count { it.level != null }, selectedText)
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            TrackedSectionHeader(stringResource(R.string.stress_monitor_timeline))
            when {
                data == null -> InsetChartPlaceholder(stringResource(R.string.stress_monitor_loading))
                data.readFailed -> InsetChartPlaceholder(stringResource(R.string.stress_monitor_read_failed))
                points.isEmpty() -> InsetChartPlaceholder(stringResource(R.string.stress_monitor_no_timeline))
                else -> {
                    val from = data.window.fromEpochSecond
                    val to = data.window.toEpochSecondInclusive + 1
                    val span = (to - from).toFloat()
                    fun timestamp(x: Float, width: Float): Long = from + (span * (x / width).coerceIn(0f, 1f)).toLong()
                    Canvas(Modifier.fillMaxWidth().height(Metrics.chartHeight)
                        .clearAndSetSemantics { contentDescription = accessibility }
                        .pointerInput(data) { detectTapGestures { onSelectTimestamp(timestamp(it.x, size.width.toFloat())) } }
                        .pointerInput(data) { detectDragGestures(onDragStart = { onSelectTimestamp(timestamp(it.x, size.width.toFloat())) }) { change, _ ->
                            change.consume()
                            onSelectTimestamp(timestamp(change.position.x, size.width.toFloat()))
                        } }) {
                        val top = Metrics.space16.toPx()
                        val plotHeight = size.height - top
                        fun x(stamp: Long): Float = ((stamp - from) / span * size.width).coerceIn(0f, size.width)
                        fun y(level: Double): Float = top + (1 - level.coerceIn(0.0, 3.0).toFloat() / 3) * plotHeight
                        for (level in 0..3) drawLine(Palette.hairline, Offset(0f, y(level.toDouble())), Offset(size.width, y(level.toDouble())), Metrics.chartGridWidth.toPx())
                        for (event in data.events) {
                            val eventColor = if (event.sleep) Palette.restColor else Palette.strain100
                            drawRect(eventColor.copy(alpha = StrandAlpha.selectedFill), Offset(x(event.start), top), Size((x(event.end) - x(event.start)).coerceAtLeast(0f), plotHeight))
                            drawLine(eventColor, Offset(x(event.start), top), Offset(x(event.end), top), Metrics.chartLineWidth.toPx())
                        }
                        for ((left, right) in points.zipWithNext()) {
                            val first = left.level ?: continue
                            val second = right.level ?: continue
                            if (right.startTs - left.startTs > DaytimeStress.timelineStepSeconds) continue
                            drawLine(stressMonitorColor((first + second) / 2), Offset(x(left.startTs), y(first)), Offset(x(right.startTs), y(second)),
                                Metrics.chartLineWidth.toPx(), cap = StrokeCap.Round)
                        }
                        for (point in points) point.level?.let { drawCircle(stressMonitorColor(it), Metrics.chartLineWidth.toPx(), Offset(x(point.startTs), y(it))) }
                        selected?.let {
                            drawLine(Palette.textSecondary, Offset(x(it.startTs), top), Offset(x(it.startTs), size.height), Metrics.chartGridWidth.toPx())
                            it.level?.let { value -> drawCircle(Palette.textPrimary, Metrics.space4.toPx(), Offset(x(it.startTs), y(value))) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf(0, 6, 12, 18).forEach { hour -> Text(stressMonitorTime(from + hour * 3600L), style = NoopType.captionNumber, color = Palette.textSecondary) }
                    }
                }
            }
            Text(selectedText, style = NoopType.caption, color = Palette.textSecondary)
            if (selectedTimestamp != null) {
                TextButton(onClick = { onSelectTimestamp(null) }) {
                    Text(stringResource(R.string.stress_monitor_latest), style = NoopType.caption, color = Palette.textPrimary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space16)) {
                StressEventLegend(true)
                StressEventLegend(false)
            }
            Text(stringResource(R.string.stress_monitor_gaps_note), style = NoopType.caption, color = Palette.textSecondary)
        }
    }
}

@Composable
private fun StressMonitorZones(data: StressMonitorData?) {
    val from = data?.observationFrom
    val to = data?.observationTo
    val durations = if (data != null && from != null && to != null) HealthspanPresentation.zoneMinutes(data.daytime.hours.map { hour ->
        val seconds = (minOf(hour.startTs + DaytimeStress.bucketSeconds, to + 1) - maxOf(hour.startTs, from)).coerceAtLeast(0)
        hour.level to (seconds / 60).toInt()
    }) else listOf(0, 0, 0)
    val total = durations.sum()
    val labels = listOf(stringResource(R.string.stress_monitor_low), stringResource(R.string.stress_monitor_medium), stringResource(R.string.stress_monitor_high))
    val colors = listOf(Palette.stressLow, Palette.stressMedium, Palette.stressHigh)
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            TrackedSectionHeader(stringResource(R.string.stress_monitor_zones), microLabel = stringResource(R.string.stress_monitor_observed))
            if (total == 0) {
                Text(stringResource(R.string.stress_monitor_no_durations), style = NoopType.body, color = Palette.textSecondary)
            } else {
                Canvas(Modifier.fillMaxWidth().height(Metrics.segmentBarHeight).clearAndSetSemantics {}) {
                    var x = 0f
                    durations.forEachIndexed { index, minutes ->
                        val width = size.width * minutes / total.toFloat()
                        drawRect(colors[index], Offset(x, 0f), Size(width, size.height))
                        x += width
                    }
                }
                durations.forEachIndexed { index, minutes ->
                    ContributorRow(labels[index], healthDuration(minutes), comparisonColor = colors[index])
                }
            }
            Text(stringResource(R.string.stress_monitor_duration_note), style = NoopType.caption, color = Palette.textSecondary)
            if (data?.daytime?.activityMaskedHours != null && data.daytime.activityMaskedHours > 0) {
                Text(stringResource(R.string.stress_monitor_masked_hours, data.daytime.activityMaskedHours), style = NoopType.caption, color = Palette.textSecondary)
            }
        }
    }
}

@Composable
private fun StressEventLegend(sleep: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space6)) {
        Icon(if (sleep) Icons.Filled.Bedtime else Icons.Filled.DirectionsRun, null,
            tint = if (sleep) Palette.restColor else Palette.strain100, modifier = Modifier.size(Metrics.iconSmall))
        Text(stringResource(if (sleep) R.string.stress_monitor_sleep_event else R.string.stress_monitor_workout_event), style = NoopType.caption, color = Palette.textSecondary)
    }
}

@Composable
private fun stressMonitorBandLabel(score: Double?): String = stringResource(when {
    score == null -> R.string.stress_monitor_unavailable
    StressBand.forScore(score) == StressBand.Low -> R.string.stress_monitor_low
    StressBand.forScore(score) == StressBand.Medium -> R.string.stress_monitor_medium
    else -> R.string.stress_monitor_high
})

private fun stressMonitorColor(value: Double): Color = Palette.sample(listOf(0f to Palette.stressLow, 0.5f to Palette.stressMedium, 1f to Palette.stressHigh), (value / 3).toFloat())

private fun stressMonitorTime(epochSecond: Long): String = Instant.ofEpochSecond(epochSecond).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
