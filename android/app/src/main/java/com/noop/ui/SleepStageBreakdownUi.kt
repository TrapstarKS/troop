package com.noop.ui

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noop.R
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * The four WHOOP-style stage rows that replace the old "label · value" footer grid, read like WHOOP's
 * sleep detail: a colour swatch, the UPPERCASE stage name, the share-of-night % in the stage colour, a
 * segmented [PipBar] (the NOOP signature) tinted in the stage colour, and the right-aligned duration.
 * Ordered by chart depth (awake / REM / light / deep); the values and colours stay attached to their
 * stage. Mirrors macOS SleepView.stageBreakdownRows. (PipBar)
 */
@Composable
internal fun StageBreakdownRows(s: Stages, palette: SleepStagePalette = SleepStagePalette.NOOP) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
        StageBreakdownRow("Awake", s.awake, s.total, stageColorForRamp("Awake", palette), stageSharePercent("Awake", s))
        StageBreakdownRow("REM", s.rem, s.total, stageColorForRamp("REM", palette), stageSharePercent("REM", s))
        StageBreakdownRow("Light", s.light, s.total, stageColorForRamp("Light", palette), stageSharePercent("Light", s))
        StageBreakdownRow("Deep", s.deep, s.total, stageColorForRamp("Deep", palette), stageSharePercent("Deep", s))
    }
}

@Composable
internal fun localizedSleepStage(stage: String): String = stringResource(when (canonicalStage(stage)) {
    "awake" -> R.string.whoop_sleep_awake
    "rem" -> R.string.whoop_sleep_rem
    "light" -> R.string.whoop_sleep_light
    "deep" -> R.string.whoop_sleep_deep
    else -> R.string.whoop_sleep_stage_unrecorded
})

@Composable
internal fun SleepStageTotalsBar(stages: Stages) {
    Canvas(Modifier.fillMaxWidth().height(Metrics.stageStripHeight)) {
        val totals = listOf(stages.awake to Palette.sleepAwake, stages.rem to Palette.sleepREM,
            stages.light to Palette.sleepLight, stages.deep to Palette.sleepDeep)
        val total = totals.sumOf { it.first.coerceAtLeast(0.0) }
        if (total <= 0.0) return@Canvas
        var x = 0f
        totals.forEach { (minutes, color) ->
            val width = (minutes.coerceAtLeast(0.0) / total * size.width).toFloat()
            drawRect(color, Offset(x, 0f), Size(width, size.height))
            x += width
        }
    }
}

/**
 * One WHOOP-style stage row. `fraction = minutes / total` sets the PipBar fill; `percent` is the night's
 * apportioned share (so the four rows sum to 100). Mirrors the macOS SleepView.stageBreakdownRow.
 */
@Composable
private fun StageBreakdownRow(stage: String, minutes: Double, total: Double, color: Color, percent: Int) {
    val label = localizedSleepStage(stage)
    val fraction = if (total > 0.0) (minutes / total).coerceIn(0.0, 1.0) else 0.0
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription =
                    uiString(R.string.l10n_sleep_screen_stage_durationtext_minutes_percent_percent_of_477dbf14, label, durationText(minutes), percent)
            },
    ) {
        Box(
            modifier = Modifier
                .size(Metrics.space12)
                .clip(RoundedCornerShape(Metrics.cornerXs))
                .background(color),
        )
        Text(
            label.uppercase(Locale.getDefault()),
            style = NoopType.overline,
            color = Palette.textPrimary,
            maxLines = 1,
            modifier = Modifier.width(Metrics.space24 + Metrics.space24 + Metrics.space8),
        )
        Text(
            uiString(R.string.l10n_sleep_screen_percent_2281d326, percent),
            style = NoopType.captionNumber,
            color = color,
            maxLines = 1,
            modifier = Modifier.width(Metrics.space24 + Metrics.space14),
        )
        PipBar(
            value = (fraction * 100).toFloat(),
            segments = 20,
            tint = color,
            height = Metrics.space8,
            modifier = Modifier.weight(1f),
        )
        Text(
            durationText(minutes),
            style = NoopType.captionNumber,
            color = Palette.textPrimary,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(Metrics.space24 + Metrics.space24 + Metrics.space12),
        )
    }
}

/**
 * The hero hypnogram strip plus an optional onset · midpoint · wake time axis. Mirrors the Swift
 * Hypnogram(showsTimeAxis:): a proportional stage strip with a per-segment WIDTH floor (so a brief
 * stage — especially a short Awake blip — reads as a rounded block, not a hairline tick), three
 * faint vertical hairlines at frac 0 / 0.5 / 1.0, and a clock-label row underneath. The axis only
 * appears when the session supplies onset/wake timestamps; otherwise this is just the floored strip.
 * Presentation-only — the segment weights and stage→colour mapping are unchanged.
 */
@Composable
internal fun HypnogramWithAxis(
    stages: List<Pair<String, Float>>,
    onsetTs: Long?,
    wakeTs: Long?,
) {
    val showsAxis = onsetTs != null && wakeTs != null
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(Metrics.stageStripHeight)) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            // Inset well so the strip reads as a recessed track (matches the shared Hypnogram).
            drawLine(
                color = Palette.surfaceInset,
                start = Offset(0f, h / 2f),
                end = Offset(w, h / 2f),
                strokeWidth = h,
                cap = StrokeCap.Round,
            )

            val weights = stages.map { it.second }.map { if (it.isFinite() && it > 0f) it else 0f }
            val total = weights.sum()
            if (stages.isEmpty() || total <= 0f) return@Canvas

            // WIDTH floor: a segment narrower than this reads as a hairline, so floor short stages to a
            // legible block. But the FLOORED widths can sum past the canvas on a fragmented night (many
            // short segments), and the old loop advanced `x` by the floored width — so the tail ran off
            // the canvas and clipped, leaving only the first ~w/h segments visible as a row of circles
            // (#36). Fix: floor every segment, then if the floored total overflows, scale them ALL to fit
            // so the strip stays a continuous bar for the WHOLE night. Draw rounded RECTS (not round-capped
            // lines, whose h-wide round cap turned any sub-h segment into a full circle) advancing by the
            // SAME width we draw, so `x` can never exceed the canvas.
            val minSegW = h / 2f
            val floored = weights.map { wt -> if (wt > 0f) maxOf(w * (wt / total), minSegW) else 0f }
            val flooredSum = floored.sum()
            val scale = if (flooredSum > w) w / flooredSum else 1f
            val radius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
            var x = 0f
            stages.forEachIndexed { i, (name, _) ->
                val segW = floored[i] * scale
                if (segW <= 0f) return@forEachIndexed
                drawRoundRect(
                    color = stageColorFor(name),
                    topLeft = Offset(x, 0f),
                    size = Size(segW.coerceAtMost(w - x), h),
                    cornerRadius = radius,
                )
                x += segW
            }

            // Time-axis vertical hairlines: onset · midpoint · wake.
            if (showsAxis) {
                listOf(0f, 0.5f, 1f).forEach { frac ->
                    val hx = w * frac
                    drawLine(
                        color = Palette.hairline,
                        start = Offset(hx, 0f),
                        end = Offset(hx, h),
                        strokeWidth = 1f,
                    )
                }
            }
        }
        if (showsAxis && onsetTs != null && wakeTs != null) {
            ClockLabelRow(onsetTs, wakeTs)
        }
    }
}

/** Recorded sleep stages at their actual timestamps. Unknown intervals remain gaps; the inspector
 * reports half-open source intervals without smoothing brief stages into another classification. */
@Composable
internal fun FilledHypnogram(
    segments: List<PersistedSegment>,
    onsetTs: Long?,
    wakeTs: Long?,
    // true → each stage FILLS its column to the baseline (the stepped-area look). false → a slim uniform
    // RIBBON at each stage level (the WHOOP-style stepped line), which reads cleaner on a fragmented night.
    filled: Boolean = true,
    // The stage-colour ramp: NOOP tokens (Fill), Garmin's (Garmin Fill), or Oura's (Ribbon).
    palette: SleepStagePalette = SleepStagePalette.NOOP,
) {
    if (segments.isEmpty()) return
    val originSec = (onsetTs?.toDouble()) ?: segments.minOf { it.start }.toDouble()
    val endSec = (wakeTs?.toDouble()) ?: segments.maxOf { it.end }.toDouble()
    val spanSec = (endSec - originSec).coerceAtLeast(1.0)
    val intervals = remember(segments, originSec, spanSec) {
        segments.sortedBy { it.start }.mapNotNull { segment ->
            val start = (segment.start - originSec).coerceIn(0.0, spanSec)
            val end = (segment.end - originSec).coerceIn(0.0, spanSec)
            val stage = canonicalStage(segment.stage)
            if (end > start && stage in listOf("awake", "rem", "light", "deep")) StageInterval(stage, start, end)
            else null
        }
    }
    val showsAxis = onsetTs != null && wakeTs != null
    val axSummary = stringResource(R.string.whoop_sleep_stage_timeline)
    // Responsive time axis: exact onset/wake at the edges + round-hour marks between, MORE marks on a wider
    // screen. Empty when the night has no clock window (no axis then).
    // ~60dp per label so a phone (~360dp) budgets ~6 -> fills the interior with round-hour marks instead of
    // stranding the axis at just onset/mid/wake; a tablet fans out to the 8-label ceiling. Floor 4 keeps a
    // narrow phone from collapsing back to bare edges.
    val maxAxisLabels = (LocalConfiguration.current.screenWidthDp / 60).coerceIn(4, 8)
    // #1821: the reader's CHOSEN clock, not the raw device switch. Reading the device here would have
    // left the hypnogram axis in 24h while the sleep card above it changed - the setting half-applied.
    val is24h = ClockPrefs.uses24Hour(LocalContext.current)
    val axisTicks = if (showsAxis) hypnogramAxisTicks(onsetTs!!, wakeTs!!, maxAxisLabels, is24h) else emptyList()
    var selectionFraction by remember(originSec) { mutableStateOf<Float?>(null) }
    val scrub = selectionFraction?.let { scrubHitAt(it, 1f, intervals, originSec, spanSec) }
    // The crosshair tracks the FINGER. Snapping it to the resolved segment would put the line up to
    // half a segment from the touch while the readout named the time where the finger actually was.
    val currentStageLabel = localizedSleepStage(scrub?.stage.orEmpty())
    val scrubState = scrub?.let { "${clockTimeLabel(it.timestamp, is24h)}, $currentStageLabel" }
    fun inspectFraction(fraction: Float) {
        selectionFraction = fraction.coerceIn(0f, 1f)
    }
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
        Text(stringResource(R.string.whoop_sleep_scrub_hint), style = NoopType.footnote, color = Palette.textSecondary)
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(Metrics.sparkWidth).height(Metrics.compactChartHeight),
                verticalArrangement = Arrangement.SpaceAround) {
                listOf("awake", "rem", "light", "deep").forEach { stage ->
                    Text(localizedSleepStage(stage), style = NoopType.footnote, color = Palette.textSecondary,
                        maxLines = 2)
                }
            }
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(Metrics.compactChartHeight)
                .semantics {
                    contentDescription = axSummary
                    if (scrubState != null) stateDescription = scrubState
                    if (showsAxis) {
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            selectionFraction ?: 0f,
                            0f..1f,
                        )
                        setProgress { inspectFraction(it); true }
                    }
                }
                .onKeyEvent { event ->
                    if (!showsAxis || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val fraction = selectionFraction ?: 0f
                    when (event.key) {
                        Key.DirectionLeft -> inspectFraction(fraction - (60.0 / spanSec).toFloat())
                        Key.DirectionRight -> inspectFraction(fraction + (60.0 / spanSec).toFloat())
                        Key.MoveHome -> inspectFraction(0f)
                        Key.MoveEnd -> inspectFraction(1f)
                        else -> return@onKeyEvent false
                    }
                    true
                }
                .focusable()
                .then(
                    // #1855: tap or drag to read the clock time under your finger. Offered only when the
                    // night supplies a clock window, because without one there is no real time to
                    // report and a number would have to be invented.
                    if (showsAxis) {
                        Modifier.pointerInput(intervals, originSec, spanSec) {
                            fun inspect(x: Float) {
                                if (size.width > 0) inspectFraction(x / size.width.toFloat())
                            }
                            detectHorizontalDragGestures(
                                onDragStart = { inspect(it.x) },
                                onDragEnd = {},
                                onDragCancel = {},
                                onHorizontalDrag = { change, _ ->
                                    inspect(change.position.x)
                                    change.consume()
                                },
                            )
                        }.pointerInput(intervals, originSec, spanSec) {
                            detectTapGestures(onTap = { position ->
                                if (size.width > 0) inspectFraction(position.x / size.width.toFloat())
                            })
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f || intervals.isEmpty()) return@Canvas
            val rowStep = h / 4f
            fun levelY(rank: Int): Float = rowStep * (rank + 0.5f)
            fun rankOf(stage: String): Int = when (canonicalStage(stage)) {
                "awake" -> 0
                "rem" -> 1
                "light" -> 2
                "deep" -> 3
                else -> 2
            }
            fun xOf(sec: Double): Float = (w * (sec / spanSec)).toFloat().coerceIn(0f, w)

            // Faint per-stage lane guides so height → stage reads even across gaps (mirrors the iOS lanes).
            for (rank in 0 until 4) {
                val y = levelY(rank)
                drawLine(
                    color = Palette.hairline.copy(alpha = StrandAlpha.chartMarker),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = Metrics.chartGridWidth.toPx(),
                )
            }
            // FILLED: each stage from its level DOWN to the baseline (sharp rects tile seamlessly into one
            // continuous staircase). RIBBON: a slim uniform band centred at the stage level — the WHOOP-style
            // stepped line, lighter on a fragmented night where full columns amplify the noise.
            val ribbonThickness = Metrics.progressHeight.toPx()
            intervals.forEach { iv ->
                val x0 = xOf(iv.startSec)
                val x1 = xOf(iv.endSec)
                val y = levelY(rankOf(iv.stage))
                val segW = (x1 - x0).coerceAtLeast(0f).coerceAtMost(w - x0)
                if (filled) {
                    drawRect(
                        color = stageColorForRamp(iv.stage, palette),
                        topLeft = Offset(x0, y),
                        size = Size(segW, (h - y).coerceAtLeast(0f)),
                    )
                } else {
                    drawRect(
                        color = stageColorForRamp(iv.stage, palette),
                        topLeft = Offset(x0, y - ribbonThickness / 2f),
                        size = Size(segW, ribbonThickness),
                    )
                }
            }
            // Connecting risers tracing the staircase between consecutive column tops.
            for (i in 0 until intervals.size - 1) {
                val a = intervals[i]
                val b = intervals[i + 1]
                if (b.startSec - a.endSec > 1.0) continue
                val x = xOf(b.startSec)
                drawLine(
                    color = Palette.textTertiary.copy(alpha = StrandAlpha.chartMarker),
                    start = Offset(x, levelY(rankOf(a.stage))),
                    end = Offset(x, levelY(rankOf(b.stage))),
                    strokeWidth = Metrics.chartLineWidth.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            // Time-axis vertical hairlines at each label tick (onset · round hours · wake).
            axisTicks.forEach { (frac, _) ->
                val hx = w * frac
                drawLine(
                    color = Palette.hairline,
                    start = Offset(hx, 0f),
                    end = Offset(hx, h),
                    strokeWidth = 1f,
                )
            }

            // Scrub crosshair LAST, so it sits over the filled staircase. In FILLED mode every stage
            // paints from its level down to the baseline, so a crosshair drawn earlier is covered by
            // the next rect and effectively invisible across most of the chart.
            if (scrub != null) {
                val cx = ((selectionFraction ?: 0f) * w).coerceIn(0f, w)
                drawLine(
                    color = Palette.textPrimary,
                    start = Offset(cx, 0f),
                    end = Offset(cx, h),
                    strokeWidth = Metrics.chartLineWidth.toPx(),
                )
            }
        }
        }
        val hit = scrub
        if (hit != null) {
            // Replaces the time axis rather than adding a layer, so the chart does not change height
            // mid-drag. Two Texts with spacing, not one string joined by a literal separator: that
            // separator would be new hardcoded copy, and building it outside the Text() call to dodge
            // extraction is the pattern #557 / #922 track. Stage names and clockTimeLabel are both
            // existing, so this adds no new strings.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    Metrics.space6,
                    Alignment.CenterHorizontally,
                ),
            ) {
                Text(
                    localizedSleepStage(hit.stage).uppercase(Locale.getDefault()),
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                    maxLines = 1,
                )
                Text(
                    clockTimeLabel(hit.timestamp, is24h),
                    style = NoopType.footnote,
                    color = Palette.textPrimary,
                    maxLines = 1,
                )
            }
        } else if (axisTicks.isNotEmpty()) {
            HypnogramTimeAxis(axisTicks, Modifier.padding(start = Metrics.sparkWidth))
        }
    }
}

/** A compact colour-coded key for the stepped hypnogram: one dot + label per stage in the chart's ramp, so
 *  the bands are decodable (esp. the Garmin ramp's two pinks, Awake vs REM). Twin of Swift SleepStageLegend.
 *
 *  NOTHING RENDERS THIS (#1536). Its only call sites put it above [StageBreakdownRows], whose rows carry
 *  their own text labels — so it decoded something already named, listed the stages in a different order
 *  than the rows, and drew RAMP colours while those rows use fixed [Palette] tokens, which made its dots
 *  disagree with the swatches beneath them on any non-NOOP ramp. Kept, not deleted: it is the only code
 *  that knows how to build this key, and a genuinely unlabelled hypnogram is exactly what it is for. Wire
 *  it to one of those, not to a labelled table. */
@Composable
internal fun SleepStageLegend(palette: SleepStagePalette) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        for (label in listOf("Awake", "REM", "Light", "Deep")) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(stageColorForRamp(label, palette)))
                Text(label, style = NoopType.caption, color = Palette.textSecondary, maxLines = 1)
            }
        }
    }
}

/**
 * Time-axis ticks for the stepped hypnogram: the EXACT onset (frac 0) and wake (frac 1) at the edges
 * (minute precision, [axisEdgeLabel]), plus round-hour marks between at a "nice" step chosen so the interior
 * count is ≤ [maxLabels]−2 — so a WIDER screen (larger [maxLabels]) shows MORE marks. Interior marks read as
 * the hour only ([axisHourLabel] — "06:00" / "6 AM"), which is shorter than an edge label, so more fit. Marks
 * within ~18% of either edge are dropped so a round-hour label can't collide with the onset/wake label.
 * [is24h] (from `DateFormat.is24HourFormat`) picks 12/24h formatting. Pure/unit-testable.
 */
internal fun hypnogramAxisTicks(
    onsetTs: Long,
    wakeTs: Long,
    maxLabels: Int,
    is24h: Boolean = true,
): List<Pair<Float, String>> {
    val span = (wakeTs - onsetTs).toDouble()
    if (span <= 0.0) return listOf(0f to axisEdgeLabel(onsetTs, is24h))
    val out = ArrayList<Pair<Float, String>>()
    out.add(0f to axisEdgeLabel(onsetTs, is24h))
    val spanHours = span / 3600.0
    val interiorTarget = (maxLabels - 2).coerceAtLeast(1)
    val stepH = intArrayOf(1, 2, 3, 4, 6, 8, 12).firstOrNull { spanHours / it <= interiorTarget + 0.5 } ?: 12
    val stepSec = stepH * 3600L
    // Align marks to LOCAL hour boundaries, not UNIX-epoch ones: on a half-hour-offset zone (e.g. UTC+5:30)
    // an epoch-aligned 3h step lands at local :30, and axisHourLabel's "HH:00" would then LIE. Local midnight
    // is a whole number of days (a multiple of stepSec for every stepH that divides 24), so shifting into
    // local-epoch space by the zone offset, aligning there, and shifting back puts every mark on a true :00.
    val offset = TimeZone.getDefault().getOffset(onsetTs * 1000L) / 1000L
    var t = (((onsetTs + offset) / stepSec) + 1L) * stepSec - offset // first LOCAL hour boundary after onset
    while (t < wakeTs) {
        val frac = ((t - onsetTs).toDouble() / span).toFloat()
        // Drop marks within ~18% of an edge so a round-hour label can't overlap the onset/wake label — sized
        // for the WIDER 12h edge ("10:25 AM"), plus half the mark's own width, on a phone.
        if (frac > 0.18f && frac < 0.82f) out.add(frac to axisHourLabel(t, is24h))
        t += stepSec
    }
    out.add(1f to axisEdgeLabel(wakeTs, is24h))
    return out
}

/**
 * Places [ticks] (fraction 0..1 → label) along a full-width row, each label CENTRED on its fraction and
 * clamped so the edge labels stay on-screen. A [Layout] (not a weighted Row) so round-hour marks sit at
 * their true time position rather than evenly spaced — the labels line up with the axis hairlines drawn at
 * the same fractions in the chart above.
 */
@Composable
private fun HypnogramTimeAxis(ticks: List<Pair<Float, String>>, modifier: Modifier = Modifier) {
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            ticks.forEach { (_, label) ->
                Text(label, style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val wpx = constraints.maxWidth
        val hpx = placeables.maxOfOrNull { it.height } ?: 0
        layout(wpx, hpx) {
            placeables.forEachIndexed { i, p ->
                val centerX = ticks[i].first * wpx
                val x = (centerX - p.width / 2f).roundToInt().coerceIn(0, (wpx - p.width).coerceAtLeast(0))
                p.place(x, 0)
            }
        }
    }
}

/**
 * The onset · midpoint · wake clock-label row under a night timeline. Extracted from
 * [HypnogramWithAxis] so the #988 stage-timeline rows share the exact same axis rendering.
 */
@Composable
internal fun ClockLabelRow(onsetTs: Long, wakeTs: Long) {
    val is24h = ClockPrefs.uses24Hour(LocalContext.current)   // #1821
    val onset = clockTimeLabel(onsetTs, is24h)
    val mid = clockTimeLabel((onsetTs + wakeTs) / 2L, is24h)
    val wake = clockTimeLabel(wakeTs, is24h)
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            onset,
            style = NoopType.footnote,
            color = Palette.textTertiary,
            textAlign = TextAlign.Start,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            mid,
            style = NoopType.footnote,
            color = Palette.textTertiary,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            wake,
            style = NoopType.footnote,
            color = Palette.textTertiary,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Map a stage name to its design-system sleep tone (case-insensitive). Keys off [canonicalStage]
 *  rather than repeating the trim/lowercase/"wake"-fold, so a new alias added there is picked up
 *  here automatically instead of needing a matching edit. Unknown stages fall back to the Light
 *  tone, as they did before. */
private fun stageColorFor(name: String): Color = when (canonicalStage(name)) {
    "deep" -> Palette.sleepDeep
    "rem" -> Palette.sleepREM
    "light" -> Palette.sleepLight
    "awake" -> Palette.sleepAwake
    else -> Palette.sleepLight
}

// Brand sleep ramps for the stepped [FilledHypnogram]: Garmin's for Filled (blue light/deep + magenta REM),
// Oura's for Ribbon (cream awake + blues, sampled from the ring's app), so the chart reads like the app it's
// modelled on. Byte-identical hexes to the Swift `StrandPalette.BrandSleepRamp`. The classic hero strip
// ([HypnogramWithAxis]) keeps the NOOP tokens via [stageColorFor]. (#sleep-chart-style)
//
// PER-SCHEME since the light-mode pass. The ramps shipped flat — one hex for both schemes — because both
// source apps are dark-tuned; measured on the light card (near-white) the Oura ramp collapses, with `awake`
// #EAE3D3 at 1.28:1, i.e. not drawn. The light values apply ONE uniform HLS-lightness scale per ramp
// (Oura x0.575, Garmin x0.912), pinned so each ramp's lightest band reaches 3:1 on white: hue and
// saturation untouched, ordering preserved, no stage pair collapsed. Clamping each band separately to 3:1
// is the trap — it drives Oura `rem` and `light` to 1.00:1 apart. Properties pinned in [BrandSleepRampTest],
// twin of the Swift `BrandSleepRampTests`.
/**
 * The eight brand hexes x two schemes, declared ONCE as ARGB longs — the [Color]s below and the ramp lists
 * [BrandSleepRampTest] asserts on are both built from these, so there is no second copy to drift. Stage
 * order is awake, rem, light, deep (which is NOT luminance order: Garmin's `light` is lighter than its
 * `rem`; what the light pass preserves is each ramp's own order, whatever it is).
 */
internal object BrandSleepRamp {
    const val OURA_AWAKE_DARK = 0xFFEAE3D3
    const val OURA_REM_DARK = 0xFF90D0F0
    const val OURA_LIGHT_DARK = 0xFF40B0E0
    const val OURA_DEEP_DARK = 0xFF206080
    const val GARMIN_AWAKE_DARK = 0xFFF26FE8
    const val GARMIN_REM_DARK = 0xFFE22DD0
    const val GARMIN_LIGHT_DARK = 0xFF4AA6F2
    const val GARMIN_DEEP_DARK = 0xFF2472D8
    const val OURA_AWAKE_LIGHT = 0xFFAD9153
    const val OURA_REM_LIGHT = 0xFF1A8AC2
    const val OURA_LIGHT_LIGHT = 0xFF176B8E
    const val OURA_DEEP_LIGHT = 0xFF12374A
    const val GARMIN_AWAKE_LIGHT = 0xFFEF52E3
    const val GARMIN_REM_LIGHT = 0xFFD91EC7
    const val GARMIN_LIGHT_LIGHT = 0xFF3099F0
    const val GARMIN_DEEP_LIGHT = 0xFF2168C5

    val ouraDark = listOf(OURA_AWAKE_DARK, OURA_REM_DARK, OURA_LIGHT_DARK, OURA_DEEP_DARK)
    val ouraLight = listOf(OURA_AWAKE_LIGHT, OURA_REM_LIGHT, OURA_LIGHT_LIGHT, OURA_DEEP_LIGHT)
    val garminDark = listOf(GARMIN_AWAKE_DARK, GARMIN_REM_DARK, GARMIN_LIGHT_DARK, GARMIN_DEEP_DARK)
    val garminLight = listOf(GARMIN_AWAKE_LIGHT, GARMIN_REM_LIGHT, GARMIN_LIGHT_LIGHT, GARMIN_DEEP_LIGHT)
}

// The scheme-resolved ramp. `Palette.isLight` is snapshot state, so a theme flip re-resolves these inside a
// Canvas DrawScope with no call-site change — the same per-scheme idiom the rest of the palette uses.
private fun brand(light: Long, dark: Long) = Color(if (Palette.isLight) light else dark)
private val ouraSleepAwake: Color get() = brand(BrandSleepRamp.OURA_AWAKE_LIGHT, BrandSleepRamp.OURA_AWAKE_DARK)
private val ouraSleepREM: Color get() = brand(BrandSleepRamp.OURA_REM_LIGHT, BrandSleepRamp.OURA_REM_DARK)
private val ouraSleepLight: Color get() = brand(BrandSleepRamp.OURA_LIGHT_LIGHT, BrandSleepRamp.OURA_LIGHT_DARK)
private val ouraSleepDeep: Color get() = brand(BrandSleepRamp.OURA_DEEP_LIGHT, BrandSleepRamp.OURA_DEEP_DARK)
private val garminSleepAwake: Color get() = brand(BrandSleepRamp.GARMIN_AWAKE_LIGHT, BrandSleepRamp.GARMIN_AWAKE_DARK)
private val garminSleepREM: Color get() = brand(BrandSleepRamp.GARMIN_REM_LIGHT, BrandSleepRamp.GARMIN_REM_DARK)
private val garminSleepLight: Color get() = brand(BrandSleepRamp.GARMIN_LIGHT_LIGHT, BrandSleepRamp.GARMIN_LIGHT_DARK)
private val garminSleepDeep: Color get() = brand(BrandSleepRamp.GARMIN_DEEP_LIGHT, BrandSleepRamp.GARMIN_DEEP_DARK)

private fun stageColorForRamp(name: String, palette: SleepStagePalette): Color = when (palette) {
    SleepStagePalette.NOOP -> stageColorFor(name)
    SleepStagePalette.OURA -> when (canonicalStage(name)) {
        "deep" -> ouraSleepDeep; "rem" -> ouraSleepREM; "light" -> ouraSleepLight
        "awake" -> ouraSleepAwake; else -> ouraSleepLight
    }
    SleepStagePalette.GARMIN -> when (canonicalStage(name)) {
        "deep" -> garminSleepDeep; "rem" -> garminSleepREM; "light" -> garminSleepLight
        "awake" -> garminSleepAwake; else -> garminSleepLight
    }
}
