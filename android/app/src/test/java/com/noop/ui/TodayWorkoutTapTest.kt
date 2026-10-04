package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.noop.data.DailyMetric

/** Home activity actions stay in the shared detail flow and survive lazy-item disposal. */
class TodayWorkoutTapTest {
    private fun repoRoot(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        return listOf(userDir, File(userDir, ".."), File(userDir, "../..")).firstOrNull {
            File(it, "Strand/Screens/TodayView.swift").isFile
        } ?: error("could not locate the repo root from ${userDir.absolutePath}")
    }

    private fun source(path: String) = File(repoRoot(), path).readText()
    private fun todayScreen() = source("android/app/src/main/java/com/noop/ui/TodayScreen.kt")

    private fun homeEventsBody(): String {
        val text = source("android/app/src/main/java/com/noop/ui/HomePresentation.kt")
        val start = text.indexOf("internal fun HomeDayEvents(")
        assertTrue("the live Home activity component must exist", start >= 0)
        val end = text.indexOf("\n@Composable", start)
        return text.substring(start, if (end > start) end else text.length)
            .lines().joinToString("\n") { it.substringBefore("//") }
    }

    @Test
    fun tilesAreClickableAndReportTheTappedRow() {
        val body = homeEventsBody()
        assertTrue(body.contains(".clickable("))
        assertTrue(body.contains("onWorkout(workout)"))
        assertTrue(body.contains("onClickLabel = uiString(R.string.today_action_show_workout)"))
        assertTrue(body.contains("role = Role.Button"))
        assertTrue(todayScreen().contains("HomeDayEvents(displayMetric, homeDayWorkouts, openSleepForDisplayedDay) { selectedWorkoutRow = it }"))
    }

    @Test
    fun theSheetIsHostedAtScreenLevelNotInsideTheLazySection() {
        assertTrue(todayScreen().contains("WorkoutDetailSheet(vm = viewModel, row = row"))
        assertFalse(homeEventsBody().contains("WorkoutDetailSheet("))
    }

    @Test
    fun bothAppleHomeVariantsOpenTheSharedActivityDetail() {
        val dashboard = source("Strand/Screens/HomeDashboardContent.swift")
        assertTrue(dashboard.contains("Button { onWorkout(workout) }"))
        for ((path, callback) in listOf(
            "Strand/Screens/TodayView.swift" to "onWorkout: { workoutDetail = WorkoutDetailTarget(row: \$0) }",
            "Strand/Liquid/LiquidTodayView.swift" to "onWorkout: { homeWorkout = HomeWorkoutTarget(row: \$0) }",
        )) {
            val text = source(path)
            assertTrue("$path must connect the live shared dashboard", text.contains("HomeDashboardContent("))
            assertTrue("$path must carry the tapped row", text.contains(callback))
            assertTrue("$path must host the shared detail", text.contains("WorkoutDetailView(row: target.row)"))
        }
    }

    @Test
    fun preFourBankedNightKeepsPostMidnightActivityAndDisplayedManualDate() {
        val zone = ZoneId.of("America/Sao_Paulo")
        val now = ZonedDateTime.of(2026, 10, 3, 2, 0, 0, 0, zone)
        val days = listOf("2026-10-02", "2026-10-03").map {
            DailyMetric(deviceId = "my-whoop", day = it, totalSleepMin = 430.0)
        }
        val logicalKey = logicalDay(now).toString()
        val displayedKey = resolveTodayRow(days, logicalKey, now.toLocalDate().toString())!!.day
        assertEquals("2026-10-02", logicalKey)
        assertEquals("2026-10-03", displayedKey)
        val midnightWorkout = now.minusMinutes(30).toEpochSecond()
        val previousWorkout = now.minusHours(3).toEpochSecond()
        assertEquals(listOf(midnightWorkout), listOf(previousWorkout, midnightWorkout)
            .filter { it in homeActivityWindow(displayedKey, zone) })
        assertEquals(now.toInstant().toEpochMilli(), homeManualActivityEnd(displayedKey, now))

        val android = todayScreen()
        assertTrue(android.contains("manualActivityEndMillis = homeManualActivityEnd(selectedDayKey)"))
        assertTrue(android.contains("val activityWindow = homeActivityWindow(effectDayKey)"))
        assertTrue(android.contains("viewModel.repo.workoutsAllSources(effectStrapId, activityWindow.first, activityWindow.last)"))
        val dashboard = source("Strand/Screens/HomeDashboardContent.swift")
        assertTrue(dashboard.contains("HomeDayActivities.rows(workouts, dayKey: dayKey)"))
        assertTrue(dashboard.contains("HomeDayActivities.manualEnd(dayKey: dayKey)"))
        assertFalse(dashboard.contains("windowDayKey ?? dayKey"))
        for (path in listOf("Strand/Screens/TodayView.swift", "Strand/Liquid/LiquidTodayView.swift")) {
            val text = source(path)
            assertTrue(text.contains("HomeDashboardContent(dayKey: selectedDayKey"))
            assertTrue(text.contains("windowDayKey: Repository.localDayKey(selectedLogicalDay)"))
        }
    }

    @Test
    fun manualEntryMatchesSwiftISODayOracleAcrossPreferredCalendars() {
        val zone = ZoneId.of("America/Sao_Paulo")
        val now = Instant.ofEpochMilli(1791003600000).atZone(zone)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
        val previousLocale = Locale.getDefault()
        val actual = try {
            listOf("gregorian" to "en-US-u-ca-gregory", "buddhist" to "th-TH-u-ca-buddhist",
                "islamic" to "ar-SA-u-ca-islamic").flatMap { (calendar, locale) ->
                Locale.setDefault(Locale.forLanguageTag(locale))
                listOf("2026-10-03", "2026-10-02").map { dayKey ->
                    val end = homeManualActivityEnd(dayKey, now)
                    "$calendar|$dayKey|${formatter.format(Instant.ofEpochMilli(end).atZone(zone))}|$end"
                }
            }.joinToString("\n")
        } finally {
            Locale.setDefault(previousLocale)
        }
        // Verbatim stdout of the extracted Swift HomeDayActivities.manualEnd oracle.
        assertEquals("""
            gregorian|2026-10-03|2026-10-03 02:00:00|1791003600000
            gregorian|2026-10-02|2026-10-02 02:00:00|1790917200000
            buddhist|2026-10-03|2026-10-03 02:00:00|1791003600000
            buddhist|2026-10-02|2026-10-02 02:00:00|1790917200000
            islamic|2026-10-03|2026-10-03 02:00:00|1791003600000
            islamic|2026-10-02|2026-10-02 02:00:00|1790917200000
        """.trimIndent(), actual)
    }

    @Test
    fun displayedMarkerDayAndItsSuccessorFeedTheSharedWindowResolver() {
        for (path in listOf("Strand/Screens/TodayView.swift", "Strand/Liquid/LiquidTodayView.swift")) {
            val text = source(path)
            assertTrue("$path must anchor onset markers to the displayed row", text.contains("RecoveryStrainDetailLogic.date(loadDayKey)"))
            assertTrue("$path must advance that marker date", text.contains("value: 1, to: markerDay"))
            assertTrue("$path must use the tested paired interval", text.contains("RecoveryStrainDetailLogic.strainWindow("))
            assertFalse("$path must not reuse the displayed marker as its end", text.contains("let nextDayKey = Repository.localDayKey(nextDay)"))
            assertFalse("$path must not resolve a different live onset", text.contains("let effortStart ="))
        }
        val android = todayScreen()
        assertTrue(android.contains("val markerDay = LocalDate.parse(selectedDayKey)"))
        assertTrue(android.contains("val nextMarkerDay = markerDay.plusDays(1)"))
        assertTrue(android.contains("viewModel.repo.hrSamplesUnion(strapDeviceId, window.first, window.last)"))
        assertTrue(android.contains("liveSnap.lastSyncAt, liveSnap.syncChunksThisSession"))
        assertFalse(android.contains("val start = activeDayCycleStart("))
    }
}
