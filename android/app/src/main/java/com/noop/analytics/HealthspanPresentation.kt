package com.noop.analytics

import kotlin.math.floor

/** Presentation of stored Body Age, not a validated biological-age model. */
object HealthspanPresentation {
    data class AgeSample(val daysAgo: Int, val age: Double)
    data class Snapshot(val age: Double?, val paceTenths: Int?, val recoveryDays: Int,
                        val recentSamples: Int, val historySamples: Int) {
        val pace: Double? get() = paceTenths?.div(10.0)
    }

    fun snapshot(samples: List<AgeSample>, recoveryDays: Int, chronologicalAge: Double): Snapshot {
        val byDay = mutableMapOf<Int, Double>()
        for (sample in samples) {
            if (sample.daysAgo in 0 until 180 && sample.age.isFinite() && sample.age in 20.0..90.0) {
                byDay[sample.daysAgo] = sample.age
            }
        }
        val offsets = byDay.keys.sorted()
        val recent = offsets.filter { it < 30 }.mapNotNull { byDay[it] }
        val history = offsets.mapNotNull { byDay[it] }
        val ready = chronologicalAge >= 18 && chronologicalAge.isFinite() && recoveryDays >= 21
        val latest = offsets.firstOrNull()?.let { if (it <= 14) byDay[it] else null }
        val age = if (ready) latest else null
        val paceTenths = if (age != null && recent.size >= 3 && history.size >= 8 && (offsets.lastOrNull() ?: 0) >= 89) {
            val recentMean = recent.sum() / recent.size
            val historyMean = history.sum() / history.size
            floor((1 + 2 * (recentMean - historyMean)).coerceIn(-1.0, 3.0) * 10 + 0.5).toInt()
        } else null
        return Snapshot(age, paceTenths, recoveryDays.coerceAtLeast(0), recent.size, history.size)
    }

    /** Counts non-overlapping buckets only; sliding timeline points are not duration inputs. */
    fun zoneMinutes(hours: List<Pair<Double?, Int>>): List<Int> {
        val minutes = mutableListOf(0, 0, 0)
        for ((value, duration) in hours) {
            if (value == null || !value.isFinite() || value !in 0.0..3.0 || duration <= 0) continue
            val zone = if (value < 1) 0 else if (value < 2) 1 else 2
            minutes[zone] += duration
        }
        return minutes
    }
}
