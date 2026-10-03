package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import com.noop.R
import com.noop.data.DailyMetric
import com.noop.data.SleepSession
import java.util.Locale
import kotlin.math.roundToInt

internal fun selectedSleepDetailModel(
    days: List<DailyMetric>,
    night: HeroNight?,
    imported: ImportedSleepSeries,
    napSleepMinByDay: Map<String, Double>,
    sessions: List<SleepSession>,
    is24h: Boolean,
    habitualMidsleepSec: Long? = null,
): SleepModel? {
    val day = night?.dayKey ?: return null
    if (days.none { it.day == day }) return null
    val model = buildSleepModel(
        days = days.filter { it.day <= day },
        session = night.session,
        imported = imported,
        selectedDay = day,
        heroStages = night.groupStages,
        heroSegments = night.groupSegments,
        napSleepMinByDay = napSleepMinByDay,
        sessions = consistencyNightSpans(sessions.filter { localDayString(it.endTs) <= day },
            habitualMidsleepSec, Int.MAX_VALUE).map { (onset, wake) ->
                SleepSession(deviceId = "", startTs = onset, endTs = wake)
            },
        todayKey = day,
        is24h = is24h,
    )
    val debtAvailable = imported.debtMin[day] != null || days.lastOrNull { it.day == day }
        ?.totalSleepMin?.let { it > 0.0 } == true
    return if (debtAvailable) model else model?.copy(
        sleepDebt = model.sleepDebt.copy(latest = null, latestDay = null),
    )
}

internal fun Metric?.selectedValue(): Double? =
    this?.takeIf { it.latestDay == null }?.latest?.takeIf { it.isFinite() }

@Composable
internal fun SleepPerformanceSummary(
    score: Double?,
    efficiencyPct: Double?,
    detail: SleepModel?,
    source: String,
    importedScore: Boolean,
    onMetricClick: (String) -> Unit,
) {
    val sufficiency = detail?.hoursVsNeeded.selectedValue()
    val consistency = detail?.consistency.selectedValue()
    val efficiency = efficiencyPct
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            ScoreDial(
                label = stringResource(R.string.whoop_sleep_performance),
                value = score?.takeIf { it.isFinite() }?.roundToInt()?.toString() ?: "—",
                unit = if (score != null) "%" else "",
                progress = score?.toFloat()?.div(100f),
                color = Palette.sleepPrimary,
            )
            SourceBadge(text = source, tint = Palette.sleepPrimary)
        }
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                SleepContributor(stringResource(R.string.l10n_sleep_screen_hours_vs_needed_500a0aca),
                    sufficiency, 70.0, 85.0, { onMetricClick("hours_vs_needed") })
                SleepContributor(stringResource(R.string.l10n_sleep_screen_consistency_0ea7b95e),
                    consistency, 70.0, 80.0, { onMetricClick("consistency") })
                SleepContributor(stringResource(R.string.l10n_trends_explore_screen_sleep_efficiency_b4b5c293),
                    efficiency, 80.0, 90.0, { onMetricClick("efficiency") })
                SleepContributor(stringResource(R.string.whoop_sleep_high_stress), null, 0.0, 0.0)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Metrics.cornerBadge))
                    .background(Palette.surfaceInset).padding(Metrics.space10),
                    horizontalArrangement = Arrangement.SpaceEvenly) {
                    SleepQualityKey(stringResource(R.string.whoop_sleep_poor), Palette.statusWarning)
                    SleepQualityKey(stringResource(R.string.whoop_sleep_sufficient), Palette.textSecondary)
                    SleepQualityKey(stringResource(R.string.whoop_sleep_optimal), Palette.positive)
                }
            }
        }
        InsightCallout(text = stringResource(
            if (importedScore) R.string.whoop_sleep_imported_insight else R.string.whoop_sleep_local_insight,
        ))
    }
}

@Composable
private fun SleepContributor(label: String, value: Double?, sufficient: Double, optimal: Double,
    onClick: (() -> Unit)? = null) {
    val quality = value?.let { if (it >= optimal) 2 else if (it >= sufficient) 1 else 0 }
    val color = when (quality) {
        0 -> Palette.statusWarning
        1 -> Palette.textSecondary
        2 -> Palette.positive
        else -> Palette.textTertiary
    }
    val description = when (quality) {
        0 -> stringResource(R.string.whoop_sleep_poor)
        1 -> stringResource(R.string.whoop_sleep_sufficient)
        2 -> stringResource(R.string.whoop_sleep_optimal)
        else -> stringResource(R.string.whoop_sleep_unavailable)
    }
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(vertical = Metrics.space12), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space10)) {
        Text(label.uppercase(Locale.getDefault()), style = NoopType.overline, color = Palette.textPrimary,
            modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space2)) {
            repeat(3) { index ->
                Box(Modifier.width(Metrics.space12).height(Metrics.space4)
                    .clip(RoundedCornerShape(Metrics.cornerXs))
                    .background(if (index == quality) color else Palette.ringTrack))
            }
        }
        Column(horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
            Text(value?.let { uiString(R.string.l10n_sleep_screen_percent_2281d326, it.roundToInt()) } ?: "—", style = NoopType.chartValueLarge,
                color = Palette.textPrimary)
            Text(description, style = NoopType.footnote, color = color)
        }
    }
}

@Composable
private fun SleepQualityKey(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space4)) {
        Box(Modifier.width(Metrics.space8).height(Metrics.space4)
            .clip(RoundedCornerShape(Metrics.cornerXs)).background(color))
        Text(label, style = NoopType.footnote, color = Palette.textSecondary)
    }
}

@Composable
internal fun SleepSupportingMetrics(
    detail: SleepModel?,
    stages: Stages?,
    efficiencyPct: Double?,
    asleepMin: Double?,
    needMin: Double?,
    onMetricClick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        TrackedSectionHeader(title = stringResource(R.string.l10n_sleep_screen_night_detail_8f271bcf))
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            MetricCard(label = stringResource(R.string.whoop_sleep_hours_slept),
                value = asleepMin?.let(::durationText) ?: "—", color = Palette.sleepPrimary,
                modifier = Modifier.weight(1f))
            MetricCard(label = stringResource(R.string.whoop_sleep_need),
                value = needMin?.let(::durationText) ?: "—", modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            MetricCard(label = stringResource(R.string.whoop_sleep_restorative),
                value = stages?.let { durationText(it.deep + it.rem) } ?: "—",
                detail = detail?.restorative.selectedValue()?.let {
                    uiString(R.string.l10n_sleep_screen_percent_2281d326, it.roundToInt())
                },
                color = Palette.sleepREM, modifier = Modifier.weight(1f).clickable { onMetricClick("restorative") })
            MetricCard(label = stringResource(R.string.l10n_trends_explore_screen_sleep_efficiency_b4b5c293),
                value = efficiencyPct?.roundToInt()?.toString() ?: "—",
                unit = if (efficiencyPct != null) "%" else "",
                color = Palette.sleepPrimary, modifier = Modifier.weight(1f).clickable { onMetricClick("efficiency") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            MetricCard(label = stringResource(R.string.l10n_health_screen_respiratory_rate_3fbb532f),
                value = detail?.respiratory.selectedValue()?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—",
                unit = if (detail?.respiratory.selectedValue() != null) stringResource(R.string.whoop_sleep_breaths_minute) else "",
                modifier = Modifier.weight(1f).clickable { onMetricClick("respiratory") })
            MetricCard(label = stringResource(R.string.l10n_sleep_screen_sleep_debt_3aec7d9c),
                value = detail?.sleepDebt.selectedValue()?.let(::durationText) ?: "—",
                color = Palette.sleepPrimary, modifier = Modifier.weight(1f).clickable { onMetricClick("sleep_debt") })
        }
    }
}
