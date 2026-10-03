package com.noop.ui

import kotlin.math.floor
import kotlin.math.roundToLong

internal object RecoveryStrainDetailLogic {
    enum class TargetStatus { Unavailable, Under, Optimal, Over }

    data class ZoneDistribution(val minutes: List<Double>, val imported: Boolean)

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
        (seconds ?: fallbackSeconds)?.let { wholeNumber(floor(it / 60)) }

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
