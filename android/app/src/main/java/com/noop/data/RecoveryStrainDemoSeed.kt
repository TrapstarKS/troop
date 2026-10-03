package com.noop.data

import java.time.ZoneId

internal object RecoveryStrainDemoSeed {
    suspend fun seed(repo: WhoopRepository, now: Long = System.currentTimeMillis() / 1000) {
        val zone = ZoneId.systemDefault()
        val today = java.time.Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        val todayStart = today.atStartOfDay(zone).toEpochSecond()
        val elapsedMinutes = ((now - todayStart) / 60).toInt()
        val endMinute = minOf(1125, elapsedMinutes)
        val startMinute = maxOf(0, endMinute - 45)
        val samples = ArrayList<HrSample>(7 * 1440)
        for (offset in 6 downTo 0) {
            val date = today.minusDays(offset.toLong())
            val dayStart = date.atStartOfDay(zone).toEpochSecond()
            val nextStart = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
            var timestamp = dayStart
            while (timestamp < nextStart && timestamp <= now) {
                val minute = ((timestamp - dayStart) / 60).toInt()
                val exercise = if (offset == 0) minute in startMinute until endMinute else minute in 1080 until 1125
                samples += HrSample("my-whoop", timestamp, if (exercise) 130 + (minute % 12) * 3 else 58 + minute % 24)
                timestamp += 60
            }
        }
        repo.insertHr(samples)
        if (endMinute <= startMinute) return
        val start = todayStart + startMinute * 60
        val end = todayStart + endMinute * 60
        val activityHr = samples.filter { it.ts >= start && it.ts < end }.map { it.bpm }
        repo.upsertWorkouts(listOf(WorkoutRow(
            deviceId = "my-whoop", startTs = start, endTs = end, sport = "Running", source = "manual",
            durationS = (end - start).toDouble(), energyKcal = 310.0,
            avgHr = activityHr.average().toInt(), maxHr = activityHr.maxOrNull(), strain = 52.0,
        )))
    }
}
