package com.noop.ui

import com.noop.data.DailyMetric
import com.noop.data.SleepSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class SleepSelectedDetailTest {
    @Test fun requestedDaySelectsTheExactLocalWakeDayWithoutCarryingANearbyNight() {
        val defaultZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT-03:00"))
            val newestWake = Instant.parse("2026-10-03T10:00:00Z").epochSecond
            val earlierWake = Instant.parse("2026-10-02T01:00:00Z").epochSecond
            val navDays = listOf(newestWake, earlierWake).map { wake ->
                listOf(SleepSession(deviceId = "test", startTs = wake - 6 * 3600, endTs = wake))
            }
            assertEquals(1, requestedSleepNightOffset(navDays, "2026-10-01"))
            assertNull(requestedSleepNightOffset(navDays, "2026-10-02"))
            assertNull(requestedSleepNightOffset(navDays, "2026-10-04"))
            val addedWake = Instant.parse("2026-10-04T10:00:00Z").epochSecond
            val refreshedDays = listOf(listOf(SleepSession(deviceId = "test",
                startTs = addedWake - 6 * 3600, endTs = addedWake))) + navDays
            assertEquals(2, requestedSleepNightOffset(refreshedDays, "2026-10-01"))
            assertNull(requestedSleepNightOffset(refreshedDays.dropLast(1), "2026-10-01"))
        } finally {
            TimeZone.setDefault(defaultZone)
        }
    }

    @Test fun consistencyUsesTheMainNightAndNotAnAfternoonNap() {
        val zone = java.time.ZoneId.systemDefault()
        fun timestamp(value: String) = java.time.LocalDateTime.parse(value).atZone(zone).toEpochSecond()
        val sessions = (1..3).flatMap { day ->
            val date = "2026-09-%02d".format(day)
            listOf(SleepSession(deviceId = "test", startTs = timestamp("${date}T00:00"), endTs = timestamp("${date}T07:00")),
                SleepSession(deviceId = "test", startTs = timestamp("${date}T14:00"), endTs = timestamp("${date}T14:30")))
        }
        val detail = selectedSleepDetailModel((1..3).map { day("2026-09-%02d".format(it), 14.2) },
            night("2026-09-03"), ImportedSleepSeries(), emptyMap(), sessions, true)!!
        assertEquals(100.0, detail.consistency.selectedValue()!!, 1e-9)
        assertEquals(14.2, detail.respiratory.selectedValue()!!, 1e-9)
        assertEquals(420.0 / 450.0 * 100.0, detail.hoursVsNeeded.selectedValue()!!, 1e-9)
    }

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
