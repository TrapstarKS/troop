package com.noop.data

import com.noop.analytics.IllnessWatch
import com.noop.notif.IllnessAlertPolicy
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class IllnessHistoryTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun sourceSwitchInvalidatesTheOldRecordWhileTheNewJoinedReadLoads() = runTest {
        val day = DailyMetric("alpha-noop", "2026-06-10", avgHrv = 40.0)
        val windows = MutableStateFlow("alpha" to listOf(day))
        val alpha = MutableSharedFlow<List<HrvProvenanceRow>>()
        val beta = MutableSharedFlow<List<HrvProvenanceRow>>()
        val dao = Proxy.newProxyInstance(WhoopDao::class.java.classLoader, arrayOf(WhoopDao::class.java)) { _, method, args ->
            check(method.name == "hrvProvenanceFlow")
            @Suppress("UNCHECKED_CAST")
            val ids = args!![0] as List<String>
            if (ids.first() == "alpha") alpha else beta
        } as WhoopDao
        val seen = mutableListOf<IllnessHistory.Snapshot?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            IllnessHistory.observe(WhoopRepository(dao), windows).collect { seen += it }
        }
        runCurrent()
        assertNull(seen.last())
        alpha.emit(listOf(HrvProvenanceRow("alpha-noop", day.day, 40.0, 1.0, 0.0)))
        runCurrent()
        val old = seen.last()!!
        assertTrue(old.hrvReliabilityByDay.getValue(day.day).matches(day.avgHrv))
        windows.value = "beta" to listOf(day)
        runCurrent()
        assertNull(seen.last())
        assertFalse(old.isCurrent(listOf(day), "beta"))
        assertFalse(old.isCurrent(listOf(day.copy(avgHrv = 20.0)), "alpha"))
        alpha.emit(listOf(HrvProvenanceRow("alpha-noop", day.day, 40.0, 1.0, 0.0)))
        assertNull(seen.last())
        beta.emit(listOf(HrvProvenanceRow("beta-noop", day.day, 40.0, null, null)))
        runCurrent()
        val current = seen.last()!!
        assertTrue(current.isCurrent(listOf(day), "beta"))
        assertFalse(current.hrvReliabilityByDay.getValue(day.day).matches(day.avgHrv))
    }

    private fun resolve(days: List<DailyMetric>, imported: List<DailyMetric>,
                        fresh: Map<String, Double>, overcount: Map<String, Double>): IllnessHistory.Snapshot {
        val rows = imported.mapNotNull { row -> row.avgHrv?.let {
            HrvProvenanceRow("my-whoop", row.day, it, null, null)
        } } + days.mapNotNull { row -> row.avgHrv?.let {
            HrvProvenanceRow("my-whoop-noop", row.day, it, fresh[row.day], overcount[row.day])
        } }
        return IllnessHistory.resolve(days, rows, listOf("my-whoop", "my-whoop-noop"), listOf("my-whoop-noop"))
    }

    @Test fun activeHrvCannotBorrowCanonicalMarkersEvenWhenValuesAreEqual() {
        val day = DailyMetric("active-noop", "2026-06-10", avgHrv = 40.0)
        val rows = listOf(HrvProvenanceRow("active-noop", day.day, 40.0, null, null),
            HrvProvenanceRow("my-whoop-noop", day.day, 40.0, 1.0, 0.0))
        val result = IllnessHistory.resolve(listOf(day), rows,
            listOf("active-noop", "my-whoop-noop"), listOf("active-noop", "my-whoop-noop"))
        assertNull(result.alertDays.single().avgHrv)
        assertFalse(result.hrvReliabilityByDay.getValue(day.day).matches(day.avgHrv))
        val fallback = IllnessHistory.resolve(listOf(day), rows.drop(1),
            listOf("active-noop", "my-whoop-noop"), listOf("active-noop", "my-whoop-noop"))
        assertEquals(40.0, fallback.alertDays.single().avgHrv!!, 0.0)
    }

    @Test fun changedMergedValueCannotUseAnOlderJoinedProjection() {
        val day = DailyMetric("active-noop", "2026-06-10", avgHrv = 20.0)
        val result = IllnessHistory.resolve(listOf(day), listOf(HrvProvenanceRow("active-noop", day.day, 40.0, 1.0, 0.0)),
            listOf("active-noop"), listOf("active-noop"))
        assertNull(result.alertDays.single().avgHrv)
        assertFalse(result.hrvReliabilityByDay.getValue(day.day).matches(day.avgHrv))
    }

    @Test fun importedDeviceIdDoesNotMakeComputedGapFillAnImportedHrv() {
        val imported = DailyMetric(deviceId = "my-whoop", day = "2026-06-10", restingHr = 50)
        val merged = imported.copy(avgHrv = 40.0)
        val result = resolve(listOf(merged), listOf(imported), emptyMap(), emptyMap())
        assertFalse(result.hrvReliabilityByDay.getValue(merged.day).matches(merged.avgHrv))
        assertNull(result.alertDays.single().avgHrv)
        assertEquals(40.0, result.days.single().avgHrv!!, 0.0)
    }

    @Test fun validWinningImportedHrvIgnoresComputedInvalidation() {
        val imported = DailyMetric(deviceId = "my-whoop", day = "2026-06-10", avgHrv = 40.0)
        val result = resolve(listOf(imported), listOf(imported),
            mapOf(imported.day to 0.0), mapOf(imported.day to 1.0))
        assertTrue(result.hrvReliabilityByDay.getValue(imported.day).matches(imported.avgHrv))
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
        val unknown = resolve(days, emptyList(), markers, emptyMap())
        val unknownEvaluation = IllnessWatch.evaluateWindow(unknown.alertDays)
        assertFalse(unknownEvaluation.valid)
        var previous: Boolean? = true
        if (IllnessAlertPolicy.shouldRecordEvaluation(true, unknownEvaluation.valid))
            previous = unknownEvaluation.alert != null
        val usable = resolve(days, emptyList(), days.associate { it.day to 1.0 }, emptyMap())
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
