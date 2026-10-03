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
    @Test fun joinedSnapshotCannotLendNewImportedProofToCachedComputedValues() {
        val cached = DailyMetric("my-whoop-noop", "2026-06-10", totalSleepMin = 321.0, efficiency = 70.0,
            deepMin = 50.0, remMin = 80.0, lightMin = 191.0, disturbances = 3, restingHr = 50, avgHrv = 40.0,
            respRateBpm = 16.0, spo2Pct = 98.0, skinTempC = 33.0, steps = 1234)
        val imported = DailyMetric("my-whoop", cached.day, totalSleepMin = 480.0, efficiency = 90.0,
            deepMin = 90.0, remMin = 100.0, lightMin = 290.0, disturbances = 1, restingHr = 58, avgHrv = 40.0,
            respRateBpm = 20.0, spo2Pct = 93.0, skinTempC = 34.0, recovery = 60.0)
        val rows = listOf(
            HrvProvenanceRow("my-whoop", cached.day, 40.0, null, null, 20.0, null, imported),
            HrvProvenanceRow("my-whoop-noop", cached.day, 40.0, 0.0, 1.0, 16.0, 0.0, cached),
        )
        val result = IllnessHistory.resolve(listOf(cached), rows,
            listOf("my-whoop", "my-whoop-noop"), listOf("my-whoop-noop"))
        assertTrue(result.isCurrent(listOf(cached), ""))
        assertEquals(listOf(imported.copy(steps = cached.steps, totalSleepMin = cached.totalSleepMin,
            efficiency = cached.efficiency, deepMin = cached.deepMin, remMin = cached.remMin,
            lightMin = cached.lightMin, disturbances = cached.disturbances)), result.vitalDays)
        assertEquals(58, result.alertDays.single().restingHr)
        assertEquals(20.0, result.alertDays.single().respRateBpm!!, 0.0)
        assertEquals(1234, result.alertDays.single().steps)
        assertTrue(result.hrvReliabilityByDay.getValue(cached.day).matches(40.0))
        assertTrue(result.respReliabilityByDay.getValue(cached.day).matches(20.0))
        assertFalse(result.respReliabilityByDay.getValue(cached.day).matches(16.0))
    }

    @Test fun joinedSnapshotKeepsIndependentPhysicalOwnersAndRejectsRemovedRows() {
        val active = DailyMetric("active-noop", "2026-06-10", respRateBpm = 16.0)
        val canonical = DailyMetric("my-whoop-noop", active.day, avgHrv = 40.0, respRateBpm = 18.0)
        val rows = listOf(
            HrvProvenanceRow(active.deviceId, active.day, null, null, null, 16.0, 1.0, active),
            HrvProvenanceRow(canonical.deviceId, canonical.day, 40.0, 1.0, 0.0, 18.0, 0.0, canonical),
        )
        val ids = listOf("active-noop", "my-whoop-noop")
        val result = IllnessHistory.resolve(listOf(active), rows, ids, ids)
        assertEquals(40.0, result.vitalDays.single().avgHrv!!, 0.0)
        assertEquals(16.0, result.alertDays.single().respRateBpm!!, 0.0)
        val imported = DailyMetric("active", active.day, totalSleepMin = 480.0, recovery = 60.0)
        val withDeletedSleep = IllnessHistory.resolve(listOf(active), rows +
            HrvProvenanceRow(imported.deviceId, imported.day, null, null, null, metric = imported),
            listOf(imported.deviceId) + ids, ids)
        assertNull(withDeletedSleep.vitalDays.single().totalSleepMin)
        assertEquals(60.0, withDeletedSleep.vitalDays.single().recovery!!, 0.0)
        assertEquals(40.0, withDeletedSleep.vitalDays.single().avgHrv!!, 0.0)
        assertEquals(16.0, withDeletedSleep.vitalDays.single().respRateBpm!!, 0.0)
        val removed = IllnessHistory.resolve(listOf(active), emptyList(), ids, ids)
        assertTrue(removed.vitalDays.isEmpty())
        assertTrue(removed.alertDays.isEmpty())
    }

    @Test fun respirationHasIndependentFreshnessAndImportedPrecedence() {
        val day = DailyMetric("active-noop", "2026-06-10", avgHrv = 40.0, respRateBpm = 16.0)
        val raw = HrvProvenanceRow("active-noop", day.day, 40.0, 0.0, 1.0, 16.0, 1.0)
        val independent = IllnessHistory.resolve(listOf(day), listOf(raw), listOf("active-noop"), listOf("active-noop"))
        assertNull(independent.alertDays.single().avgHrv)
        assertEquals(16.0, independent.alertDays.single().respRateBpm!!, 0.0)
        val imported = raw.copy(deviceId = "my-whoop", respFreshScoringValid = 0.0)
        val winningImport = IllnessHistory.resolve(listOf(day), listOf(raw, imported),
            listOf("my-whoop", "active-noop"), listOf("active-noop"))
        assertEquals(16.0, winningImport.alertDays.single().respRateBpm!!, 0.0)
        val vendorOnly = raw.copy(value = null, freshScoringValid = null)
        val vendor = IllnessHistory.resolve(listOf(day.copy(avgHrv = null)), listOf(vendorOnly),
            listOf("active-noop"), listOf("active-noop"))
        assertEquals(16.0, vendor.alertDays.single().respRateBpm!!, 0.0)
    }

    @Test fun respiratoryMarkerCannotBeBorrowedOrAppliedToADifferentValue() {
        val day = DailyMetric("active-noop", "2026-06-10", respRateBpm = 16.0)
        val active = HrvProvenanceRow("active-noop", day.day, null, null, null, 16.0, null)
        val canonical = active.copy(deviceId = "my-whoop-noop", respFreshScoringValid = 1.0)
        val result = IllnessHistory.resolve(listOf(day), listOf(active, canonical),
            listOf("active-noop", "my-whoop-noop"), listOf("active-noop", "my-whoop-noop"))
        assertNull(result.alertDays.single().respRateBpm)
        assertFalse(result.respReliabilityByDay.getValue(day.day).matches(day.respRateBpm))
        val changed = IllnessHistory.resolve(listOf(day.copy(respRateBpm = 20.0)), listOf(canonical),
            listOf("my-whoop-noop"), listOf("my-whoop-noop"))
        assertNull(changed.alertDays.single().respRateBpm)
    }

    @Test fun hrvAndRespirationMayHaveDifferentPhysicalOwners() {
        val day = DailyMetric("active-noop", "2026-06-10", avgHrv = 40.0, respRateBpm = 16.0)
        val active = HrvProvenanceRow("active-noop", day.day, null, null, null, 16.0, 1.0)
        val canonical = HrvProvenanceRow("my-whoop-noop", day.day, 40.0, 1.0, 0.0, 18.0, 0.0)
        val result = IllnessHistory.resolve(listOf(day), listOf(active, canonical),
            listOf("active-noop", "my-whoop-noop"), listOf("active-noop", "my-whoop-noop"))
        assertEquals(40.0, result.alertDays.single().avgHrv!!, 0.0)
        assertEquals(16.0, result.alertDays.single().respRateBpm!!, 0.0)
    }

    @Test fun unknownRespirationDoesNotClearAnExistingRaisedPattern() {
        val baseline = (1..31).map { DailyMetric("active-noop", "2026-01-%02d".format(it), restingHr = 50, respRateBpm = 16.0) }
        val current = listOf("2026-02-01", "2026-02-02").map { DailyMetric("active-noop", it, restingHr = 58, respRateBpm = 22.0) }
        val days = baseline + current
        val rows = days.map { HrvProvenanceRow("active-noop", it.day, null, null, null, it.respRateBpm,
            if (it.day < "2026-02-01") 1.0 else null) }
        val unknown = IllnessHistory.resolve(days, rows, listOf("active-noop"), listOf("active-noop"))
        val evaluation = IllnessWatch.evaluateWindow(unknown.alertDays)
        assertFalse(evaluation.valid)
        var previous: Boolean? = true
        if (IllnessAlertPolicy.shouldRecordEvaluation(true, evaluation.valid)) previous = evaluation.alert != null
        val fresh = IllnessHistory.resolve(days, rows.map { it.copy(respFreshScoringValid = 1.0) },
            listOf("active-noop"), listOf("active-noop"))
        val raised = IllnessWatch.evaluateWindow(fresh.alertDays)
        assertTrue(raised.valid)
        assertNotNull(raised.alert)
        assertFalse(IllnessAlertPolicy.shouldNotify(raised.alert, previous, "2026-02-01", "2026-02-02"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun sourceSwitchInvalidatesTheOldRecordWhileTheNewJoinedReadLoads() = runTest {
        val day = DailyMetric("alpha-noop", "2026-06-10", avgHrv = 40.0, respRateBpm = 16.0)
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
        alpha.emit(listOf(HrvProvenanceRow("alpha-noop", day.day, 40.0, 1.0, 0.0, 16.0, 1.0)))
        runCurrent()
        val old = seen.last()!!
        assertTrue(old.hrvReliabilityByDay.getValue(day.day).matches(day.avgHrv))
        assertTrue(old.respReliabilityByDay.getValue(day.day).matches(day.respRateBpm))
        windows.value = "beta" to listOf(day)
        runCurrent()
        assertNull(seen.last())
        assertFalse(old.isCurrent(listOf(day), "beta"))
        assertFalse(old.isCurrent(listOf(day.copy(avgHrv = 20.0)), "alpha"))
        alpha.emit(listOf(HrvProvenanceRow("alpha-noop", day.day, 40.0, 1.0, 0.0, 16.0, 1.0)))
        assertNull(seen.last())
        beta.emit(listOf(HrvProvenanceRow("beta-noop", day.day, 40.0, null, null, 16.0, null)))
        runCurrent()
        val current = seen.last()!!
        assertTrue(current.isCurrent(listOf(day), "beta"))
        assertFalse(current.hrvReliabilityByDay.getValue(day.day).matches(day.avgHrv))
        assertFalse(current.respReliabilityByDay.getValue(day.day).matches(day.respRateBpm))
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
        assertNull(IllnessWatch.evaluateWindow(baseline + recent).alert)
        val trusted = baseline.map { it.copy(avgHrv = 60.0) } + recent
        assertNotNull(IllnessWatch.evaluateWindow(trusted).alert)
        assertNull(IllnessWatch.evaluateWindow(trusted, 1769947200.0, 1769947200.0).alert)
    }

    @Test fun historicalRowsDoNotBridgeAMissingCalendarWindow() {
        val baseline = (1..31).map { day -> DailyMetric(deviceId = "test", day = "2026-01-%02d".format(day),
            restingHr = 50, avgHrv = 60.0) }
        val recent = listOf("2026-03-01", "2026-03-02").map { day ->
            DailyMetric(deviceId = "test", day = day, restingHr = 58, avgHrv = 20.0) }
        assertNull(IllnessWatch.evaluateWindow(baseline + recent).alert)
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

    @Test fun joinedSnapshotMatchesStandaloneSwiftOracle() {
        data class Fixture(
            val name: String,
            val sourceIds: List<String>,
            val computedIds: List<String>,
            val rows: List<HrvProvenanceRow>,
            val cachedDays: List<DailyMetric>,
        )
        fun number(value: Double?): String = when {
            value == null -> "-"
            value.isNaN() -> "nan"
            value == Double.POSITIVE_INFINITY -> "+inf"
            value == Double.NEGATIVE_INFINITY -> "-inf"
            else -> String.format(java.util.Locale.ROOT, "%.1f", value)
        }
        val cases = listOf(
            Fixture(
                name = "stale-computed",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop-noop", day = "2026-06-10", value = 40.0, freshScoringValid = 0.0, overcount = 1.0, respValue = 16.0, respFreshScoringValid = 0.0, metric = DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = 50, avgHrv = 40.0, recovery = 70.0, strain = null, exerciseCount = null, spo2Pct = 98.0, skinTempDevC = null, respRateBpm = 16.0, steps = 1234, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = 33.0, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                    DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = 50, avgHrv = 40.0, recovery = 70.0, strain = null, exerciseCount = null, spo2Pct = 98.0, skinTempDevC = null, respRateBpm = 16.0, steps = 1234, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = 33.0, sleepHrOnly = null, activeEnergyKcalEst = null),
                ),
            ),
            Fixture(
                name = "new-vendor-equal-hrv",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop-noop", day = "2026-06-10", value = 40.0, freshScoringValid = 0.0, overcount = 1.0, respValue = 16.0, respFreshScoringValid = 0.0, metric = DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = 50, avgHrv = 40.0, recovery = 70.0, strain = null, exerciseCount = null, spo2Pct = 98.0, skinTempDevC = null, respRateBpm = 16.0, steps = 1234, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = 33.0, sleepHrOnly = null, activeEnergyKcalEst = null)),
                    HrvProvenanceRow(deviceId = "my-whoop", day = "2026-06-10", value = 40.0, freshScoringValid = null, overcount = null, respValue = 20.0, respFreshScoringValid = null, metric = DailyMetric(deviceId = "my-whoop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = 58, avgHrv = 40.0, recovery = 60.0, strain = null, exerciseCount = null, spo2Pct = 93.0, skinTempDevC = null, respRateBpm = 20.0, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = 34.0, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                    DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = 50, avgHrv = 40.0, recovery = 70.0, strain = null, exerciseCount = null, spo2Pct = 98.0, skinTempDevC = null, respRateBpm = 16.0, steps = 1234, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = 33.0, sleepHrOnly = null, activeEnergyKcalEst = null),
                ),
            ),
            Fixture(
                name = "independent-physical-owners",
                sourceIds = listOf("active", "my-whoop", "active-noop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("active-noop", "my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop-noop", day = "2026-06-10", value = 40.0, freshScoringValid = 1.0, overcount = 0.0, respValue = 20.0, respFreshScoringValid = 0.0, metric = DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = 40.0, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = 20.0, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                    HrvProvenanceRow(deviceId = "active-noop", day = "2026-06-10", value = null, freshScoringValid = 0.0, overcount = 1.0, respValue = 16.0, respFreshScoringValid = 1.0, metric = DailyMetric(deviceId = "active-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = null, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = 16.0, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "invalid-first-hrv-owner",
                sourceIds = listOf("active", "my-whoop", "active-noop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("active-noop", "my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop-noop", day = "2026-06-10", value = 60.0, freshScoringValid = 1.0, overcount = 0.0, respValue = 20.0, respFreshScoringValid = 0.0, metric = DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = 60.0, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = 20.0, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                    HrvProvenanceRow(deviceId = "active-noop", day = "2026-06-10", value = 40.0, freshScoringValid = 0.0, overcount = 1.0, respValue = 16.0, respFreshScoringValid = 1.0, metric = DailyMetric(deviceId = "active-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = 40.0, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = 16.0, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "hollow-import-invalid-gapfill",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop-noop", day = "2026-06-10", value = 40.0, freshScoringValid = 0.0, overcount = 1.0, respValue = 20.0, respFreshScoringValid = 1.0, metric = DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = 58, avgHrv = 40.0, recovery = 60.0, strain = null, exerciseCount = null, spo2Pct = 93.0, skinTempDevC = null, respRateBpm = 20.0, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = 34.0, sleepHrOnly = null, activeEnergyKcalEst = null)),
                    HrvProvenanceRow(deviceId = "my-whoop", day = "2026-06-10", value = null, freshScoringValid = null, overcount = null, respValue = null, respFreshScoringValid = null, metric = DailyMetric(deviceId = "my-whoop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = null, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = null, steps = 4321, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "activity-only",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "activity-file", day = "2026-06-10", value = null, freshScoringValid = null, overcount = null, respValue = null, respFreshScoringValid = null, metric = DailyMetric(deviceId = "activity-file", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = null, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = null, steps = 12345, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "activity-gapfill",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop-noop", day = "2026-06-10", value = 40.0, freshScoringValid = 1.0, overcount = 0.0, respValue = 20.0, respFreshScoringValid = 1.0, metric = DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = 58, avgHrv = 40.0, recovery = 60.0, strain = null, exerciseCount = null, spo2Pct = 93.0, skinTempDevC = null, respRateBpm = 20.0, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = 34.0, sleepHrOnly = null, activeEnergyKcalEst = null)),
                    HrvProvenanceRow(deviceId = "activity-file", day = "2026-06-10", value = null, freshScoringValid = null, overcount = null, respValue = null, respFreshScoringValid = null, metric = DailyMetric(deviceId = "activity-file", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = null, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = null, steps = 12345, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "activity-keeps-measured-zero",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop-noop", day = "2026-06-10", value = null, freshScoringValid = null, overcount = null, respValue = null, respFreshScoringValid = null, metric = DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = null, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = null, steps = 0, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                    HrvProvenanceRow(deviceId = "activity-file", day = "2026-06-10", value = null, freshScoringValid = null, overcount = null, respValue = null, respFreshScoringValid = null, metric = DailyMetric(deviceId = "activity-file", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = null, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = null, steps = 12345, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "activity-ignores-zero",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "activity-file", day = "2026-06-10", value = null, freshScoringValid = null, overcount = null, respValue = null, respFreshScoringValid = null, metric = DailyMetric(deviceId = "activity-file", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = null, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = null, steps = 0, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "empty-joined",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                ),
                cachedDays = listOf(
                    DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = 50, avgHrv = 40.0, recovery = 70.0, strain = null, exerciseCount = null, spo2Pct = 98.0, skinTempDevC = null, respRateBpm = 16.0, steps = 1234, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = 33.0, sleepHrOnly = null, activeEnergyKcalEst = null),
                ),
            ),
            Fixture(
                name = "recovery-only",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop-noop", day = "2026-06-10", value = null, freshScoringValid = null, overcount = null, respValue = null, respFreshScoringValid = null, metric = DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = null, recovery = 60.0, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = null, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "sleep-only",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop-noop", day = "2026-06-10", value = null, freshScoringValid = null, overcount = null, respValue = null, respFreshScoringValid = null, metric = DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = 480.0, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = null, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = null, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "empty-physical-row",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop-noop", day = "2026-06-10", value = null, freshScoringValid = null, overcount = null, respValue = null, respFreshScoringValid = null, metric = DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = null, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = null, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "vendor-lower-boundaries",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop", day = "2026-06-10", value = 5.0, freshScoringValid = null, overcount = null, respValue = 4.0, respFreshScoringValid = null, metric = DailyMetric(deviceId = "my-whoop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = 5.0, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = 4.0, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "vendor-upper-boundaries",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop", day = "2026-06-10", value = 250.0, freshScoringValid = null, overcount = null, respValue = 40.0, respFreshScoringValid = null, metric = DailyMetric(deviceId = "my-whoop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = 250.0, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = 40.0, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "vendor-below-boundaries",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop", day = "2026-06-10", value = 4.999, freshScoringValid = null, overcount = null, respValue = 3.999, respFreshScoringValid = null, metric = DailyMetric(deviceId = "my-whoop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = 4.999, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = 3.999, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "vendor-above-boundaries",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop", day = "2026-06-10", value = 250.001, freshScoringValid = null, overcount = null, respValue = 40.001, respFreshScoringValid = null, metric = DailyMetric(deviceId = "my-whoop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = 250.001, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = 40.001, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "vendor-ignores-computed-markers",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop", day = "2026-06-10", value = 40.0, freshScoringValid = 0.0, overcount = Double.POSITIVE_INFINITY, respValue = 16.0, respFreshScoringValid = 0.0, metric = DailyMetric(deviceId = "my-whoop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = 40.0, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = 16.0, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "computed-valid",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop-noop", day = "2026-06-10", value = 40.0, freshScoringValid = 1.0, overcount = 0.0, respValue = 16.0, respFreshScoringValid = 1.0, metric = DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = 40.0, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = 16.0, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "computed-marker-zero",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop-noop", day = "2026-06-10", value = 40.0, freshScoringValid = 0.0, overcount = 0.0, respValue = 16.0, respFreshScoringValid = 0.0, metric = DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = 40.0, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = 16.0, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "computed-marker-threshold-pass",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop-noop", day = "2026-06-10", value = 40.0, freshScoringValid = 0.5, overcount = 0.499, respValue = 16.0, respFreshScoringValid = 0.5, metric = DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = 40.0, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = 16.0, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "computed-marker-threshold-reject",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop-noop", day = "2026-06-10", value = 40.0, freshScoringValid = 0.499, overcount = 0.499, respValue = 16.0, respFreshScoringValid = 0.499, metric = DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = 40.0, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = 16.0, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "computed-overcount-threshold",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop-noop", day = "2026-06-10", value = 40.0, freshScoringValid = 1.0, overcount = 0.5, respValue = 16.0, respFreshScoringValid = 1.0, metric = DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = 40.0, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = 16.0, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "computed-missing-markers",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop-noop", day = "2026-06-10", value = 40.0, freshScoringValid = null, overcount = null, respValue = 16.0, respFreshScoringValid = null, metric = DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = 40.0, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = 16.0, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "computed-no-overcount",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop-noop", day = "2026-06-10", value = 40.0, freshScoringValid = 1.0, overcount = null, respValue = 16.0, respFreshScoringValid = 1.0, metric = DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = 40.0, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = 16.0, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "computed-marker-nan",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop-noop", day = "2026-06-10", value = 40.0, freshScoringValid = Double.NaN, overcount = 0.0, respValue = 16.0, respFreshScoringValid = Double.NaN, metric = DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = 40.0, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = 16.0, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "computed-marker-positive-inf",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop-noop", day = "2026-06-10", value = 40.0, freshScoringValid = Double.POSITIVE_INFINITY, overcount = 0.0, respValue = 16.0, respFreshScoringValid = Double.POSITIVE_INFINITY, metric = DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = 40.0, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = 16.0, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "computed-overcount-nan",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop-noop", day = "2026-06-10", value = 40.0, freshScoringValid = 1.0, overcount = Double.NaN, respValue = 16.0, respFreshScoringValid = 1.0, metric = DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = 40.0, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = 16.0, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "computed-overcount-positive-inf",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop-noop", day = "2026-06-10", value = 40.0, freshScoringValid = 1.0, overcount = Double.POSITIVE_INFINITY, respValue = 16.0, respFreshScoringValid = 1.0, metric = DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = 40.0, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = 16.0, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "computed-values-nan",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop-noop", day = "2026-06-10", value = Double.NaN, freshScoringValid = 1.0, overcount = 0.0, respValue = Double.NaN, respFreshScoringValid = 1.0, metric = DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = Double.NaN, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = Double.NaN, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "computed-values-positive-inf",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop-noop", day = "2026-06-10", value = Double.POSITIVE_INFINITY, freshScoringValid = 1.0, overcount = 0.0, respValue = Double.POSITIVE_INFINITY, respFreshScoringValid = 1.0, metric = DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = Double.POSITIVE_INFINITY, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = Double.POSITIVE_INFINITY, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "computed-values-negative-inf",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop-noop", day = "2026-06-10", value = Double.NEGATIVE_INFINITY, freshScoringValid = 1.0, overcount = 0.0, respValue = Double.NEGATIVE_INFINITY, respFreshScoringValid = 1.0, metric = DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = Double.NEGATIVE_INFINITY, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = Double.NEGATIVE_INFINITY, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "computed-respiration-only",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop-noop", day = "2026-06-10", value = null, freshScoringValid = null, overcount = null, respValue = 16.0, respFreshScoringValid = 1.0, metric = DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = null, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = 16.0, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "computed-hrv-only",
                sourceIds = listOf("my-whoop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop-noop", day = "2026-06-10", value = 40.0, freshScoringValid = 1.0, overcount = 0.0, respValue = null, respFreshScoringValid = null, metric = DailyMetric(deviceId = "my-whoop-noop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = 40.0, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = null, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
            Fixture(
                name = "vendor-active-first",
                sourceIds = listOf("active", "my-whoop", "active-noop", "my-whoop-noop", "apple-health"),
                computedIds = listOf("active-noop", "my-whoop-noop"),
                rows = listOf(
                    HrvProvenanceRow(deviceId = "my-whoop", day = "2026-06-10", value = 60.0, freshScoringValid = null, overcount = null, respValue = 20.0, respFreshScoringValid = null, metric = DailyMetric(deviceId = "my-whoop", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = 60.0, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = 20.0, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                    HrvProvenanceRow(deviceId = "active", day = "2026-06-10", value = 40.0, freshScoringValid = null, overcount = null, respValue = 16.0, respFreshScoringValid = null, metric = DailyMetric(deviceId = "active", day = "2026-06-10", totalSleepMin = null, efficiency = null, deepMin = null, remMin = null, lightMin = null, disturbances = null, restingHr = null, avgHrv = 40.0, recovery = null, strain = null, exerciseCount = null, spo2Pct = null, skinTempDevC = null, respRateBpm = 16.0, steps = null, activeKcalEst = null, spo2Red = null, spo2Ir = null, avgSdnn = null, skinTempC = null, sleepHrOnly = null, activeEnergyKcalEst = null)),
                ),
                cachedDays = listOf(
                ),
            ),
        )
        val actual = cases.joinToString(separator = "\n", postfix = "\n") { fixture ->
            val snapshot = IllnessHistory.resolve(fixture.cachedDays, fixture.rows, fixture.sourceIds, fixture.computedIds)
            assertTrue(fixture.name, snapshot.vitalDays.size <= 1)
            val metric = snapshot.vitalDays.firstOrNull()
            val hrvEligible = metric?.let { snapshot.hrvReliabilityByDay[it.day]?.matches(it.avgHrv) } == true
            val respEligible = metric?.let { snapshot.respReliabilityByDay[it.day]?.matches(it.respRateBpm) } == true
            listOf(fixture.name, metric?.day ?: "-", number(metric?.avgHrv), number(metric?.restingHr?.toDouble()),
                number(metric?.respRateBpm), number(metric?.spo2Pct), number(metric?.skinTempC ?: metric?.skinTempDevC),
                number(metric?.recovery), hrvEligible.toString(), respEligible.toString(), metric?.steps?.toString() ?: "-")
                .joinToString("|")
        }
        val expected = """
stale-computed|2026-06-10|40.0|50.0|16.0|98.0|33.0|70.0|false|false|1234
new-vendor-equal-hrv|2026-06-10|40.0|58.0|20.0|93.0|34.0|60.0|true|true|1234
independent-physical-owners|2026-06-10|40.0|-|16.0|-|-|-|true|true|-
invalid-first-hrv-owner|2026-06-10|40.0|-|16.0|-|-|-|false|true|-
hollow-import-invalid-gapfill|2026-06-10|40.0|58.0|20.0|93.0|34.0|60.0|false|true|4321
activity-only|2026-06-10|-|-|-|-|-|-|false|false|12345
activity-gapfill|2026-06-10|40.0|58.0|20.0|93.0|34.0|60.0|true|true|12345
activity-keeps-measured-zero|2026-06-10|-|-|-|-|-|-|false|false|0
activity-ignores-zero|-|-|-|-|-|-|-|false|false|-
empty-joined|-|-|-|-|-|-|-|false|false|-
recovery-only|2026-06-10|-|-|-|-|-|60.0|false|false|-
sleep-only|2026-06-10|-|-|-|-|-|-|false|false|-
empty-physical-row|2026-06-10|-|-|-|-|-|-|false|false|-
vendor-lower-boundaries|2026-06-10|5.0|-|4.0|-|-|-|true|true|-
vendor-upper-boundaries|2026-06-10|250.0|-|40.0|-|-|-|true|true|-
vendor-below-boundaries|2026-06-10|5.0|-|4.0|-|-|-|false|false|-
vendor-above-boundaries|2026-06-10|250.0|-|40.0|-|-|-|false|false|-
vendor-ignores-computed-markers|2026-06-10|40.0|-|16.0|-|-|-|true|true|-
computed-valid|2026-06-10|40.0|-|16.0|-|-|-|true|true|-
computed-marker-zero|2026-06-10|40.0|-|16.0|-|-|-|false|false|-
computed-marker-threshold-pass|2026-06-10|40.0|-|16.0|-|-|-|true|true|-
computed-marker-threshold-reject|2026-06-10|40.0|-|16.0|-|-|-|false|false|-
computed-overcount-threshold|2026-06-10|40.0|-|16.0|-|-|-|false|true|-
computed-missing-markers|2026-06-10|40.0|-|16.0|-|-|-|false|false|-
computed-no-overcount|2026-06-10|40.0|-|16.0|-|-|-|true|true|-
computed-marker-nan|2026-06-10|40.0|-|16.0|-|-|-|false|false|-
computed-marker-positive-inf|2026-06-10|40.0|-|16.0|-|-|-|false|false|-
computed-overcount-nan|2026-06-10|40.0|-|16.0|-|-|-|false|true|-
computed-overcount-positive-inf|2026-06-10|40.0|-|16.0|-|-|-|false|true|-
computed-values-nan|2026-06-10|nan|-|nan|-|-|-|false|false|-
computed-values-positive-inf|2026-06-10|+inf|-|+inf|-|-|-|false|false|-
computed-values-negative-inf|2026-06-10|-inf|-|-inf|-|-|-|false|false|-
computed-respiration-only|2026-06-10|-|-|16.0|-|-|-|false|true|-
computed-hrv-only|2026-06-10|40.0|-|-|-|-|-|true|false|-
vendor-active-first|2026-06-10|40.0|-|16.0|-|-|-|true|true|-
""".trimIndent() + "\n"
        assertEquals(expected, actual)
    }

}
