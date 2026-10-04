package com.noop.ui

import com.noop.data.HrBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepHeartRateTest {
    @Test fun actualWindowFiltersMissingDataAndBreaksLongGaps() {
        fun point(ts: Long, bpm: Double = 60.0) = HrBucket(ts, bpm, bpm, bpm)
        val buckets = listOf(point(900), point(660), point(120), point(60), point(1100), point(180, Double.NaN))
        assertEquals(listOf(listOf(60L, 120L), listOf(660L, 900L)),
            sleepHeartRateRuns(buckets, 60, 1000).map { run -> run.map { it.bucket } })
        assertEquals(listOf(listOf(60L, 120L)),
            sleepHeartRateRuns(buckets, 60, 600).map { run -> run.map { it.bucket } })
        assertTrue(sleepHeartRateRuns(buckets, null, 1000).isEmpty())
        assertTrue(sleepHeartRateRuns(buckets, 60, 60).isEmpty())
        assertTrue(sleepHeartRateRuns(emptyList(), 60, 1000).isEmpty())
    }
}
