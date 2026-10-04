package com.noop.ui

import com.noop.data.DailyMetric
import com.noop.data.SleepSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class SleepSelectedDetailTest {
    @Test fun historicalSelectionSurvivesNapEditAndNewNightWithoutChangingMainBounds() {
        val zone = java.time.ZoneId.systemDefault()
        fun session(date: String, onset: String, wake: String) = SleepSession(deviceId = "test",
            startTs = java.time.LocalDateTime.parse("${date}T$onset").atZone(zone).toEpochSecond(),
            endTs = java.time.LocalDateTime.parse("${date}T$wake").atZone(zone).toEpochSecond(),
            stagesJSON = """{"awake":10,"light":300,"deep":60,"rem":40}""")
        val main = session("2026-10-02", "00:00", "07:00")
        val nap = session("2026-10-02", "14:15", "14:45").copy(
            stagesJSON = """{"awake":0,"light":30,"deep":0,"rem":0}""")
        val newest = session("2026-10-03", "00:00", "07:00")
        val original = listOf(listOf(newest), listOf(main, nap))
        val selectedDay = selectedSleepDayKey(original, 1)
        assertEquals("2026-10-02", selectedDay)
        val refreshed = listOf(listOf(session("2026-10-04", "00:00", "07:00"))) +
            listOf(listOf(newest), listOf(main, nap.copy(endTs = nap.endTs - 60)))
        val selectedOffset = requestedSleepNightOffset(refreshed, selectedDay)!!
        assertEquals(2, selectedOffset)
        val selected = selectNight(refreshed, emptyList(), selectedOffset)!!
        assertEquals(main.effectiveStartTs, selected.heroOnsetTs)
        assertEquals(main.endTs, selected.heroWakeTs)
        assertEquals(nap.endTs - 60, selected.napBlocks.single().endTs)
        assertNull(requestedSleepNightOffset(refreshed.take(2), selectedDay))
        assertNull(selectedSleepDayKey(refreshed, 0))
    }

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

    @Test fun recordedFragmentStagesOwnAllNightDurationsWithoutChangingTheExistingNeed() {
        val zone = java.time.ZoneId.systemDefault()
        fun session(start: String, end: String, stages: String?) = SleepSession(deviceId = "test",
            startTs = java.time.LocalDateTime.parse(start).atZone(zone).toEpochSecond(),
            endTs = java.time.LocalDateTime.parse(end).atZone(zone).toEpochSecond(), stagesJSON = stages)
        val first = session("2026-10-02T00:00", "2026-10-02T02:00",
            """{"awake":10,"light":60,"deep":20,"rem":10}""")
        val second = session("2026-10-02T02:20", "2026-10-02T05:20",
            """{"awake":5,"light":90,"deep":30,"rem":30}""")
        val nap = session("2026-10-02T14:15", "2026-10-02T15:00",
            """{"awake":5,"light":25,"deep":0,"rem":10}""")
        val daily = day("2026-10-02")
        val selected = selectNight(listOf(listOf(first, second, nap)), listOf(daily), 0)!!
        val stages = selectedNightStages(selected)!!
        val detail = selectedSleepDetailModel(listOf(daily), selected, ImportedSleepSeries(),
            emptyMap(), listOf(first, second, nap), true)!!
        val display = heroDisplay(detail, selected, stages)!!
        val h9Display = heroDisplay(detail, selected, recordedStages = null)!!
        val amounts = selectedSleepAmounts(stages, daily, detail.hoursVsNeeded.selectedValue(), null)
        assertEquals(detail.stages, h9Display.stages)
        assertEquals(420.0 / 0.9 - 420.0, h9Display.stages.awake, 1e-9)
        assertEquals(15.0, display.stages.awake, 0.0)
        assertEquals(255.0, display.stages.total, 0.0)
        assertEquals(240.0, display.stages.asleep, 0.0)
        assertEquals(display.stages.asleep, amounts.asleepMin!!, 0.0)
        assertEquals(240.0 / 255.0 * 100.0, selectedSleepEfficiency(selected, listOf(daily), stages)!!, 1e-9)
        assertEquals(450.0, amounts.needMin!!, 1e-9)
        assertEquals(240.0 / 450.0 * 100.0, amounts.sufficiencyPct!!, 1e-9)
        val imported = selectedSleepAmounts(stages, daily, detail.hoursVsNeeded.selectedValue(), 480.0)
        assertEquals(480.0, imported.needMin!!, 0.0)
        assertEquals(50.0, imported.sufficiencyPct!!, 0.0)
        assertEquals(35.0, napAsleepMinutes(selected.napBlocks)!!, 0.0)
        assertEquals(45.0, (nap.endTs - nap.effectiveStartTs) / 60.0, 0.0)
        assertNull(napAsleepMinutes(listOf(nap, nap.copy(stagesJSON = null))))
    }

    @Test fun missingStagesUseExactDailyFallbackAndImportedNeedSupportsRecordedSleepWithoutDailyRow() {
        val daily = day("2026-10-02")
        val selected = night(daily.day).copy(session = night(daily.day).session.copy(efficiency = 0.88))
        val detail = selectedSleepDetailModel(listOf(daily), selected, ImportedSleepSeries(),
            emptyMap(), emptyList(), true)!!
        assertNull(selectedNightStages(selected))
        assertEquals(detail.stages, heroDisplay(detail, selected)!!.stages)
        val amounts = selectedSleepAmounts(null, daily, detail.hoursVsNeeded.selectedValue(), null)
        assertEquals(420.0, amounts.asleepMin!!, 0.0)
        assertEquals(450.0, amounts.needMin!!, 1e-9)
        assertEquals(88.0, selectedSleepEfficiency(selected, listOf(daily))!!, 0.0)
        val recorded = Stages(10.0, 300.0, 50.0, 40.0)
        val withoutDay = selectedSleepAmounts(recorded, null, null, 450.0)
        assertEquals(390.0, withoutDay.asleepMin!!, 0.0)
        assertEquals(450.0, withoutDay.needMin!!, 0.0)
        assertEquals(390.0 / 450.0 * 100.0, withoutDay.sufficiencyPct!!, 0.0)
        assertNull(napAsleepMinutes(listOf(selected.session)))
        val awakeNight = selected.copy(session = selected.session.copy(
            stagesJSON = """{"awake":60,"light":0,"deep":0,"rem":0}"""))
        val awakeStages = selectedNightStages(awakeNight)!!
        assertEquals(60.0, awakeStages.total, 0.0)
        val awakeAmounts = selectedSleepAmounts(awakeStages, daily, detail.hoursVsNeeded.selectedValue(), null)
        assertEquals(0.0, awakeAmounts.asleepMin!!, 0.0)
        assertEquals(0.0, awakeAmounts.sufficiencyPct!!, 0.0)
        assertEquals(0.0, heroDisplay(detail, awakeNight)!!.stages.asleep, 0.0)
        assertEquals(0.0, selectedSleepEfficiency(awakeNight, listOf(daily))!!, 0.0)
    }
}
