package com.noop.ui

import com.noop.alarm.SleepPlannerSettings
import com.noop.alarm.SmartAlarmScheduler
import com.noop.alarm.WindDownScheduler
import com.noop.analytics.PlannerAlarmPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SleepPlannerAlarmTest {
    private fun calendar(zone: String = "UTC", day: Int = 17, hour: Int = 6): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
            clear()
            set(2026, Calendar.JUNE, day, hour, 0, 0)
        }

    @Test fun skippingTheOnlyWeeklyAlarmStillFindsTheFollowingWeek() {
        val now = calendar()
        val key = PlannerAlarmPolicy.occurrenceKey(2026, 6, 17, 420)
        val next = nextSmartAlarmEpochSec(
            420, setOf(Calendar.WEDNESDAY), nowMs = now.timeInMillis,
            calendarFactory = { calendar() }, skippedOccurrence = key,
        )
        assertEquals(calendar(day = 24, hour = 7).timeInMillis / 1000, next)
        val phone = SmartAlarmScheduler.nextDeadline(
            now, setOf(Calendar.WEDNESDAY), 30, skippedOccurrence = key,
        ) { 420 }
        assertEquals(24, phone!!.get(Calendar.DAY_OF_MONTH))
        assertEquals(30, phone.get(Calendar.MINUTE))
    }

    @Test fun aSkipDoesNotCancelAnotherAlarmAtAnotherTime() {
        val now = calendar()
        val next = SmartAlarmScheduler.nextDeadline(
            now, setOf(Calendar.WEDNESDAY), 30,
            skippedOccurrence = PlannerAlarmPolicy.occurrenceKey(2026, 6, 17, 420),
        ) { 480 }
        assertEquals(17, next!!.get(Calendar.DAY_OF_MONTH))
        assertEquals(8, next.get(Calendar.HOUR_OF_DAY))
    }

    @Test fun skippedLocalDateSurvivesATimezoneChange() {
        val key = PlannerAlarmPolicy.occurrenceKey(2026, 6, 17, 420)
        for (zone in listOf("UTC", "America/Sao_Paulo", "Asia/Tokyo")) {
            val now = calendar(zone)
            val next = nextSmartAlarmEpochSec(
                420, setOf(Calendar.WEDNESDAY), nowMs = now.timeInMillis,
                calendarFactory = { calendar(zone) }, skippedOccurrence = key,
            )
            val resolved = calendar(zone).apply { timeInMillis = next!! * 1000 }
            assertEquals(24, resolved.get(Calendar.DAY_OF_MONTH))
            assertEquals(7, resolved.get(Calendar.HOUR_OF_DAY))
        }
    }

    @Test fun nonGregorianCalendarsUseTheSameCanonicalSkipAcrossAllSchedulers() {
        val now = Calendar.getInstance(
            TimeZone.getTimeZone("UTC"), java.util.Locale.forLanguageTag("th-TH-u-ca-buddhist"),
        ).apply { timeInMillis = java.time.Instant.parse("2026-06-17T06:00:00Z").toEpochMilli() }
        assertEquals(2569, now.get(Calendar.YEAR))
        val skipped = "2026-06-17|420"
        assertTrue(PlannerAlarmPolicy.isSkipPending(skipped, now))
        val strap = nextSmartAlarmEpochSec(
            420, setOf(Calendar.WEDNESDAY), nowMs = now.timeInMillis,
            calendarFactory = { now.clone() as Calendar }, skippedOccurrence = skipped,
        )
        assertEquals(java.time.Instant.parse("2026-06-24T07:00:00Z").epochSecond, strap)
        val phone = SmartAlarmScheduler.nextDeadline(
            now, setOf(Calendar.WEDNESDAY), 30, skippedOccurrence = skipped,
        ) { 420 }
        assertEquals(java.time.Instant.parse("2026-06-24T07:30:00Z").toEpochMilli(), phone!!.timeInMillis)
        val reminderNow = (now.clone() as Calendar).apply {
            timeInMillis = java.time.Instant.parse("2026-06-16T12:00:00Z").toEpochMilli()
        }
        val reminder = WindDownScheduler.nextPlannerReminder(
            reminderNow, SleepPlannerSettings(skippedOccurrence = skipped),
            setOf(Calendar.WEDNESDAY), 420, emptyMap(), 30,
        )!!
        assertEquals("2026-06-24|420", reminder.occurrenceKey)
        assertEquals(java.time.Instant.parse("2026-06-23T22:30:00Z").toEpochMilli(), reminder.at.timeInMillis)
    }

    @Test fun dstGapKeysUseResolvedClockMinutes() {
        val now = Calendar.getInstance(TimeZone.getTimeZone("America/New_York")).apply {
            clear()
            set(2026, Calendar.MARCH, 8, 0, 0, 0)
        }
        val next = nextSmartAlarmEpochSec(
            150, setOf(Calendar.SUNDAY), nowMs = now.timeInMillis,
            calendarFactory = { now.clone() as Calendar },
            skippedOccurrence = PlannerAlarmPolicy.occurrenceKey(2026, 3, 8, 210),
        )
        val resolved = (now.clone() as Calendar).apply { timeInMillis = next!! * 1000 }
        assertEquals(15, resolved.get(Calendar.DAY_OF_MONTH))
        assertEquals(2, resolved.get(Calendar.HOUR_OF_DAY))
    }

    @Test fun aPendingSkipCannotBeReplacedDuringTheFirstFoldHour() {
        val now = Calendar.getInstance(TimeZone.getTimeZone("America/New_York")).apply {
            timeInMillis = java.time.Instant.parse("2026-11-01T05:31:00Z").toEpochMilli()
        }
        val skipped = "2026-11-01|90"
        assertTrue(PlannerAlarmPolicy.isSkipPending(skipped, now))
        val wake = nextSmartAlarmEpochSec(
            90, setOf(Calendar.SUNDAY), nowMs = now.timeInMillis,
            calendarFactory = { now.clone() as Calendar },
        )
        assertEquals(java.time.Instant.parse("2026-11-01T06:30:00Z").epochSecond, wake)
        val next = nextSmartAlarmEpochSec(
            90, setOf(Calendar.SUNDAY), nowMs = now.timeInMillis,
            calendarFactory = { now.clone() as Calendar }, skippedOccurrence = skipped,
        )
        assertEquals(java.time.Instant.parse("2026-11-08T06:30:00Z").epochSecond, next)
        now.timeInMillis = java.time.Instant.parse("2026-11-01T06:31:00Z").toEpochMilli()
        assertFalse(PlannerAlarmPolicy.isSkipPending(skipped, now))
    }

    @Test fun remindersUseWeekdayGoalAndDebt() {
        val now = calendar(hour = 12)
        val next = WindDownScheduler.nextPlannerReminder(
            now, SleepPlannerSettings(baseNeedMinutes = 480, debtMinutes = 60, historyNights = 3, goals = mapOf(Calendar.THURSDAY to 70)),
            setOf(Calendar.THURSDAY), 420, emptyMap(), 30,
        )!!
        assertEquals(378, next.plan.targetSleepMinutes)
        assertEquals(18, next.at.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, next.at.get(Calendar.HOUR_OF_DAY))
        assertEquals(12, next.at.get(Calendar.MINUTE))
        assertTrue(next.plan.debtNudge)
    }

    @Test fun resolvedGoalAndTargetStayTogetherInTheSelectedWakeSnapshot() {
        val now = calendar(hour = 12)
        for ((goals, expectedGoal, expectedTarget) in listOf(
            Triple(emptyMap<Int, Int>(), 100, 555),
            Triple(mapOf(Calendar.THURSDAY to 70), 70, 389),
            Triple(mapOf(Calendar.THURSDAY to 99), 100, 555),
        )) {
            val settings = SleepPlannerSettings(baseNeedMinutes = 480, debtMinutes = 75, goals = goals)
            val (goal, plan) = settings.resolvedPlan(Calendar.THURSDAY, 420, 30)
            assertEquals(expectedGoal, goal)
            assertEquals(555, plan.needMinutes)
            assertEquals(expectedTarget, plan.targetSleepMinutes)
            val snapshot = plannerAlarmSnapshot(
                nowMs = now.timeInMillis, enabled = true, wakeMinutes = 420,
                weekdays = setOf(Calendar.THURSDAY), overrides = emptyMap(),
                settings = settings, leadMinutes = 30,
                sentEpoch = 0, sentAt = 0, sentDevice = null, sentConnected = false,
                reportedEpoch = 0, reportedAt = 0, reportedDevice = null, activeDevice = "active",
                calendarFactory = { now.clone() as Calendar },
            )
            assertEquals(Calendar.THURSDAY, snapshot.wake.get(Calendar.DAY_OF_WEEK))
            assertEquals(expectedGoal, snapshot.goalPercent)
            assertEquals(expectedTarget, snapshot.plan.targetSleepMinutes)
        }
    }

    @Test fun skippedReminderRetainsTheFutureWeek() {
        val next = WindDownScheduler.nextPlannerReminder(
            calendar(day = 16, hour = 12),
            SleepPlannerSettings(skippedOccurrence = "2026-06-17|420"),
            setOf(Calendar.WEDNESDAY), 420, emptyMap(), 30,
        )!!
        assertEquals("2026-06-24|420", next.occurrenceKey)
        assertEquals(23, next.at.get(Calendar.DAY_OF_MONTH))
    }

    @Test fun debtOnlyRemindersNeedEnoughHistoryAndARealDebtNudge() {
        assertNull(WindDownScheduler.nextPlannerReminder(
            calendar(hour = 12), SleepPlannerSettings(debtReminderEnabled = true, debtMinutes = 120, historyNights = 2),
            emptySet(), 420, emptyMap(), 30, debtOnly = true,
        ))
        val eligible = WindDownScheduler.nextPlannerReminder(
            calendar(hour = 12), SleepPlannerSettings(debtReminderEnabled = true, debtMinutes = 120, historyNights = 3),
            emptySet(), 420, emptyMap(), 30, debtOnly = true,
        )
        assertTrue(eligible!!.plan.debtNudge)
        assertNull(WindDownScheduler.nextPlannerReminder(
            calendar(hour = 12), SleepPlannerSettings(debtReminderEnabled = false, debtMinutes = 120, historyNights = 3),
            emptySet(), 420, emptyMap(), 30, debtOnly = true,
        ))
    }

    private fun dstSnapshot(now: Calendar): PlannerAlarmSnapshot = plannerAlarmSnapshot(
        nowMs = now.timeInMillis, enabled = true, wakeMinutes = 420,
        weekdays = setOf(Calendar.SUNDAY), overrides = emptyMap(),
        settings = SleepPlannerSettings(baseNeedMinutes = 480), leadMinutes = 30,
        sentEpoch = 0, sentAt = 0, sentDevice = null, sentConnected = false,
        reportedEpoch = 0, reportedAt = 0, reportedDevice = null, activeDevice = "active",
        calendarFactory = { now.clone() as Calendar },
    )

    @Test fun springTransitionKeepsEightElapsedSleepHours() {
        val now = Calendar.getInstance(TimeZone.getTimeZone("America/New_York")).apply {
            clear()
            set(2026, Calendar.MARCH, 7, 12, 0, 0)
        }
        val snapshot = dstSnapshot(now)
        assertEquals(8, snapshot.wake.get(Calendar.DAY_OF_MONTH))
        assertEquals(7, snapshot.wake.get(Calendar.HOUR_OF_DAY))
        assertEquals(7, snapshot.bedtime.get(Calendar.DAY_OF_MONTH))
        assertEquals(22, snapshot.bedtime.get(Calendar.HOUR_OF_DAY))
        assertEquals(21, snapshot.reminder.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, snapshot.reminder.get(Calendar.MINUTE))
        assertEquals(480 * 60_000L, snapshot.wake.timeInMillis - snapshot.bedtime.timeInMillis)
        assertEquals(30 * 60_000L, snapshot.bedtime.timeInMillis - snapshot.reminder.timeInMillis)
        assertEquals(23 * 60, snapshot.plan.bedtimeMinutes)
        val reminder = WindDownScheduler.nextPlannerReminder(
            now, SleepPlannerSettings(baseNeedMinutes = 480), setOf(Calendar.SUNDAY), 420, emptyMap(), 30,
        )!!
        assertEquals(snapshot.wake.timeInMillis, reminder.wakeAt.timeInMillis)
        assertEquals(snapshot.bedtime.timeInMillis, reminder.bedtimeAt.timeInMillis)
        assertEquals(snapshot.reminder.timeInMillis, reminder.at.timeInMillis)
    }

    @Test fun autumnTransitionKeepsEightElapsedSleepHours() {
        val now = Calendar.getInstance(TimeZone.getTimeZone("America/New_York")).apply {
            clear()
            set(2026, Calendar.OCTOBER, 31, 12, 0, 0)
        }
        val snapshot = dstSnapshot(now)
        assertEquals(Calendar.NOVEMBER, snapshot.wake.get(Calendar.MONTH))
        assertEquals(1, snapshot.wake.get(Calendar.DAY_OF_MONTH))
        assertEquals(7, snapshot.wake.get(Calendar.HOUR_OF_DAY))
        assertEquals(1, snapshot.bedtime.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, snapshot.bedtime.get(Calendar.HOUR_OF_DAY))
        assertEquals(Calendar.OCTOBER, snapshot.reminder.get(Calendar.MONTH))
        assertEquals(31, snapshot.reminder.get(Calendar.DAY_OF_MONTH))
        assertEquals(23, snapshot.reminder.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, snapshot.reminder.get(Calendar.MINUTE))
        assertEquals(480 * 60_000L, snapshot.wake.timeInMillis - snapshot.bedtime.timeInMillis)
        assertEquals(30 * 60_000L, snapshot.bedtime.timeInMillis - snapshot.reminder.timeInMillis)
        assertEquals(23 * 60, snapshot.plan.bedtimeMinutes)
        val reminder = WindDownScheduler.nextPlannerReminder(
            now, SleepPlannerSettings(baseNeedMinutes = 480), setOf(Calendar.SUNDAY), 420, emptyMap(), 30,
        )!!
        assertEquals(snapshot.wake.timeInMillis, reminder.wakeAt.timeInMillis)
        assertEquals(snapshot.bedtime.timeInMillis, reminder.bedtimeAt.timeInMillis)
        assertEquals(snapshot.reminder.timeInMillis, reminder.at.timeInMillis)
    }

    private fun snapshot(reportedDevice: String = "active", reportedAt: Long = 20, enabled: Boolean = true, rejectStreak: Int = 0): PlannerAlarmSnapshot {
        val now = calendar().timeInMillis
        val epoch = nextSmartAlarmEpochSec(420, emptySet(), nowMs = now)!!
        return plannerAlarmSnapshot(
            nowMs = now, enabled = enabled, wakeMinutes = 420, weekdays = emptySet(), overrides = emptyMap(),
            settings = SleepPlannerSettings(), leadMinutes = 30,
            sentEpoch = epoch, sentAt = 10, sentDevice = "active", sentConnected = true,
            reportedEpoch = epoch, reportedAt = reportedAt, reportedDevice = reportedDevice, activeDevice = "active", rejectStreak = rejectStreak,
        )
    }

    @Test fun anotherStrapOrOldReadbackCannotConfirmTheDeadline() {
        assertEquals("sent", snapshot(reportedDevice = "other").status)
        assertNull(snapshot(reportedDevice = "other").countdownMinutes)
        assertEquals("sent", snapshot(reportedAt = 9).status)
        assertNull(snapshot(reportedAt = 9).countdownMinutes)
    }

    @Test fun rejectionStreakSuppressesAReadbackCountdown() {
        assertEquals("sent", snapshot(rejectStreak = 1).status)
        assertNull(snapshot(rejectStreak = 1).countdownMinutes)
    }

    @Test fun oneMatchedSnapshotOwnsTheConfirmedCountdown() {
        val snapshot = snapshot()
        assertEquals("reported", snapshot.status)
        assertEquals(snapshot.wake.get(Calendar.HOUR_OF_DAY) * 60 + snapshot.wake.get(Calendar.MINUTE), snapshot.plan.wakeMinutes)
        assertTrue(snapshot.countdownMinutes!! > 0)
    }

    @Test fun disabledAlarmMakesNoCountdownPromise() {
        val snapshot = snapshot(enabled = false)
        assertEquals("off", snapshot.status)
        assertNull(snapshot.epochSec)
        assertNull(snapshot.countdownMinutes)
    }
}
