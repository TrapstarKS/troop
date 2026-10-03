package com.noop.ui

import com.noop.data.DailyMetric
import com.noop.data.SleepSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepSelectedDetailTest {
    private fun day(date: String, respiratory: Double? = null) = DailyMetric(
        deviceId = "test", day = date, totalSleepMin = 420.0, efficiency = 90.0,
        deepMin = 60.0, remMin = 90.0, lightMin = 270.0, respRateBpm = respiratory,
    )

    private fun night(date: String) = HeroNight(
        SleepSession(deviceId = "test", startTs = 100L, endTs = 1000L), date, null, "",
    )

    @Test fun selectedNightExcludesLaterMetricsAndCarriedEarlierRespiration() {
        val days = listOf(day("2026-10-01", 14.0), day("2026-10-02"), day("2026-10-03", 18.0))
        val detail = selectedSleepDetailModel(days, night("2026-10-02"),
            ImportedSleepSeries(performance = mapOf("2026-10-02" to 75.0, "2026-10-03" to 95.0)),
            emptyMap(), emptyList(), true)!!
        assertEquals(75.0, detail.performance.selectedValue()!!, 0.0)
        assertNull(detail.respiratory.selectedValue())
        assertEquals(listOf("2026-10-01", "2026-10-02"), detail.trendDates)
        assertNull(selectedSleepDetailModel(days, night("2026-10-04"), ImportedSleepSeries(),
            emptyMap(), emptyList(), true))
    }

    @Test fun napEditKeepsTheMainGroupAndOtherDeviceNamespacesOutOfItsPlan() {
        val main = SleepSession(deviceId = "main", startTs = 100L, endTs = 300L)
        val fragment = SleepSession(deviceId = "main", startTs = 400L, endTs = 600L)
        val nap = SleepSession(deviceId = "main", startTs = 800L, endTs = 1000L)
        val otherDevice = main.copy(deviceId = "other")
        val group = listOf(main, fragment)
        assertEquals(group, sleepEditGroupFor(fragment, group))
        assertEquals(listOf(nap), sleepEditGroupFor(nap, group))
        assertEquals(listOf(otherDevice), sleepEditGroupFor(otherDevice, group))
    }

    @Test fun debtRequiresAnExactDayTotalOrAnImportedDebtForThatDay() {
        val days = listOf(day("2026-10-01"), day("2026-10-02").copy(totalSleepMin = null))
        val selected = night("2026-10-02")
        val withoutDebt = selectedSleepDetailModel(days, selected, ImportedSleepSeries(),
            emptyMap(), emptyList(), true)!!
        assertNull(withoutDebt.sleepDebt.selectedValue())
        val withDebt = selectedSleepDetailModel(days, selected,
            ImportedSleepSeries(debtMin = mapOf("2026-10-02" to 80.0)),
            emptyMap(), emptyList(), true)!!
        assertEquals(80.0, withDebt.sleepDebt.selectedValue()!!, 0.0)
    }
}
