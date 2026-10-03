package com.noop.analytics

import kotlin.math.abs
import kotlin.math.floor

/** Read-only coverage and contributor comparisons; never changes stored Body Age. */
object HealthspanHistory {
    enum class State { adultOnly, initialCalibration, recentCoverage, unavailable, ready }
    data class Eligibility(val state: State, val initialDays: Int, val recoveryDays: Int)
    data class Sample(val daysAgo: Int, val value: Double)
    data class Comparison(val recentTenths: Int?, val longTermTenths: Int?, val recentCount: Int, val longTermCount: Int)

    /** Swift twin: `HealthspanHistory.eligibility`. Earliest recovery establishes span, not wear. */
    fun eligibility(recoveryOffsets: List<Int>, chronologicalAge: Double, latestAgeDaysAgo: Int?): Eligibility {
        val offsets = recoveryOffsets.filter { it in 0 until 4000 }.toSet()
        val initialDays = offsets.maxOrNull()?.plus(1) ?: 0
        val recent = offsets.count { it < 31 }
        val state = when {
            !chronologicalAge.isFinite() || chronologicalAge < 18 -> State.adultOnly
            initialDays < 90 -> State.initialCalibration
            recent < 21 -> State.recentCoverage
            latestAgeDaysAgo == null || latestAgeDaysAgo !in 0..14 -> State.unavailable
            else -> State.ready
        }
        return Eligibility(state, initialDays, recent)
    }

    /** Swift twin: `HealthspanHistory.oldestReferenceOffset`. */
    fun oldestReferenceOffset(dayOffsets: List<Int>): Int =
        ((dayOffsets.filter { it in 0 until 4000 }.maxOrNull() ?: 0) - 30).coerceAtLeast(0)

    /** Swift twin: `HealthspanHistory.comparison`. Missing days are never filled with zero. */
    fun comparison(samples: List<Sample>, weekly: Boolean = false): Comparison {
        val recent = values(samples, 30, weekly)
        val longTerm = values(samples, 180, weekly)
        // Swift twin: HealthspanHistory.tenths
        fun tenths(values: List<Double>): Int? = if (values.isEmpty()) null else floor(values.sum() / values.size * 10 + 0.5).toInt()
        return Comparison(tenths(recent), tenths(longTerm), recent.size, longTerm.size)
    }

    /** Swift twin: `HealthspanHistory.selected`. */
    fun selected(samples: List<Sample>, daysAgo: Int, windowDays: Int): Sample? {
        return points(samples, windowDays).minByOrNull { abs(it.daysAgo - daysAgo) }
    }

    /** Swift twin: `HealthspanHistory.points`. */
    fun points(samples: List<Sample>, windowDays: Int): List<Sample> {
        val byDay = mutableMapOf<Int, Double>()
        for (sample in samples) if (sample.daysAgo in 0 until windowDays && sample.value.isFinite() && sample.value in 0.0..100_000.0) {
            byDay[sample.daysAgo] = sample.value
        }
        return byDay.keys.sorted().map { Sample(it, byDay.getValue(it)) }
    }

    data class Activity(val daysAgo: Int, val durationSeconds: Double, val zones: List<Double>?, val strength: Boolean)

    /** Swift twin: `HealthspanHistory.activitySeries`. */
    fun activitySeries(activities: List<Activity>): List<List<Sample>> {
        val totals = List(3) { mutableMapOf<Int, Double>() }
        for (activity in activities) {
            if (activity.daysAgo !in 0 until 180 || !activity.durationSeconds.isFinite() || activity.durationSeconds <= 0) continue
            val minutes = activity.durationSeconds / 60
            val zones = activity.zones
            if (zones != null && zones.size == 5 && zones.all { it.isFinite() && it in 0.0..100.0 } && zones.any { it > 0 }) {
                totals[0][activity.daysAgo] = (totals[0][activity.daysAgo] ?: 0.0) + minutes * zones.take(3).sum() / 100
                totals[1][activity.daysAgo] = (totals[1][activity.daysAgo] ?: 0.0) + minutes * zones.takeLast(2).sum() / 100
            }
            if (activity.strength) totals[2][activity.daysAgo] = (totals[2][activity.daysAgo] ?: 0.0) + minutes
        }
        return totals.map { byDay -> byDay.keys.sorted().map { Sample(it, byDay.getValue(it)) } }
    }

    /** Swift twin: `HealthspanHistory.isStrength`. */
    fun isStrength(sport: String, source: String): Boolean {
        val label = sport.lowercase(java.util.Locale.ROOT).filter { it != ' ' && it != '_' && it != '-' }
        return source == "lifting" || label in listOf("strength", "strengthtraining", "traditionalstrengthtraining", "functionalstrengthtraining", "weightlifting", "weighttraining")
    }

    // Swift twin: HealthspanHistory.values
    private fun values(samples: List<Sample>, days: Int, weekly: Boolean): List<Double> {
        val bins = mutableMapOf<Int, Double>()
        for (sample in points(samples, days)) {
            val bin = if (weekly) sample.daysAgo / 7 else sample.daysAgo
            bins[bin] = (bins[bin] ?: 0.0) + sample.value
        }
        return bins.keys.sorted().map { bins.getValue(it) }
    }
}
