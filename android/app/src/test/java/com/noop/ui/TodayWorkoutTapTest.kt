package com.noop.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        assertTrue(todayScreen().contains("HomeDayEvents(displayMetric, homeDayWorkouts, onOpenSleep) { selectedWorkoutRow = it }"))
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
    fun bothPlatformsWindowMyDayToTheSelectedCalendarDay() {
        val android = todayScreen()
        assertTrue(android.contains("val date = LocalDate.parse(effectDayKey)"))
        assertTrue(android.contains("val start = date.atStartOfDay(zone).toEpochSecond()"))
        assertTrue(android.contains("val end = date.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1"))
        assertTrue(android.contains("viewModel.repo.workoutsAllSources(effectStrapId, start, end)"))
        assertTrue(android.contains("HomeDayEvents(displayMetric, homeDayWorkouts, onOpenSleep)"))
        val dashboard = source("Strand/Screens/HomeDashboardContent.swift")
        assertTrue(dashboard.contains("HomeDayActivities.rows(workouts, dayKey: dayKey)"))
        for (path in listOf("Strand/Screens/TodayView.swift", "Strand/Liquid/LiquidTodayView.swift")) {
            assertTrue(source(path).contains("dayKey: selectedDayKey"))
        }
        assertTrue(source("Strand/Screens/TodayView.swift").contains("workouts.filter { WorkoutSource.isAppleHealth"))
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
