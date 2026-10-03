package com.noop.notif

import org.junit.Assert.*
import org.junit.Test

class LocalNotificationContextTest {
    private val contexts = listOf(
        LocalNotificationContext("local_briefing", "night:2026-10-02", "dailyOutlook", "2026-10-02", report =
            LocalRecordedReport("2026-10-02", 82, 451, null, 7)),
        LocalNotificationContext("local_briefing", "evening:2026-10-02", "dayInReview", "2026-10-02", report =
            LocalRecordedReport("2026-10-02", null, null, 164, 0, 510, 39)),
        LocalNotificationContext("weekly_plan", "weeklyRecap:2026-09-21", "weeklyRecap", "2026-09-28",
            weekKey = "2026-09-21", message = "Saved plan recap"),
        LocalNotificationContext("workouts", "workoutReady:1790967600", day = "2026-10-02",
            workoutStartSec = 1790967600, message = "Saved activity"),
        LocalNotificationContext("devices", "devices"),
    )

    @Test fun matchesActualCompiledSwiftWireOracleAndRoundTrips() {
        val output = contexts.joinToString("") { context ->
            context.identity + "\n" + context.wireFields.toSortedMap().entries.joinToString("\n") {
                it.key + "=" + it.value
            } + "\n--\n"
        }
        // Expected stdout is copied verbatim from swiftc -O LocalNotificationContext.swift main.swift.
        assertEquals("local_briefing:night:2026-10-02\nlocalNotificationDay=2026-10-02\nlocalNotificationEvent=night:2026-10-02\nlocalNotificationFamily=dailyOutlook\nlocalNotificationRecovery=82\nlocalNotificationReport=1\nlocalNotificationRoute=local_briefing\nlocalNotificationSleep=451\nlocalNotificationStreak=7\n--\nlocal_briefing:evening:2026-10-02\nlocalNotificationDay=2026-10-02\nlocalNotificationEvent=evening:2026-10-02\nlocalNotificationFamily=dayInReview\nlocalNotificationReport=1\nlocalNotificationRoute=local_briefing\nlocalNotificationSleepDebt=39\nlocalNotificationSleepNeed=510\nlocalNotificationStrain=164\nlocalNotificationStreak=0\n--\nweekly_plan:weeklyRecap:2026-09-21\nlocalNotificationDay=2026-09-28\nlocalNotificationEvent=weeklyRecap:2026-09-21\nlocalNotificationFamily=weeklyRecap\nlocalNotificationMessage=Saved plan recap\nlocalNotificationRoute=weekly_plan\nlocalNotificationWeek=2026-09-21\n--\nworkouts:workoutReady:1790967600\nlocalNotificationDay=2026-10-02\nlocalNotificationEvent=workoutReady:1790967600\nlocalNotificationMessage=Saved activity\nlocalNotificationRoute=workouts\nlocalNotificationWorkoutStart=1790967600\n--\ndevices:devices\nlocalNotificationEvent=devices\nlocalNotificationRoute=devices\n--\n", output)
        contexts.forEach { assertEquals(it, LocalNotificationContext.fromWireFields(it.wireFields)) }
    }

    @Test fun coldStartDecodeAndNewDataDoNotRetargetAnOlderNight() {
        val captured = LocalNotificationContext.fromWireFields(contexts[0].wireFields)!!
        val latest = LocalRecordedReport("2026-10-03", 22, 300, 99, 8)
        val displayed = LocalRecordedReport.forDisplay(captured, latest)
        assertEquals(contexts[0].report, displayed)
        assertNull(displayed?.strainTenths)
        assertEquals(latest, LocalRecordedReport.forDisplay(null, latest))
        assertNull(LocalRecordedReport.forDisplay(contexts[4], latest))
    }

    @Test fun recapAfterWeekChangeKeepsWeekAndOriginalCopy() {
        val captured = LocalNotificationContext.fromWireFields(contexts[2].wireFields)!!
        val next = LocalNotificationContext("weekly_plan", "weeklyRecap:2026-09-28", weekKey = "2026-09-28", message = "New recap")
        assertEquals("2026-09-21", captured.weekKey)
        assertEquals("Saved plan recap", captured.message)
        assertNotEquals(captured.identity, next.identity)
    }

    @Test fun retainedDaysHaveDistinctIntentDataInputsAndWorkoutIdentity() {
        val next = LocalNotificationContext("local_briefing", "night:2026-10-03")
        assertNotEquals(contexts[0].identity, next.identity)
        assertNotEquals(contexts[0].eventID, next.eventID)
        assertEquals(1790967600L, LocalNotificationContext.fromWireFields(contexts[3].wireFields)?.workoutStartSec)
        assertNull(LocalNotificationContext.fromWireFields(emptyMap()))
    }
    @Test fun launcherBindsPendingIntentDataToCompleteDatedContext() {
        val root = java.io.File(System.getProperty("user.dir") ?: ".")
        val source = listOf("src/main/java", "app/src/main/java", "android/app/src/main/java")
            .map { java.io.File(root, "$it/com/noop/notif/LocalNotificationDispatcher.kt") }
            .first { it.isFile }.readText().replace(Regex("\\s+"), " ")
        assertTrue(source.contains("notification.wireFields.forEach { (key, value) -> putExtra(key, value) }"))
        assertTrue(source.contains(".appendPath(notification.route).appendQueryParameter(\"event\", notification.eventID)"))
        assertTrue(source.contains("manager.notify(payload.identity,"))
    }

}
