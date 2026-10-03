package com.noop.data

import com.noop.BuildConfig
import java.time.Instant
import java.time.ZoneId

object HealthspanStressDemoSeed {
    suspend fun seedIfDemo(repo: WhoopRepository, seededNow: Boolean) {
        if (!BuildConfig.ENABLE_DEMO) return
        if (!seededNow && repo.pairedDevices().none { it.id == "demo-polar-h10" }) return

        val counts = repo.storageRowCounts()
        val biometricTables = listOf("hr", "rr", "spo2", "skinTemp", "steps", "resp", "gravity",
            "ppgHr", "sleepState", "ppgWaveform", "v18Aux")
        // ponytail: launch-time coverage is seeded once; reset the demo store when fresh coverage is needed.
        if (biometricTables.any { counts[it] != 0 }) return

        // Synthetic banked HR only; never produced by the strap or used outside the demo seed.
        val hourlyBpm = listOf(52, 56, 64, 68, 72, 78, 84, 88, 82, 74, 68, 76, 80, 70, 60, 54)
        val now = Instant.now()
        val latestTs = now.epochSecond
        val zone = ZoneId.systemDefault()
        val today = now.atZone(zone).toLocalDate()
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
}
