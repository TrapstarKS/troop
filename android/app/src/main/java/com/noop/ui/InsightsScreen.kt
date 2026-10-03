package com.noop.ui

import com.noop.R
import androidx.compose.ui.res.stringResource
import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.data.DailyMetric
import com.noop.data.JournalEntry
import com.noop.data.WorkoutRow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import com.noop.analytics.BehaviorEffect
import com.noop.analytics.EffectRanker

// MARK: - Insights
//
// The "interrogate what affects what" screen, ported from the macOS InsightsView.
// Two halves:
//
//  1. BEHAVIOUR EFFECTS, split logged journal answers (the days each behaviour was
//     logged "yes" vs the days it was logged "no" — a day it was not logged at all
//     is in neither group) and compare a chosen outcome metric (Charge / HRV /
//     Rest / RHR) between the two groups. Ranked by effect size (Cohen's d), with
//     significant effects first. Compact summaries open the with/without means,
//     group counts, the association note,
//     significance, and the magnitude word.
//     Tint is sign-aware: a behaviour that moves the outcome the "good" way (respecting
//     higherIsBetter) reads positive/green, the "bad" way reads critical/red.
//
//  2. METRIC RELATIONSHIPS, a curated set of Pearson correlations between daily series
//     (HRV ↔ charge, rest ↔ charge, RHR ↔ charge, charge → next-day charge),
//     each rendered as a one-line insight with r and a plain-English reading.
//
// Data note vs macOS: the Swift app computes these via the StrandAnalytics package
// (BehaviorInsights / CorrelationEngine) over a metricSeries store. On Android the
// analytics package isn't ported, and the guaranteed outcome source is the cached
// DailyMetric rows (vm.recentDays). So the outcome series here are read straight off
// those rows (recovery / avgHrv / sleep-efficiency / restingHr) and the simple, honest
// math (group means + Cohen's d, Pearson r) is computed inline below. No fabricated
// values: a behaviour or relationship only appears when there is real overlapping data.

// MARK: - Outcome (segmented selection)

/** One interrogable outcome metric: how to read it off a DailyMetric, its label,
 *  units, and whether higher is the "good" direction (drives sign-aware tint). */
private enum class Outcome(
    val outcomeName: String,
    val higherIsBetter: Boolean,
    /** The Bevel colour world the outcome belongs to, drives the card wash so the
     *  Behaviour Effects section sits in one world (Charge→green, HRV/Rest→indigo,
     *  RHR→Stress teal), mirroring the Swift Outcome.domain. */
    val domain: DomainTheme,
    val pick: (DailyMetric) -> Double?,
    val format: (Double) -> String,
) {
    Recovery(
        outcomeName = "Recovery", higherIsBetter = true, domain = DomainTheme.Charge,
        pick = { it.recovery }, format = { "${it.roundToInt()}%" },
    ),
    Hrv(
        outcomeName = "HRV", higherIsBetter = true, domain = DomainTheme.Rest,
        pick = { it.avgHrv }, format = { "${it.roundToInt()} ms" },
    ),
    Sleep(
        outcomeName = "Sleep Performance", higherIsBetter = true, domain = DomainTheme.Rest,
        pick = { null }, format = { "${it.roundToInt()}%" },
    ),
    Rhr(
        outcomeName = "Resting HR", higherIsBetter = false, domain = DomainTheme.Stress,
        pick = { it.restingHr?.toDouble() }, format = { "${it.roundToInt()} bpm" },
    );

    val label: String
        get() = when (this) {
            Recovery -> uiString(R.string.plan_trends_recovery)
            Hrv -> "HRV"
            Sleep -> uiString(R.string.plan_trends_sleep_performance)
            Rhr -> uiString(R.string.l10n_insights_screen_rhr_04edf9b3)
        }
}

private data class EffectSelection(val effect: BehaviorEffect, val outcome: Outcome, val displayName: String)

// MARK: - Computed shapes (plain data; behaviour effects come from the analytics package)

/** A curated metric relationship plus its computed Pearson correlation. */
private data class Relationship(
    val id: String,
    val title: String,
    val blurb: String,
    val r: Double,
    val n: Int,
) {
    /** Crude significance flag for |r| with n pairs (rough p < 0.05 threshold). */
    val significant: Boolean get() = n >= 4 && abs(r) >= significanceThreshold(n)
}

/** The fully-computed insight inputs for the current data, recomputed off recentDays. */
private data class InsightModel(
    /** behaviour question → set of days it was answered "yes". */
    val behaviours: Map<String, Set<String>>,
    /** Per behaviour, the days it was logged NO — the only legitimate control group. A day with no
     *  journal row for the question is in neither map and takes part in no comparison. */
    val controls: Map<String, Set<String>>,
    /** day → value, per outcome. */
    val outcomeByDay: Map<Outcome, Map<String, Double>>,
    /** ordered (day, value) per outcome for correlations. */
    val seriesByOutcome: Map<Outcome, List<Pair<String, Double>>>,
    /**
     * #322: numeric journal item (question) → [day: value]. A numeric series is the same
     * Map<String, Double> shape EffectRanker.rank's `outcomeByDay` takes, so a numeric journal item
     * ("caffeine mg", "alcohol units") is a first-class series the ranker can consume like any metric
     * outcome (dose-response lands in the v5 hub). Empty for a yes/no-only journal.
     */
    val numericJournalSeries: Map<String, Map<String, Double>> = emptyMap(),
)

// MARK: - Screen

/**
 * Insights, behaviour effects + metric relationships over cached history.
 *
 * Loads the journal (all days) and the per-day outcome series from `vm.recentDays`,
 * then presents the ranked behaviour effects for the selected outcome and the curated
 * Pearson relationships. Empty/sparse states explain what's missing rather than faking
 * numbers, matching the macOS data-display contract.
 */
@Composable
fun InsightsScreen(vm: AppViewModel, onOpenInsightsHub: () -> Unit = {}) {
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val registryActiveId by vm.activeStrapIdFlow.collectAsStateWithLifecycle()
    val activeStrapId = registryActiveId ?: vm.activeStrapId
    var showingWeeklyPlan by remember { mutableStateOf(false) }
    if (showingWeeklyPlan) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showingWeeklyPlan = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(Modifier.fillMaxSize().background(Palette.surfaceBase)) {
                androidx.compose.material3.TextButton(onClick = { showingWeeklyPlan = false }) {
                    Text(uiString(R.string.weekly_plan_dismiss), style = NoopType.body, color = Palette.textPrimary)
                }
                WeeklyPlanScreen(vm)
            }
        }
    }

    // Journal answers (all history): imported "my-whoop" rows UNIONED with native "noop-journal"
    // rows (native wins per (day, question)). Keyed on journalSeq so the logging card's saves and
    // clears refresh the effects immediately; re-loaded too when the cached days change underneath.
    var behaviours by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var controls by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    // #322: numeric journal item (question) -> [day: value]. A numeric journal series is a daily series
    // the effect ranker consumes exactly like a metric series (EffectRanker.effect already takes a
    // Map<String, Double> outcome), so "caffeine mg" / "alcohol units" can rank as a numeric outcome.
    var numericJournalSeries by remember { mutableStateOf<Map<String, Map<String, Double>>>(emptyMap()) }
    var journalLoaded by remember { mutableStateOf(false) }
    val publishedStrapId by vm.activeStrapIdFlow.collectAsStateWithLifecycle()
    var sleepPerformance by remember(publishedStrapId) { mutableStateOf<Map<String, Double>>(emptyMap()) }
    androidx.compose.runtime.LaunchedEffect(days, publishedStrapId, activeStrapId) {
