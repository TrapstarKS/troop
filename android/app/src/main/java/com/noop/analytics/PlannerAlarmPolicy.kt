package com.noop.analytics

import java.util.Calendar
import java.util.GregorianCalendar

/**
 * Pure policy for advancing an alarm within its final hour. The caller supplies only
 * a recovery value from the current night; this helper cannot establish freshness.
 * Only recovery percentages in 67..100 can advance the alarm.
 */
object PlannerAlarmPolicy {
    fun shouldWakeEarly(
        mode: String,
        targetSleepMinutes: Int,
        observedSleepMinutes: Int?,
        currentNightRecoveryPercent: Int?,
        minutesUntilDeadline: Int,
    ): Boolean {
        if (minutesUntilDeadline !in 1..60) return false

        return when (mode) {
            "sleepGoal" -> targetSleepMinutes > 0 &&
                observedSleepMinutes != null && observedSleepMinutes >= targetSleepMinutes
            "recovery" -> currentNightRecoveryPercent != null && currentNightRecoveryPercent in 67..100
            else -> false
        }
    }

    /**
     * Stable identity for supplied Gregorian date components and a minute. Supplied
     * components are preserved rather than normalized.
     */
    fun occurrenceKey(year: Int, month: Int, day: Int, minutes: Int): String =
        "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-" +
            "${day.toString().padStart(2, '0')}|$minutes"

    /**
     * Gregorian identity for a resolved alarm instant in the caller's timezone,
     * independent of the caller's preferred calendar.
     */
    fun occurrenceKey(date: Calendar): String {
        val gregorian = GregorianCalendar(date.timeZone).apply { timeInMillis = date.timeInMillis }
        return occurrenceKey(
            gregorian.get(Calendar.YEAR), gregorian.get(Calendar.MONTH) + 1, gregorian.get(Calendar.DAY_OF_MONTH),
            gregorian.get(Calendar.HOUR_OF_DAY) * 60 + gregorian.get(Calendar.MINUTE),
        )
    }

    /**
     * Advice quiet hours with an inclusive start and exclusive end. Equal bounds
     * disable the quiet window; wake-alarm deadlines are exempt at the caller.
     */
    fun isQuietMinute(minute: Int, enabled: Boolean, startMinutes: Int, endMinutes: Int): Boolean {
        if (!enabled) return false
        val currentMinute = minute.coerceIn(0, 1439)
        val start = startMinutes.coerceIn(0, 1439)
        val end = endMinutes.coerceIn(0, 1439)
        if (start == end) return false
        return if (start < end) currentMinute >= start && currentMinute < end else currentMinute >= start || currentMinute < end
    }

    /**
     * Pending until the saved wake instant. Scheduling still matches the exact
     * occurrence key; this comparison only gates the pending status and duplicate skip.
     */
    fun isSkipPending(skippedOccurrence: String, now: Calendar): Boolean {
        val saved = occurrenceParts(skippedOccurrence) ?: return false
        val parts = saved.first.split('-').map { it.toInt() }
        val savedDay = GregorianCalendar(now.timeZone).apply {
            clear()
            set(parts[0], parts[1] - 1, parts[2], 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (savedDay.get(Calendar.YEAR) != parts[0] || savedDay.get(Calendar.MONTH) != parts[1] - 1 ||
            savedDay.get(Calendar.DAY_OF_MONTH) != parts[2]) return false
        return SleepPlanner.wakeDate(saved.second, savedDay).timeInMillis >= now.timeInMillis
    }

    private fun occurrenceParts(key: String): Pair<String, Int>? {
        val parts = key.split('|')
        if (parts.size != 2) return null
        val day = parts[0]
        if (day.length != 10 || !day.withIndex().all { (index, character) ->
                if (index == 4 || index == 7) character == '-' else character in '0'..'9'
            }) return null
        val year = day.substring(0, 4).toInt()
        val month = day.substring(5, 7).toInt()
        val date = day.substring(8, 10).toInt()
        val minutes = parts[1].toIntOrNull() ?: return null
        if (year <= 0 || month !in 1..12 || minutes !in 0..1439 || minutes.toString() != parts[1]) return null
        val leapYear = year % 400 == 0 || (year % 4 == 0 && year % 100 != 0)
        val days = intArrayOf(31, if (leapYear) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        if (date !in 1..days[month - 1]) return null
        return day to minutes
    }
}
