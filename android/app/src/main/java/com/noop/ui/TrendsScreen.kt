package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.ExpandMore
import com.noop.analytics.RangeReportEngine
import com.noop.analytics.ReportDisplayUnits
import com.noop.analytics.ReportMetric
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.WeeklyDigestEngine
import com.noop.data.DailyMetric
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - Trends

@Composable
fun TrendsScreen(vm: AppViewModel) {
    // Reactive cache (oldest → newest) as the immediate backing.
    val reactiveDays by vm.recentDays.collectAsStateWithLifecycle()
    val registryActiveId by vm.activeStrapIdFlow.collectAsStateWithLifecycle()
    val activeStrapId = registryActiveId ?: vm.activeStrapId

    // Full history backs calendar navigation; recent data populates the first frame.
    var fullHistory by remember { mutableStateOf<List<DailyMetric>?>(null) }
    LaunchedEffect(reactiveDays, activeStrapId) {
        // Merged: imported WHOOP days win; on-device computed days gap-fill the trends. Reads the registry's
        // ACTIVE strap id so daysMerged resolves the active-id ∪ canonical "my-whoop" union (SPINE / #814) ,
        // a re-added strap's data and the canonical import both surface; a single-WHOOP install is unchanged.
        fullHistory = vm.repo.daysMerged(activeStrapId)
    }
    val days = fullHistory ?: reactiveDays

    // Effort display scale (#268) , routes the Effort small-multiple's numbers + unit. Display-only.
    val effortScale = UnitPrefs.effortScale(LocalContext.current)

    // Day-cycle sky backdrop (#698). Default ON. When off, Trends drops the liquid sky and the scaffold
    // paints the plain dark surface canvas instead. SharedPreferences isn't reactive, so this is read once
    // into local state (mirrors Today's showDayCycleBackground gate).
    val trendsCtx = LocalContext.current
    val showDayCycleBackground = remember { NoopPrefs.showDayCycleBackground(trendsCtx) }
    // Sky-behind-cards (#434 family): when on, the sky fills the whole viewport so the transparent
    // cards reveal it the whole way down, exactly like Today and the metric-detail screens.
    val skyBehindCards = remember { NoopPrefs.skyBehindCards(trendsCtx) }

    var range by remember { mutableStateOf(TrendsRange.Week) }
    var rangeOffset by remember { mutableStateOf(0) }
    var monthOffset by remember { mutableStateOf(-1) }
    var selectedMetric by remember { mutableStateOf(TrendsCoreMetric.Recovery) }
    var metricMenuOpen by remember { mutableStateOf(false) }
    val today = LocalDate.now().toString()
    val minimumRangeOffset = remember(days, range, today) {
        TrendsWindow.minimumOffset(range.days ?: 30, days.firstOrNull()?.day, today)
    }
    val minimumMonthOffset = remember(days, today) {
        TrendsWindow.minimumOffset(30, days.firstOrNull()?.day, today)
    }
    LaunchedEffect(minimumRangeOffset, minimumMonthOffset) {
        rangeOffset = rangeOffset.coerceIn(minimumRangeOffset, 0)
        monthOffset = monthOffset.coerceIn(minimumMonthOffset, 0)
    }
    val window = remember(range, rangeOffset, today) {
        TrendsWindow.period(range.days ?: 30, rangeOffset, today)!!
    }

    // #710 , browse previous weeks in the Week-in-review digest. 0 = the week containing today; each step
    // back is one Mon–Sun week earlier, clamped so it never runs past the earliest day we hold. The Trends
    // RANGE control above scopes the long charts; this only moves the weekly digest at the top.
    var weekOffset by remember { mutableStateOf(0) }
    // Re-clamp the offset whenever the loaded history changes (e.g. an import lands more weeks), so a
    // stored offset can never point past the new earliest week. Mirrors the iOS minWeekOffset clamp.
    val minWeekOffset = remember(days) { minWeekOffset(days) }
    LaunchedEffect(minWeekOffset) { weekOffset = weekOffset.coerceIn(minWeekOffset, 0) }

    // Resolve each metric's window ONCE per composition and reuse below , mirrors the macOS resolve(_:)
    // so caption / widened / points aren't recomputed per use. HOISTED above the lazy scaffold: these
    // are @Composable `remember` hooks, which can't run inside the LazyListScope content lambda. They're
    // cheap memoized resolves (no-ops over an empty `days`), so the empty branch below simply ignores
    // them , same as Intelligence's hoisted range/filter. Mirrors the eager body's per-composition resolve.
    val recovery = remember(days, range, window) { resolveSelectedMetric(days, window) { it.recovery } }
    val hrv = remember(days, range, window) { resolveSelectedMetric(days, window) { it.avgHrv } }
    val rhr = remember(days, range, window) { resolveSelectedMetric(days, window) { it.restingHr?.toDouble() } }
    val strain = remember(days, range, window) { resolveSelectedMetric(days, window) { it.strain } }
    // Rest = the sleep_performance COMPOSITE (0–100) , the SAME metric the Today Rest score/tile and the
    // Sleep Rest-detail plot (#614 follow-up), NOT raw efficiency, which is a different number under the
    // same "Rest" label and made the Trends Rest graph disagree with the Today Rest score (#732).
    // sleep_performance is a metricSeries (imported-wins resolved), not a DailyMetric column, so fetch the
    // resolved series and key it by day for the selected calendar window. Mirrors the source
    // TodayScreen's restScore reads, so the two screens now plot the same number.
    var sleepPerfByDay by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    LaunchedEffect(days) {
        sleepPerfByDay = runCatching {
            vm.repo.resolvedSeries("sleep_performance", "my-whoop", "0000-00-00", "9999-99-99",
                strapDeviceId = activeStrapId)
                .values.associate { it.first to it.second }
        }.getOrDefault(emptyMap())
    }
    val rest = remember(days, range, window, sleepPerfByDay) {
        resolveSelectedMetric(days, window) { d -> sleepPerfByDay[d.day] }
    }
    val selected = when (selectedMetric) {
        TrendsCoreMetric.Recovery -> recovery
        TrendsCoreMetric.Strain -> strain
        TrendsCoreMetric.SleepPerformance -> rest
        TrendsCoreMetric.Hrv -> hrv
        TrendsCoreMetric.RestingHr -> rhr
    }
    val selectedColor = when (selectedMetric) {
        TrendsCoreMetric.Recovery -> Palette.chargeColor
        TrendsCoreMetric.Strain -> Palette.effortColor
        TrendsCoreMetric.SleepPerformance -> Palette.restColor
        TrendsCoreMetric.Hrv -> Palette.metricPurple
        TrendsCoreMetric.RestingHr -> Palette.metricRose
    }

    LazyScreenScaffold(
        title = stringResource(R.string.nav_trends),
        subtitle = stringResource(R.string.trends_subtitle),
        // LIQUID SKY BACKDROP (the pilot pattern — LiquidScreenSky.kt): the time-of-day liquid sky settles
        // into the theme canvas behind the header + top rows, full-bleed via the scaffold's topBackground
        // plumbing. Static (LiquidSkyStatic, inside the helper) — never an animated sky behind a scrolling
        // list. Gated on the same day-cycle pref as Today; when off, the scaffold paints the flat canvas.
        topBackground = screenBackdropSlot(showDayCycleBackground, skyBehindCards),
        // Sky-behind-cards fills the viewport so the transparent cards reveal the sky the whole way down
        // (Today / metric-detail parity — the same two prefs drive the same two behaviours everywhere).
        fullBleedBackground = screenBackdropFullBleed(showDayCycleBackground, skyBehindCards),
    ) {
        if (days.isEmpty()) {
            item { EmptyTrends() }
            return@LazyScreenScaffold
        }

        item {
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    Box {
                        TextButton(onClick = { metricMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(selectedMetric.label), style = NoopType.headline, color = Palette.textPrimary,
                                modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                            Icon(Icons.Filled.ExpandMore, contentDescription = stringResource(R.string.plan_trends_metric),
                                tint = Palette.textPrimary)
                        }
                        DropdownMenu(expanded = metricMenuOpen, onDismissRequest = { metricMenuOpen = false }) {
                            TrendsCoreMetric.entries.forEach { metric ->
                                DropdownMenuItem(text = { Text(stringResource(metric.label)) }, onClick = {
                                    selectedMetric = metric
                                    metricMenuOpen = false
                                })
                            }
                        }
                    }
                    SegmentedPillControl(
                        items = listOf(TrendsRange.Week, TrendsRange.Month, TrendsRange.Half),
                        selection = range, label = { it.label }, onSelect = { range = it; rangeOffset = 0 },
                    )
                    TrendsPeriodNavigation(window, rangeOffset, minimumRangeOffset) { delta ->
                        rangeOffset = (rangeOffset + delta).coerceIn(minimumRangeOffset, 0)
                    }
                }
            }
        }
        item {
            MetricTrendCard(
                title = stringResource(selectedMetric.label),
                unit = when (selectedMetric) {
                    TrendsCoreMetric.Recovery, TrendsCoreMetric.SleepPerformance -> "%"
                    TrendsCoreMetric.Strain -> "/ ${UnitFormatter.effortScaleMax(effortScale)}"
                    TrendsCoreMetric.Hrv -> "ms"
                    TrendsCoreMetric.RestingHr -> "bpm"
                },
                color = selectedColor,
                resolved = selected,
                higherIsBetter = when (selectedMetric) {
                    TrendsCoreMetric.RestingHr -> false
                    TrendsCoreMetric.Strain -> null
                    else -> true
                },
                fmt = { if (selectedMetric == TrendsCoreMetric.Strain) UnitFormatter.effortDisplay(it, effortScale)
                    else "${it.roundToInt()}" },
            )
        }
        item {
            MonthlyPerformanceCard(days, today, monthOffset.coerceIn(minimumMonthOffset, 0), minimumMonthOffset, effortScale) {
                monthOffset = (monthOffset.coerceIn(minimumMonthOffset, 0) + it).coerceIn(minimumMonthOffset, 0)
            }
        }
        item {
            WeeklyDigestNav(days, weekOffset, minWeekOffset) { delta ->
                weekOffset = (weekOffset + delta).coerceIn(minWeekOffset, 0)
            }
        }

        // --- Long-horizon training load (CTL/ATL/TSB). Full history, not the range window — chronic
        // load is inherently a 42-day horizon. Shows an honest "needs N more days" state until enough
        // contiguous Effort history exists. Twin of the Apple TrainingLoadCard. ---
        item {
            TrainingLoadCard(days = days, modifier = Modifier.staggeredAppear(index = 5))
        }

        // --- Recovery history strip (stands in for the macOS YearHeatStrip) ---
        item {
            Column(modifier = Modifier.staggeredAppear(index = 6)) {
                RecoveryHistoryCard(days = days, range = range)
            }
        }

        // --- Export trends report (#436) , the shareable offline PDF exporter. Mirrors the iOS
        // TrendsView.exportReportRow footer; the same composable Settings hosts, so both surfaces
        // offer it. Routed through NoopButton like every other CTA (no gold). ---
        item {
            Column(modifier = Modifier.staggeredAppear(index = 7)) {
                TrendsReportExportSection(vm)
            }
        }
    }
}

private enum class TrendsCoreMetric(val label: Int) {
    Recovery(R.string.plan_trends_recovery),
    Strain(R.string.plan_trends_strain),
    SleepPerformance(R.string.plan_trends_sleep_performance),
    Hrv(R.string.trends_hrv_full),
    RestingHr(R.string.trends_resting_hr_full),
}

private fun resolveSelectedMetric(
    days: List<DailyMetric>, window: TrendsWindow,
    value: (DailyMetric) -> Double?,
): ResolvedMetric {
    val points = days.filter { window.contains(it.day) }.mapNotNull { day ->
        value(day)?.takeIf { it.isFinite() }?.let { day.day to it }
    }
    return ResolvedMetric(points.map { it.second }, points.map { it.first })
}

private fun trendsWindowLabel(window: TrendsWindow): String {
    val formatter = DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
    return "${LocalDate.parse(window.start).format(formatter)} – ${LocalDate.parse(window.end).format(formatter)}"
}

@Composable
private fun TrendsPeriodNavigation(window: TrendsWindow, offset: Int, minimum: Int, onStep: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onStep(-1) }, enabled = offset > minimum) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = stringResource(R.string.plan_trends_previous_period),
                tint = if (offset > minimum) Palette.textPrimary else Palette.textTertiary)
        }
        Text(trendsWindowLabel(window), modifier = Modifier.weight(1f), style = NoopType.subhead,
            color = Palette.textSecondary, textAlign = TextAlign.Center)
        IconButton(onClick = { onStep(1) }, enabled = offset < 0) {
            Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(R.string.plan_trends_next_period),
                tint = if (offset < 0) Palette.textPrimary else Palette.textTertiary)
        }
    }
}

@Composable
private fun MonthlyPerformanceCard(
    days: List<DailyMetric>, today: String, offset: Int, minimum: Int,
    effortScale: EffortScale, onStep: (Int) -> Unit,
) {
    val window = remember(today, offset) { TrendsWindow.period(30, offset, today)!! }
    val report = remember(days, window) {
        RangeReportEngine.build(TrendsReportData.metricMaps(days), window.start, window.end)
    }
    val units = ReportDisplayUnits(false, effortDisplayFactor(effortScale))
    val metrics = listOf(ReportMetric.RECOVERY, ReportMetric.STRAIN, ReportMetric.SLEEP_HOURS, ReportMetric.HRV, ReportMetric.RESTING_HR)
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            SectionHeader(stringResource(R.string.plan_trends_monthly_performance), overline = stringResource(R.string.plan_trends_local_report))
            TrendsPeriodNavigation(window, offset, minimum, onStep)
            metrics.forEach { metric ->
                val stat = report.stat(metric)
                HorizontalDivider(color = Palette.hairline)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                        val label = when (metric) {
                            ReportMetric.RECOVERY -> R.string.plan_trends_recovery
                            ReportMetric.STRAIN -> R.string.plan_trends_strain
                            ReportMetric.SLEEP_HOURS -> R.string.l10n_trends_explore_screen_sleep_3cac34e6
                            ReportMetric.HRV -> R.string.trends_hrv_full
                            else -> R.string.trends_resting_hr_full
                        }
                        Text(stringResource(label), style = NoopType.subhead, color = Palette.textPrimary)
                        Text(if (stat != null) stringResource(R.string.plan_trends_recorded_days, stat.n, report.totalDays)
                            else stringResource(R.string.plan_trends_no_readings_month),
                            style = NoopType.footnote, color = Palette.textTertiary)
                    }
                    Text(stat?.let { TrendsReportFormat.meanText(it, units) } ?: "—",
                        style = NoopType.bodyNumber, color = Palette.textPrimary)
                }
            }
            Text(stringResource(R.string.plan_trends_report_note), style = NoopType.footnote, color = Palette.textTertiary)
        }
    }
}

// MARK: - Week-in-review digest with prev/next week browsing (#710)

/**
 * The most-negative weekOffset allowed: the number of whole Mon–Sun weeks between the earliest day we
 * hold and this week. Beyond it there's no data to digest, so the back chevron disables. 0 when history
 * is empty or unparseable (so we stay on this week). `days` is oldest → newest. Mirrors iOS minWeekOffset.
 */
private fun minWeekOffset(days: List<DailyMetric>): Int {
    val earliest = days.firstOrNull()?.day ?: return 0
    val earliestMon = WeeklyDigestEngine.mondayOfWeek(earliest) ?: return 0
    val thisMon = WeeklyDigestEngine.mondayOfWeek(logicalDayKeyNow()) ?: return 0
    var off = 0
    var mon = thisMon
    // Walk weeks back until we pass the earliest week. Hard cap ~10 years so a bad date can't spin.
    while (mon > earliestMon && off > -520) {
        mon = WeeklyDigestEngine.addDays(mon, -7)
        off -= 1
    }
    return off
}

/**
 * The Week-in-review digest for the selected week, with prev/next chevrons in its header. The digest for
 * the offset week is built straight from the shared [buildWeeklyDigest] (the same builder
 * WeeklyDigestCard uses) so past weeks render in the identical format. The whole block self-hides only
 * when the WHOLE history is empty; an empty PAST week still shows the chevrons so the user can step on.
 * Mirrors iOS TrendsView.weeklyDigestNav.
 */
@Composable
private fun WeeklyDigestNav(
    days: List<DailyMetric>,
    weekOffset: Int,
    minWeekOffset: Int,
    onStep: (Int) -> Unit,
) {
    if (days.isEmpty()) return
    // Anchor day for this offset = today shifted back by weekOffset whole weeks; the engine snaps it to
    // that week's Monday. Memoised so the (cheap but non-trivial) digest rebuild only runs on a real change.
    val anchorDay = remember(weekOffset) {
        WeeklyDigestEngine.addDays(logicalDayKeyNow(), weekOffset * 7)
    }
    // #268/#463: past weeks quote Effort on the user's display scale too, same as the live card.
    val context = LocalContext.current
    val factor = effortDisplayFactor(UnitPrefs.effortScale(context))
    val digest = remember(days, anchorDay, factor) {
        buildWeeklyDigest(days, anchorDay, effortDisplayFactor = factor)
    }
    // "Share recap" capture: track the card's on-screen bounds, then draw the Compose host view + crop
    // (RecapShare.captureCropped) — the Compose-1.7 GraphicsLayer capture API isn't in this 1.6.8 build.
    val scope = rememberCoroutineScope()
    val hostView = LocalView.current
    var cardBounds by remember { mutableStateOf<Rect?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        WeekNavBar(weekOffset = weekOffset, minWeekOffset = minWeekOffset, onStep = onStep)
        if (digest.isEmpty) {
            DataPendingNote(
                title = stringResource(R.string.trends_no_readings_this_week),
                body = stringResource(R.string.trends_no_readings_body),
            )
        } else {
            Box(modifier = Modifier.onGloballyPositioned { cardBounds = it.boundsInRoot() }) {
                NoopCard { WeeklyDigestContent(digest = digest, compact = true) }
            }
            NoopButton(
                text = "Share recap",
                leadingIcon = Icons.Filled.IosShare,
                kind = NoopButtonKind.Secondary,
                onClick = {
                    val bounds = cardBounds
                    val bmp = bounds?.let { RecapShare.captureCropped(hostView, it) }
                    if (bmp != null) scope.launch { RecapShare.share(context, bmp, anchorDay) }
                },
            )
        }
    }
}

/**
 * Prev/next week stepper. Back is clamped at the earliest week we hold; forward at this week (no future
 * weeks). Flat accent chevrons, mirroring the iOS FullDayChart day stepper (#597).
 */
@Composable
private fun WeekNavBar(weekOffset: Int, minWeekOffset: Int, onStep: (Int) -> Unit) {
    val atOldest = weekOffset <= minWeekOffset
    val atNewest = weekOffset >= 0
    val label = when {
        weekOffset == 0 -> stringResource(R.string.trends_this_week)
        weekOffset == -1 -> stringResource(R.string.trends_last_week)
        else -> pluralStringResource(R.plurals.trends_weeks_ago, -weekOffset, -weekOffset)
    }
    // liquidPress on the two week-step chevrons (the screen's tappable controls): each settles inward on
    // press, wired to the SAME interactionSource the IconButton uses for its own ripple, matching the pilot.
    val prevInteraction = remember { MutableInteractionSource() }
    val nextInteraction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Metrics.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { onStep(-1) },
            enabled = !atOldest,
            interactionSource = prevInteraction,
            modifier = Modifier.liquidPress(prevInteraction),
        ) {
            Icon(
                Icons.Filled.ChevronLeft,
                contentDescription = stringResource(R.string.trends_previous_week),
                tint = if (atOldest) Palette.textTertiary else Palette.accent,
            )
        }
        Spacer(Modifier.weight(1f))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = NoopType.headline, color = Palette.textPrimary)
            Overline(stringResource(R.string.trends_week_in_review), color = Palette.textSecondary)
        }
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = { onStep(1) },
            enabled = !atNewest,
            interactionSource = nextInteraction,
            modifier = Modifier.liquidPress(nextInteraction),
        ) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = stringResource(R.string.trends_next_week),
                tint = if (atNewest) Palette.textTertiary else Palette.accent,
            )
        }
    }
}

// MARK: - Range control model (ported from TrendsView.Range)

/** W(7) / M(30) / 3M(90) / 6M(180) / 1Y(365) / ALL. */
private enum class TrendsRange(val days: Int?, val label: String, val longName: String) {
    Week(7, "W", "week"),
    Month(30, "M", "month"),
    Quarter(90, "3M", "3 months"),
    Half(180, "6M", "6 months"),
    Year(365, "1Y", "year"),
    All(null, "ALL", "all history");

    /** This range plus every LARGER range, ascending , the auto-expand search order. */
    val widening: List<TrendsRange>
        get() = entries.dropWhile { it != this }
}

// MARK: - Resolved metric (mirrors TrendsView.ResolvedMetric / resolve)

/** The plotted values and calendar day of each reading. */
private data class ResolvedMetric(
    val values: List<Double>,
    val dates: List<String>,
)

/**
 * Walk the widening order once: take the smallest range ≥ selected whose window holds
 * ≥1 non-null point for [value]; if none do, fall back to ALL. Windows are taken
 * relative to the phone's local day for the hosted Home cards.
 */
private fun resolveMetric(
    days: List<DailyMetric>,
    selected: TrendsRange,
    value: (DailyMetric) -> Double?,
): ResolvedMetric {
    for (r in selected.widening) {
        val pts = windowPoints(days, r, value)
        if (pts.isNotEmpty()) {
            return ResolvedMetric(
                values = pts.map { it.second },
                dates = pts.map { it.first },
            )
        }
    }
    val pts = windowPoints(days, TrendsRange.All, value)
    return ResolvedMetric(
        values = pts.map { it.second },
        dates = pts.map { it.first },
    )
}

/**
 * Non-null metric points (day, value) within [range]'s trailing window, ending today. `days` is the full oldest-first history. A null
 * `range.days` (ALL) returns every non-null point. The day string is carried alongside each
 * value so the chart can draw a real date X-axis.
 */
private fun windowPoints(
    days: List<DailyMetric>,
    range: TrendsRange,
    value: (DailyMetric) -> Double?,
): List<Pair<String, Double>> {
    if (days.isEmpty()) return emptyList()
    val sliced = when (val n = range.days) {
        null -> days
        // Trailing N CALENDAR days ending today , anchored to the phone's date, NOT the last N rows
        // (which on a stale import made months-old data fill the W/M/3M windows, looking current , #23).
        // ISO yyyy-MM-dd sorts chronologically. Empty short windows auto-widen via resolveMetric, so old
        // imports surface under a wider range / All history rather than masquerading as recent.
        else -> {
            val cutoff = LocalDate.now().minusDays((n - 1).toLong()).toString()
            days.filter { it.day >= cutoff }
        }
    }
    return sliced.mapNotNull { d -> value(d)?.let { d.day to it } }
}

// MARK: - ChartCard , the uniform fixed-height trend card
//
// A NoopCard holding a header (overline-styled title + caption + trailing read-out), a
// fixed-height LineChart, and a divided footer of labelled stats. Mirrors the macOS
// ChartCard used across Trends so every card is Metrics.chartHeight-class and identical.

@Composable
private fun ChartCard(
    title: String,
    subtitle: String?,
    trailing: String?,
    color: Color,
    values: List<Double>,
    footer: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    dates: List<String> = emptyList(),
    formatY: (Double) -> String = { "${it.roundToInt()}" },
    // Bevel: a domain card wash, a bright end-cap "now" colour, and an optional window-change TrendChip.
    tint: Color? = null,
    tipColor: Color = color,
    change: Double? = null,
    higherIsBetter: Boolean? = null,
    changeFmt: (Double) -> String = { "${it.roundToInt()}" },
    // Fraction of the plot height left empty above the peak , the Android stand-in for the iOS
    // hero's `valueRange: 0...106` padded ceiling, so the peak + now-cap halo clear the top
    // gridline. 0 keeps the curve filling the full height (the small multiples). (#458/parity)
    chartHeadroom: Float = 0f,
) {
    val body: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            // Header.
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Overline(title)
                    if (subtitle != null) {
                        Text(subtitle, style = NoopType.footnote, color = Palette.textTertiary)
                    }
                }
                if (trailing != null) {
                    // Neutral 15pt readout (matches iOS TrendsView) , not the 22sp tinted figure.
                    Text(trailing, style = NoopType.bodyNumber, color = Palette.textPrimary)
                }
            }

            // Chart (fixed height) or sparse placeholder. The chart is flanked by a max/avg/min
            // Y-axis column on the left and a first/mid/last date X-axis row underneath, so the
            // line reads against real numbers and dates instead of a bare unlabelled curve.
            if (values.size >= 2) {
                ChartWithAxes(
                    values = values,
                    dates = dates,
                    color = color,
                    tipColor = tipColor,
                    formatY = formatY,
                    headroom = chartHeadroom,
                )
            } else {
                SparsePlaceholder()
            }

            // Footer stats + a window-change chip aligned to the trailing edge.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) { ChartFooter(footer) }
                ChangeChip(change, higherIsBetter, changeFmt)
            }
        }
    }

    NoopCard(modifier = modifier, padding = Metrics.cardPadding, tint = tint) { body() }
}

/** A TrendChip for a window's period change , green/rose by whether the move is good for THIS metric. */
@Composable
private fun ChangeChip(change: Double?, higherIsBetter: Boolean?, fmt: (Double) -> String) {
    if (change == null || kotlin.math.abs(change) <= 0.0001) return
    val sign = if (change >= 0) "+" else "−"
    val deltaText = uiString(R.string.l10n_trends_screen_sign_fmt_kotlin_math_abs_change_9ad2f71e, sign, fmt(kotlin.math.abs(change)))
    val color = when (higherIsBetter) {
        null -> Palette.textTertiary
        else -> if ((change > 0) == higherIsBetter) Palette.statusPositive else Palette.metricRose
    }
    // Parity with iOS #967 (fix(trends): label change indicators): a "Trend" overline above the delta chip
    // so it reads as a labeled statistic beside the ChartFooter columns instead of an unlabeled pill at the
    // card edge. Uses a leading-aligned Overline stack + merged a11y
    // announcement ("Trend: +5") so TalkBack reads it as one statistic, not two.
    val trendLabel = stringResource(R.string.trends_trend)
    // A11y announces the label + delta as ONE statistic ("Trend: +5"), in natural case (not the visible
    // all-caps, which readers may spell out). Routed through a format resource so the label + locale-correct
    // separator (e.g. French thin space, Chinese full-width colon) are localized — a bare "$label: $delta"
    // template is both un-localizable and flagged by the i18n regression gate.
    val trendA11y = stringResource(R.string.trends_trend_a11y, deltaText)
    Column(
        modifier = Modifier.clearAndSetSemantics { contentDescription = trendA11y },
        horizontalAlignment = Alignment.Start,
    ) {
        Overline(trendLabel, color = Palette.textTertiary)
        TrendChip(text = deltaText, color = color)
    }
}

/**
 * A [LineChart] with a max/avg/min Y-axis label column and a first/mid/last date X-axis row.
 * Shared by the hero + small-multiple trend cards so every chart gets the same axis treatment.
 * Date strings (ISO yyyy-MM-dd) are reformatted to "d MMM"; an unparseable string falls back to
 * its raw value so a non-ISO key never blanks a label.
 */
@Composable
private fun ChartWithAxes(
    values: List<Double>,
    dates: List<String>,
    color: Color,
    formatY: (Double) -> String,
    tipColor: Color = color,
    // See ChartCard.chartHeadroom , fraction of the plot left empty above the peak.
    headroom: Float = 0f,
) {
    val maxV = values.max()
    val avgV = values.average()
    val minV = values.min()
    // Trend chart style (line vs bar). Read here at the single chart choke point (every trend card routes
    // through ChartWithAxes); SharedPreferences isn't reactive, but returning from Settings recomposes the
    // Trends screen, which re-reads it — the same read-on-recompose the Effort scale toggle relies on.
    val chartStyle = UnitPrefs.trendChartStyle(LocalContext.current)
    Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space4)) {
        Column(
            modifier = Modifier.height(Metrics.chartHeight),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatY(maxV), style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
            Text(formatY(avgV), style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
            Text(formatY(minV), style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Metrics.space4),
        ) {
            // The shared LineChart with a glowing "now" end-cap drawn on top , the Bevel idiom from
            // Today's OverviewHRChart. The cap reproduces LineChart's own point geometry (same
            // strokePx/topPad/bottomPad) so the dot lands exactly on the line's final sample.
            //
            // headroom leaves the top fraction of the card empty and pins the plotting Box to the
            // bottom , the Android stand-in for the iOS hero's `valueRange: 0...106` (LineChart has
            // no value-domain hook, so we shrink its drawing box instead). Both LineChart and the
            // GlowEndCap fill this same Box, so the cap stays on the line.
            val plotHeight = Metrics.chartHeight * (1f - headroom.coerceIn(0f, 0.5f))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Metrics.chartHeight),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(modifier = Modifier.fillMaxWidth().height(plotHeight)) {
                    if (chartStyle == TrendChartStyle.BAR) {
                        // Bar mode: value-ramp bars from the baseline. No GlowEndCap (the "now" halo is a
                        // line idiom). selectionEnabled is OFF so BarChart mean-bins a dense window (the
                        // multi-year "ALL" span) down to the pixel width — a clean silhouette instead of a
                        // 1000-bar sub-pixel smear. The max/avg/min axis column + footer carry the numbers.
                        BarChart(
                            values = values,
                            modifier = Modifier.fillMaxSize(),
                            color = color,
                            selectionEnabled = false,
                        )
                    } else {
                        LineChart(
                            values = values,
                            modifier = Modifier.fillMaxSize(),
                            color = color,
                            fill = true,
                            selectionEnabled = true,
                            // #463: the pinpoint label goes through the SAME formatter as the axis column,
                            // so a tapped Effort day can't print the stored 0-100 value beside a 0-21 axis.
                            formatValue = formatY,
                            selectionLabels = dates.map(::prettyAxisDate),
                        )
                        GlowEndCap(values = values, tipColor = tipColor)
                    }
                }
            }
            val axisLabels = trendAxisLabels(dates)
            if (axisLabels.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    axisLabels.forEach { label ->
                        Text(
                            prettyAxisDate(label.day),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                            modifier = Modifier.weight(1f),
                            textAlign = when (label.anchor) {
                                TrendAxisAnchor.START -> TextAlign.Start
                                TrendAxisAnchor.CENTER -> TextAlign.Center
                                TrendAxisAnchor.END -> TextAlign.End
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

internal enum class TrendAxisAnchor { START, CENTER, END }

internal data class TrendAxisLabel(val day: String, val anchor: TrendAxisAnchor)

/** Selects date labels and pins them to the corresponding start, middle, and end of the plot. */
internal fun trendAxisLabels(dates: List<String>): List<TrendAxisLabel> = when {
    dates.size < 2 -> emptyList()
    dates.size == 2 -> listOf(
        TrendAxisLabel(dates.first(), TrendAxisAnchor.START),
        TrendAxisLabel(dates.last(), TrendAxisAnchor.END),
    )
    else -> listOf(
        TrendAxisLabel(dates.first(), TrendAxisAnchor.START),
        TrendAxisLabel(dates[dates.lastIndex / 2], TrendAxisAnchor.CENTER),
        TrendAxisLabel(dates.last(), TrendAxisAnchor.END),
    )
}

/** ISO "yyyy-MM-dd" → "d MMM"; falls back to the raw string (or "" when null) if it doesn't parse. */
private fun prettyAxisDate(day: String?): String =
    day?.let {
        runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("d MMM", Locale.US)) }
            .getOrDefault(it)
    }.orEmpty()

/**
 * One Trends metric trend, rendered as a Today host card (#today-hosted-cards).
 *
 * Renders the SAME [MetricTrendCard] the Trends tab draws, so the hosted copy cannot drift into a
 * second chart of the same numbers. What it does NOT carry is the range selector: a home-screen card
 * has nowhere to put one and no obvious place to persist a per-card choice, so it takes a fixed
 * trailing month and keeps the tab's widening fallback for a wearer whose history is shorter than
 * that. The Trends tab remains where you change the window.
 *
 * Costs nothing to host. [resolveMetric] is a pure walk over the `days` list Today already holds, so
 * unlike the sleep model or the stress curve there is no read behind this and nothing to gate.
 */
@Composable
internal fun TrendHostCard(card: HostedCard, days: List<DailyMetric>, effortScale: EffortScale) {
    val range = TrendsRange.Month
    when (card) {
        HostedCard.TREND_HRV -> MetricTrendCard(
            title = stringResource(R.string.trends_hrv_full), unit = "ms",
            color = Palette.metricPurple,
            higherIsBetter = true,
            resolved = remember(days) { resolveMetric(days, range) { it.avgHrv } },
            fmt = { "${it.roundToInt()}" },
        )
        HostedCard.TREND_RESTING_HR -> MetricTrendCard(
            title = stringResource(R.string.trends_resting_hr_full), unit = "bpm",
            color = Palette.metricRose,
            higherIsBetter = false,
            resolved = remember(days) { resolveMetric(days, range) { it.restingHr?.toDouble() } },
            fmt = { "${it.roundToInt()}" },
        )
        HostedCard.TREND_EFFORT -> MetricTrendCard(
            // Plotted values stay on the stored 0-100 scale (line shape unchanged); only the displayed
            // numbers and unit follow the Effort-scale toggle, converted inside `fmt`, exactly as the
            // Trends tab does it (#268).
            title = stringResource(R.string.trends_effort),
            unit = "/ ${UnitFormatter.effortScaleMax(effortScale)}",
            color = Palette.effortColor,
            tint = Palette.effortColor,
            tipColor = Palette.effortBright,
            higherIsBetter = null,
            resolved = remember(days) { resolveMetric(days, range) { it.strain } },
            fmt = { UnitFormatter.effortDisplay(it, effortScale) },
        )
        else -> Unit
    }
}

/** A labelled metric-trend card built from a [ResolvedMetric] with mean / min / max. */
@Composable
private fun MetricTrendCard(
    title: String,
    unit: String,
    color: Color,
    resolved: ResolvedMetric,
    fmt: (Double) -> String,
    tint: Color? = null,
    tipColor: Color = color,
    higherIsBetter: Boolean? = null,
) {
    val avg = resolved.values.averageOrNull()
    ChartCard(
        title = title,
        subtitle = null,
        trailing = avg?.let { fmt(it) },
        color = color,
        tint = tint,
        tipColor = tipColor,
        values = resolved.values,
        dates = resolved.dates,
        formatY = fmt,
        change = periodChange(resolved.values),
        higherIsBetter = higherIsBetter,
        changeFmt = fmt,
        footer = listOf(
            // Plain "Mean" to match the bare Min/Max columns; the unit moves into the value
            // (e.g. "58 ms") so uppercasing can't render a shouty "MEAN MS".
            stringResource(R.string.trends_mean) to (avg?.let { "${fmt(it)} $unit" } ?: EM_DASH),
            stringResource(R.string.trends_min) to (resolved.values.minOrNull()?.let { fmt(it) } ?: EM_DASH),
            stringResource(R.string.trends_max) to (resolved.values.maxOrNull()?.let { fmt(it) } ?: EM_DASH),
        ),
    )
}

/**
 * The window's trend as a signed mean-of-recent-half minus mean-of-earlier-half , drives the card's
 * TrendChip so a glance reads the direction, like Today's deltas. null for a window too short to split.
 */
private fun periodChange(values: List<Double>): Double? {
    if (values.size < 4) return null
    val mid = values.size / 2
    val earlier = values.take(mid)
    val recent = values.drop(mid)
    if (earlier.isEmpty() || recent.isEmpty()) return null
    return recent.average() - earlier.average()
}

/** Evenly-spaced labelled stats under a chart, separated by a hairline rule. */
@Composable
private fun ChartFooter(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
        HorizontalDivider(color = Palette.hairline)
        Row(modifier = Modifier.fillMaxWidth()) {
            items.forEach { (label, value) ->
                Column(modifier = Modifier.weight(1f)) {
                    Overline(label, color = Palette.textTertiary)
                    Text(value, style = NoopType.bodyNumber, color = Palette.textPrimary)
                }
            }
        }
    }
}

// MARK: - Recovery history strip (stands in for the macOS YearHeatStrip)

/**
 * The recovery history card. macOS shows a YearHeatStrip (a 53-week calendar heat grid);
 * that bespoke component has no Android foundation equivalent, so we plot the real
 * per-day recovery series as a bar strip over the same window and note the difference.
 * Always shows at least a full year of context, like the macOS strip.
 */
@Composable
private fun RecoveryHistoryCard(days: List<DailyMetric>, range: TrendsRange) {
    // PERF (#scroll-jank): memoise the window slice + recovery extraction on (days, range) so the
    // 800+-day takeLast + mapNotNull don't re-run on every recomposition (e.g. the staggered-appear
    // animation frames that drive this whole strip). Same span rule, same values, same order , purely
    // skips redundant re-slicing. NOTE: the bars are NOT caller-downsampled , BarChart already mean-
    // bucket-downsamples internally to ~one bar per horizontal pixel (pixel-identical), so a second,
    // coarser caller-side bucket (e.g. ≤180) would visibly widen the bars and is deliberately avoided.
    val recovery = remember(days, range) {
        // Always show at least a year; expand to all history on ALL.
        val span = (range.days ?: days.size).coerceAtLeast(365)
        days.takeLast(span).mapNotNull { it.recovery }
    }
    val title = if (range == TrendsRange.All && days.size > 365) {
        "Charge , all history"
    } else {
        "Charge , past year"
    }

    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            SectionHeader(title, overline = stringResource(R.string.trends_calendar), trailing = "${recovery.size} days")
            if (recovery.size >= 2) {
                BarChart(
                    values = recovery,
                    modifier = Modifier.height(Metrics.trendStripHeight),
                    color = Palette.accent,
                )
            } else {
                SparsePlaceholder(height = Metrics.trendStripHeight)
            }
            HorizontalDivider(color = Palette.hairline)
            Text(
                stringResource(R.string.trends_calendar_footnote),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

// MARK: - Shared bits

/**
 * A glowing dot pinned to a LineChart's latest sample , the Bevel "now" end-cap (a soft halo + bright
 * core + white centre), matching Today's OverviewHRChart. Drawn as a sibling overlay so the shared
 * LineChart stays untouched; it reproduces that chart's point geometry exactly (strokePx 2.5, top/
 * bottom pad strokePx+4, finite-value min/max) so the cap sits on the curve's final point.
 */
@Composable
private fun GlowEndCap(values: List<Double>, tipColor: Color) {
    val clean = remember(values) { values.filter { it.isFinite() } }
    if (clean.size < 2) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        val strokePx = 2.5f
        val topPad = strokePx + 4f
        val bottomPad = strokePx + 4f
        val minV = clean.min()
        val maxV = clean.max()
        val span = (maxV - minV).takeIf { it > 0.0 } ?: 1.0
        val usableH = (size.height - topPad - bottomPad).coerceAtLeast(1f)
        val x = size.width  // the latest point sits at the right edge
        val norm = ((clean.last() - minV) / span).toFloat().coerceIn(0f, 1f)
        val y = topPad + (1f - norm) * usableH
        val center = Offset(x, y)
        drawCircle(color = tipColor.copy(alpha = 0.30f), radius = 9f, center = center)
        drawCircle(color = tipColor.copy(alpha = 0.65f), radius = 5.5f, center = center)
        drawCircle(color = Palette.tipCore, radius = 2.4f, center = center)
    }
}

/** Inset well shown when a window has too few points to plot, mirroring sparsePlaceholder. */
@Composable
private fun SparsePlaceholder(height: Dp = Metrics.chartHeight) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(Metrics.cornerSm))
            .background(Palette.surfaceInset),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.trends_not_enough_data),
            style = NoopType.subhead,
            color = Palette.textTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyTrends() {
    DataPendingNote(
        title = stringResource(R.string.trends_empty_title),
        body = stringResource(R.string.trends_empty_body),
    )
}

// MARK: - Small numeric helpers

private const val EM_DASH = "—"

private fun List<Double>.averageOrNull(): Double? =
    if (isEmpty()) null else sum() / size
