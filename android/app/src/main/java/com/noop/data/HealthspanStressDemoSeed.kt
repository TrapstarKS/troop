package com.noop.data

import com.noop.BuildConfig
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object HealthspanStressDemoSeed {
    /** Swift twin: `HealthspanStressDemoSeed.seedIfDemo`. */
    suspend fun seedIfDemo(repo: WhoopRepository, seededNow: Boolean, pristineBeforeBaseSeed: Boolean = false) {
        if (!BuildConfig.ENABLE_DEMO) return
        if (!seededNow && repo.pairedDevices().none { it.id == "demo-polar-h10" }) return

        // ponytail: launch-time coverage is seeded once; reset the demo store when fresh coverage is needed.
        if (!pristineBeforeBaseSeed && !hasEmptyStreams(repo)) return

        // Synthetic banked HR only; never produced by the strap or used outside the demo seed.
        val hourlyBpm = listOf(52, 56, 64, 68, 72, 78, 84, 88, 82, 74, 68, 76, 80, 70, 60, 54)
        val now = Instant.now()
        val latestTs = now.epochSecond
        val zone = ZoneId.systemDefault()
        val today = now.atZone(zone).toLocalDate()
        seedDailyStressIfMissing(repo, today)
        seedYesterdayActivityIfMissing(repo, today, zone)
        val hr = ArrayList<HrSample>()
        for (dayOffset in -1L..0L) {
            val day = today.plusDays(dayOffset)
            for (hour in 6 until 22) {
                val startTs = day.atTime(hour, 0).atZone(zone).toEpochSecond()
                for (sample in 0 until 600) {
                    val ts = startTs + sample * 6
                    if (ts > latestTs) break
                    val bpm = hourlyBpm[hour - 6] + (sample / 10) % 5 - 2
                    hr.add(HrSample("my-whoop", ts, bpm))
                }
            }
        }
        repo.insertHr(hr)
    }

    /** Swift twin: HealthspanStressDemoSeed.hasEmptyStreams */
    suspend fun hasEmptyStreams(repo: WhoopRepository): Boolean {
        val counts = repo.storageRowCounts()
        val biometricTables = listOf("hr", "rr", "spo2", "skinTemp", "steps", "resp", "gravity",
            "ppgHr", "sleepState", "ppgWaveform", "v18Aux")
        return biometricTables.all { counts[it] == 0 }
    }

    // Swift twin: HealthspanStressDemoSeed.seedYesterdayActivityIfMissing.
    private suspend fun seedYesterdayActivityIfMissing(repo: WhoopRepository, today: LocalDate, zone: ZoneId) {
        val yesterday = today.minusDays(1)
        val from = yesterday.atTime(6, 0).atZone(zone).toEpochSecond()
        val to = yesterday.atTime(22, 0).atZone(zone).toEpochSecond()
        val dayEnd = today.atStartOfDay(zone).toEpochSecond() - 1
        for (source in listOf("my-whoop", "apple-health")) {
            val existing = repo.workouts(source, 0L, dayEnd, limit = Int.MAX_VALUE)
            if (existing.any { it.endTs > it.startTs && it.startTs < to && it.endTs > from }) return
        }

        // Synthetic activity context for the yesterday HR fixture; no physiological metrics are fabricated.
        val start = yesterday.atTime(12, 0).atZone(zone).toEpochSecond()
        repo.upsertWorkouts(listOf(
            WorkoutRow(deviceId = "my-whoop", startTs = start, endTs = start + 1200, sport = "Walking",
                source = "my-whoop", durationS = 1200.0, energyKcal = null, avgHr = null, maxHr = null,
                strain = null, distanceM = null, zonesJSON = null, notes = "Synthetic demo activity overlay",
                routePolyline = null, steps = null),
        ))
    }

    /** Swift twin: `HealthspanStressDemoSeed.seedDailyStressIfMissing`. */
    private suspend fun seedDailyStressIfMissing(repo: WhoopRepository, today: LocalDate) {
        if (repo.metricSeries("my-whoop", "stress", "0000-00-00", "9999-99-99").isNotEmpty()) return

        // Synthetic daily preview fixtures, independent of the raw-HR stress analysis.
        repo.upsertMetricSeries(listOf(
            MetricSeriesRow("my-whoop", today.minusDays(1).toString(), "stress", 1.8),
            MetricSeriesRow("my-whoop", today.toString(), "stress", 1.2),
        ))
    }
}
