package com.noop.analytics

import kotlin.math.floor

/** Stored Body Age and step observations; Body Age is not a validated biological-age model. */
object HealthspanPresentation {
    data class AgeSample(val daysAgo: Int, val age: Double)
    data class StepSample(val day: String, val count: Double, val source: String)
    data class Snapshot(val eligibility: HealthspanHistory.Eligibility, val age: Double?, val paceTenths: Int?, val recoveryDays: Int,
                        val recentSamples: Int, val historySamples: Int) {
        val pace: Double? get() = paceTenths?.div(10.0)
    }

    /** Swift twin: `HealthspanPresentation.snapshot`. */
    fun snapshot(samples: List<AgeSample>, recoveryOffsets: List<Int>, chronologicalAge: Double): Snapshot {
        val byDay = mutableMapOf<Int, Double>()
        for (sample in samples) {
            if (sample.daysAgo in 0 until 180 && sample.age.isFinite() && sample.age in 20.0..90.0) {
                byDay[sample.daysAgo] = sample.age
            }
        }
        val offsets = byDay.keys.sorted()
        val recent = offsets.filter { it < 30 }.mapNotNull { byDay[it] }
        val history = offsets.mapNotNull { byDay[it] }
        val eligibility = HealthspanHistory.eligibility(recoveryOffsets, chronologicalAge, offsets.firstOrNull())
        val age = if (eligibility.state == HealthspanHistory.State.ready) offsets.firstOrNull()?.let { byDay[it] } else null
        val paceTenths = if (age != null && recent.size >= 3 && history.size >= 8 && (offsets.lastOrNull() ?: 0) >= 89) {
            val recentMean = recent.sum() / recent.size
            val historyMean = history.sum() / history.size
            floor((1 + 2 * (recentMean - historyMean)).coerceIn(-1.0, 3.0) * 10 + 0.5).toInt()
        } else null
        return Snapshot(eligibility, age, paceTenths, eligibility.recoveryDays, recent.size, history.size)
    }

    /** Counts non-overlapping buckets only; sliding timeline points are not duration inputs.
     * Swift twin: `HealthspanPresentation.zoneMinutes`. */
    fun zoneMinutes(hours: List<Pair<Double?, Int>>): List<Int> {
        val minutes = mutableListOf(0, 0, 0)
        for ((value, duration) in hours) {
            if (value == null || !value.isFinite() || value !in 0.0..3.0 || duration <= 0) continue
            val zone = if (value < 1) 0 else if (value < 2) 1 else 2
            minutes[zone] += duration
        }
        return minutes
    }

    /** Uses inclusive canonical ISO day keys and caller-resolved measured WHOOP samples.
     * Measured rows, including zero, win per day; imported maxima retain the first tied row.
     * Swift twin: `HealthspanPresentation.latestSteps`. */
    fun latestSteps(measured: List<StepSample>, imported: List<StepSample>,
                    fromDay: String, throughDay: String): StepSample? {
        // Swift twin: HealthspanPresentation.valid
        fun valid(sample: StepSample): Boolean =
            sample.count.isFinite() && sample.count >= 0 && sample.day >= fromDay && sample.day <= throughDay
        val byDay = mutableMapOf<String, StepSample>()
        for (sample in imported) {
            if (valid(sample) && sample.count > (byDay[sample.day]?.count ?: -1.0)) byDay[sample.day] = sample
        }
        // Reverse traversal preserves the first valid measured row for each day.
        for (sample in measured.asReversed()) {
            if (valid(sample)) byDay[sample.day] = sample
        }
        return byDay.keys.maxOrNull()?.let { byDay[it] }
    }
}
