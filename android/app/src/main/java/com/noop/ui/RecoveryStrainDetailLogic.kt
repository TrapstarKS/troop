package com.noop.ui

import kotlin.math.floor
import kotlin.math.roundToLong

internal object RecoveryStrainDetailLogic {
    enum class TargetStatus { Unavailable, Under, Optimal, Over }

    data class ZoneDistribution(val minutes: List<Double>, val imported: Boolean)

    fun comparisonValue(value: Double?, decimals: Int): Double? {
        if (value == null || !value.isFinite() || decimals !in 0..1) return null
        val factor = if (decimals == 0) 1.0 else 10.0
        val scaled = value * factor
        val magnitude = kotlin.math.abs(scaled)
        val whole = floor(magnitude)
        val rounded = if (scaled.isFinite()) Math.copySign(whole + if (magnitude - whole >= 0.5) 1.0 else 0.0, scaled) / factor else value
        return if (rounded == 0.0) 0.0 else rounded
    }

    fun comparisonDelta(current: Double?, mean: Double?, decimals: Int): Double? {
        val shownCurrent = comparisonValue(current, decimals) ?: return null
        val shownMean = comparisonValue(mean, decimals) ?: return null
        return comparisonValue(shownCurrent - shownMean, decimals)
    }

    fun zoneDistribution(
        importedPercentages: List<Double>?,
        durationSeconds: Double,
        recordedMinutes: List<Double>? = null,
    ): ZoneDistribution? {
        if (importedPercentages != null && durationSeconds.isFinite() && durationSeconds > 0) {
            return ZoneDistribution(importedPercentages.map { durationSeconds / 60 * it / 100 }, true)
        }
        return recordedMinutes?.let { ZoneDistribution(it, false) }
    }

    fun timestampSeconds(value: Double?): Long? = value
        ?.takeIf { it.isFinite() && it >= Long.MIN_VALUE.toDouble() && it < Long.MAX_VALUE.toDouble() }
        ?.let { floor(it).toLong() }

    fun strainWindow(
        calendarStart: Long,
        nextCalendarStart: Long,
        isCurrentDay: Boolean,
        sleepOnsetMode: Boolean,
        onset: Long?,
        nextOnset: Long?,
        now: Long,
    ): LongRange? {
        if (calendarStart >= nextCalendarStart) return null
        val start = if (sleepOnsetMode) onset ?: calendarStart else calendarStart
        val boundary = (if (sleepOnsetMode) nextOnset else null) ?: nextCalendarStart.takeIf { !isCurrentDay }
        val end = if (boundary != null) {
            if (boundary <= start) return null
            minOf(now, boundary - 1)
        } else now
        return (start..end).takeIf { start <= end }
    }

    fun recoveryPercent(score: Double?): Int? =
        score?.takeIf { it.isFinite() && it in 0.0..100.0 }?.let { floor(it).toInt() }

    fun wholeNumber(value: Double?): Long? =
        value?.takeIf { it.isFinite() && it >= 0 && it < Long.MAX_VALUE.toDouble() }?.roundToLong()

    fun durationMinutes(seconds: Double?, fallbackSeconds: Double? = null): Long? =
        (seconds ?: fallbackSeconds)?.let { wholeNumber(it / 60) }

    /** Summed seconds of the strength sessions, null when none. Swift twin: `strengthSeconds`. */
    fun strengthSeconds(rows: List<com.noop.data.WorkoutRow>): Double? {
        val strength = rows.filter { com.noop.analytics.HealthspanHistory.isStrength(it.sport, it.source) }
        if (strength.isEmpty()) return null
        return strength.sumOf { it.durationS ?: (it.endTs - it.startTs).toDouble() }
    }

    fun priorMean(
        dayKeys: List<String>,
        values: List<Double?>,
        fromDay: String,
        selectedDay: String,
    ): Double? {
        val prior = dayKeys.zip(values).mapNotNull { (day, value) ->
            value?.takeIf { day >= fromDay && day < selectedDay && it.isFinite() }
        }
        return prior.takeIf { it.isNotEmpty() }?.average()
    }

    fun targetStatus(displayedStrain: String?, lower: Int?, upper: Int?): TargetStatus {
        val strain21 = displayedStrain?.toDoubleOrNull()
        if (strain21 == null || !strain21.isFinite() || lower == null || upper == null || lower > upper) {
            return TargetStatus.Unavailable
        }
        return when {
            strain21 < lower -> TargetStatus.Under
            strain21 > upper -> TargetStatus.Over
            else -> TargetStatus.Optimal
        }
    }
}
