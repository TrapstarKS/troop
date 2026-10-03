package com.noop.data

import com.noop.analytics.IllnessWatch
import com.noop.notif.IllnessAlertPolicy
import org.junit.Assert.*
import org.junit.Test

class IllnessHistoryTest {
    @Test fun importedDeviceIdDoesNotMakeComputedGapFillAnImportedHrv() {
        val imported = DailyMetric(deviceId = "my-whoop", day = "2026-06-10", restingHr = 50)
        val merged = imported.copy(avgHrv = 40.0)
        val result = IllnessHistory.resolve(listOf(merged), listOf(imported), emptyMap(), emptyMap())
        assertEquals(false, result.hrvReliabilityByDay[merged.day])
        assertNull(result.alertDays.single().avgHrv)
        assertEquals(40.0, result.days.single().avgHrv!!, 0.0)
    }

    @Test fun validWinningImportedHrvIgnoresComputedInvalidation() {
        val imported = DailyMetric(deviceId = "my-whoop", day = "2026-06-10", avgHrv = 40.0)
        val result = IllnessHistory.resolve(listOf(imported), listOf(imported),
            mapOf(imported.day to 0.0), mapOf(imported.day to 1.0))
        assertEquals(true, result.hrvReliabilityByDay[imported.day])
        assertEquals(40.0, result.alertDays.single().avgHrv!!, 0.0)
    }

    @Test fun sparseHrvCannotBorrowTrustedRestingHrCorroboration() {
        val baseline = (1..31).map { day -> DailyMetric(deviceId = "test", day = "2026-01-%02d".format(day),
            restingHr = 50, avgHrv = if (day in 27..30) 60.0 else null) }
        val recent = listOf("2026-02-01", "2026-02-02").map { day ->
            DailyMetric(deviceId = "test", day = day, restingHr = 58, avgHrv = 20.0) }
        assertNull(IllnessWatch.evaluate(baseline + recent))
        val trusted = baseline.map { it.copy(avgHrv = 60.0) } + recent
        assertNotNull(IllnessWatch.evaluate(trusted))
        assertNull(IllnessWatch.evaluate(trusted, 1769947200.0, 1769947200.0))
    }

    @Test fun historicalRowsDoNotBridgeAMissingCalendarWindow() {
        val baseline = (1..31).map { day -> DailyMetric(deviceId = "test", day = "2026-01-%02d".format(day),
            restingHr = 50, avgHrv = 60.0) }
        val recent = listOf("2026-03-01", "2026-03-02").map { day ->
            DailyMetric(deviceId = "test", day = day, restingHr = 58, avgHrv = 20.0) }
        assertNull(IllnessWatch.evaluate(baseline + recent))
    }
    @Test fun unknownFreshHrvDoesNotClearThenRenotifyAnExistingRaisedPattern() {
        val baseline = (1..31).map { day -> DailyMetric(deviceId = "my-whoop-noop", day = "2026-01-%02d".format(day),
            restingHr = 50, avgHrv = 60.0) }
        val recent = listOf("2026-02-01", "2026-02-02").map { day ->
            DailyMetric(deviceId = "my-whoop-noop", day = day, restingHr = 58, avgHrv = 20.0) }
        val days = baseline + recent
        val markers = baseline.associate { it.day to 1.0 }
        val unknown = IllnessHistory.resolve(days, emptyList(), markers, emptyMap())
        val unknownEvaluation = IllnessWatch.evaluateWindow(unknown.alertDays)
        assertFalse(unknownEvaluation.valid)
        var previous: Boolean? = true
        if (IllnessAlertPolicy.shouldRecordEvaluation(true, unknownEvaluation.valid))
            previous = unknownEvaluation.alert != null
        val usable = IllnessHistory.resolve(days, emptyList(), days.associate { it.day to 1.0 }, emptyMap())
        val raised = IllnessWatch.evaluateWindow(usable.alertDays)
        assertTrue(raised.valid)
        assertNotNull(raised.alert)
        assertFalse(IllnessAlertPolicy.shouldNotify(raised.alert, previous, "2026-02-01", "2026-02-02"))
        val clear = IllnessWatch.evaluateWindow(usable.alertDays.map { it.copy(restingHr = 50, avgHrv = 60.0) })
        assertTrue(clear.valid)
        assertNull(clear.alert)
        if (IllnessAlertPolicy.shouldRecordEvaluation(true, clear.valid)) previous = clear.alert != null
        assertTrue(IllnessAlertPolicy.shouldNotify(raised.alert, previous, "2026-02-01", "2026-02-02"))
    }
}
