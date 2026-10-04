package com.noop.analytics

import java.util.Calendar

/** Sleep-duration goals as a share of the local planning estimate, not composite performance scores. */
enum class SleepPlannerGoal(val percent: Int) {
    PEAK(100),
    PERFORM(85),
    GET_BY(70),
}

/**
 * A local sleep-duration estimate. Clock minutes are relative to the wake day's midnight;
 * a day shift of -1 places bedtime or its reminder on the previous calendar day.
 */
data class SleepPlan(
    val needMinutes: Int,
    val debtMinutes: Int,
    val targetSleepMinutes: Int,
    val bedtimeMinutes: Int,
    val bedtimeDayShift: Int,
    val reminderMinutes: Int,
    val reminderDayShift: Int,
    val wakeMinutes: Int,
    val historyReady: Boolean,
    val debtNudge: Boolean,
)

object SleepPlanner {
    /**
     * Plans from a caller-supplied base need and nonnegative debt magnitude. Base need is
     * bounded to 300..660 minutes; the added debt estimate is capped at 120 minutes.
     * These are local planning bounds, not a physiological claim. The target is rounded
     * up to the next whole minute and unsupported goals fall back to 100 percent.
     */
    fun plan(
        baseNeedMinutes: Int,
        debtMinutes: Int,
        goalPercent: Int,
        wakeMinutes: Int,
        leadMinutes: Int,
        historyNights: Int,
    ): SleepPlan {
        val base = baseNeedMinutes.coerceIn(300, 660)
        val debt = debtMinutes.coerceIn(0, 120)
        val need = base + debt
        val target = (need * normalizedGoal(goalPercent) + 99) / 100
        val wake = wakeMinutes.coerceIn(0, 1439)
        val lead = leadMinutes.coerceIn(0, 120)
        val rawBedtime = wake - target
        val rawReminder = rawBedtime - lead
        val historyReady = historyNights >= 3

        return SleepPlan(
            needMinutes = need,
            debtMinutes = debt,
            targetSleepMinutes = target,
            bedtimeMinutes = Math.floorMod(rawBedtime, 1440),
            bedtimeDayShift = Math.floorDiv(rawBedtime, 1440),
            reminderMinutes = Math.floorMod(rawReminder, 1440),
            reminderDayShift = Math.floorDiv(rawReminder, 1440),
            wakeMinutes = wake,
            historyReady = historyReady,
            debtNudge = historyReady && debt >= 60,
        )
    }

    /**
     * Resolves a weekday override (1 = Sunday, 7 = Saturday). Invalid weekdays use
     * the default; unsupported percentages fall back to the full local need.
     */
    fun weekdayGoal(
        weekday: Int,
        overrides: Map<Int, Int>,
        defaultPercent: Int = 100,
    ): Int {
        val selected = if (weekday in 1..7) overrides[weekday] ?: defaultPercent else defaultPercent
        return normalizedGoal(selected)
    }

    /** Native lenient calendar resolution preserves minutes through a gap and uses the later fold. */
    fun wakeDate(minutes: Int, on: Calendar): Calendar = (on.clone() as Calendar).apply {
        val minute = minutes.coerceIn(0, 1439)
        set(Calendar.HOUR_OF_DAY, minute / 60)
        set(Calendar.MINUTE, minute % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    /** Subtracts elapsed sleep duration rather than civil clock minutes across a DST change. */
    fun bedtime(wake: Calendar, targetSleepMinutes: Int): Calendar = (wake.clone() as Calendar).apply {
        timeInMillis -= targetSleepMinutes.coerceIn(0, 780) * 60_000L
    }

    private fun normalizedGoal(percent: Int): Int = when (percent) {
        85, 70 -> percent
        else -> SleepPlannerGoal.PEAK.percent
    }
}
