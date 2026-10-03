package com.noop.ble

import com.noop.data.DailyMetric
import com.noop.analytics.IllnessWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class NotifyDayStateCacheTest {

    private fun days(): List<DailyMetric> = (1..14).map { day ->
        DailyMetric(
            deviceId = "my-whoop",
            day = "2026-07-${day.toString().padStart(2, '0')}",
            recovery = day.toDouble(),
            strain = (day + 20).toDouble(),
        )
    }

    @Test
    fun emptyHistoryCannotProduceAnIllnessAlert() {
        var illnessCalls = 0
        val cache = NotifyDayStateCache {
            illnessCalls += 1
            IllnessWatch.Evaluation("stale alert", true)
        }

        val state = cache.resolve(emptyList(), "2026-07-14", "2026-07-14", illnessEnabled = true)
        assertNull(state.illness)
        assertEquals(0, illnessCalls)
    }

    @Test
    fun liveTicksReuseDailyProjectionWhileInputsAreUnchanged() {
        var illnessCalls = 0
        val cache = NotifyDayStateCache {
            illnessCalls += 1
            IllnessWatch.Evaluation("alert-$illnessCalls", true)
        }
        val days = days()

        val first = cache.resolve(days, "2026-07-14", "2026-07-14", illnessEnabled = true)
        repeat(1_000) {
            assertSame(first, cache.resolve(days, "2026-07-14", "2026-07-14", illnessEnabled = true))
        }

        assertEquals(1, illnessCalls)
        assertEquals(14.0, first.todayRecovery)
        assertEquals(14, first.widgetRecovery)
        assertEquals(34, first.widgetEffort)
    }

    @Test
    fun newRoomEmissionRefreshesEvenWhenRowsCompareEqual() {
        var illnessCalls = 0
        val cache = NotifyDayStateCache {
            illnessCalls += 1
            IllnessWatch.Evaluation(null, true)
        }
        val firstDays = days()
        val secondDays = ArrayList(firstDays)

        val first = cache.resolve(firstDays, "2026-07-14", "2026-07-14", illnessEnabled = true)
        val second = cache.resolve(secondDays, "2026-07-14", "2026-07-14", illnessEnabled = true)

        assertNotSame(first, second)
        assertEquals(2, illnessCalls)
        assertSame(secondDays, second.days)
    }

    @Test
    fun preferenceChangeClearsIllnessWithoutWaitingForRoom() {
        var illnessCalls = 0
        val cache = NotifyDayStateCache {
            illnessCalls += 1
            IllnessWatch.Evaluation("strained", true)
        }
        val days = days()

        val enabled = cache.resolve(days, "2026-07-14", "2026-07-14", illnessEnabled = true)
        val disabled = cache.resolve(days, "2026-07-14", "2026-07-14", illnessEnabled = false)

        assertEquals("strained", enabled.illness)
        assertNull(disabled.illness)
        assertEquals(1, illnessCalls)
    }

    @Test
    fun logicalDayRolloverRefreshesWithoutWaitingForRoom() {
        var illnessCalls = 0
        val cache = NotifyDayStateCache {
            illnessCalls += 1
            IllnessWatch.Evaluation("alert-$illnessCalls", true)
        }
        val days = days()

        val before = cache.resolve(days, "2026-07-14", "2026-07-14", illnessEnabled = true)
        val after = cache.resolve(days, "2026-07-15", "2026-07-15", illnessEnabled = true)

        assertNotSame(before, after)
        assertEquals(14.0, before.todayRecovery)
        assertNull(after.todayRecovery)
        assertEquals("alert-1", before.illness)
        assertNull(after.illness)
        assertEquals(1, illnessCalls)
    }
    @Test
    fun loadingStaleAndOptedOutRowsCannotRecordAClearEdge() {
        val cache = NotifyDayStateCache { IllnessWatch.Evaluation(null, true) }
        val days = days()
        org.junit.Assert.assertFalse(cache.resolve(emptyList(), "2026-07-14", "2026-07-14", true).illnessEvaluated)
        org.junit.Assert.assertFalse(cache.resolve(days, "2026-07-15", "2026-07-15", true).illnessEvaluated)
        org.junit.Assert.assertFalse(cache.resolve(days, "2026-07-14", "2026-07-14", false).illnessEvaluated)
        org.junit.Assert.assertTrue(cache.resolve(days, "2026-07-14", "2026-07-14", true).illnessEvaluated)
    }

    @Test
    fun markerEligibilityChangesRefreshTheAlertWithoutDailyRowChanges() {
        var calls = 0
        val cache = NotifyDayStateCache { calls++; IllnessWatch.Evaluation(null, true) }
        val days = days()
        val valid = days.map { it.copy(avgHrv = 60.0) }
        val withheld = days.map { it.copy(avgHrv = null) }
        val first = cache.resolve(days, "2026-07-14", "2026-07-14", true, valid)
        val second = cache.resolve(days, "2026-07-14", "2026-07-14", true, withheld)
        assertNotSame(first, second)
        assertEquals(2, calls)
    }
    @Test
    fun fourteenEmptyDailyRowsAreNotAValidClearEvaluation() {
        val state = NotifyDayStateCache().resolve(days(), "2026-07-14", "2026-07-14", true)
        org.junit.Assert.assertFalse(state.illnessEvaluated)
    }
}
