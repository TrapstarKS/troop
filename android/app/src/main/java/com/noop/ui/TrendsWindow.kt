package com.noop.ui

import java.time.LocalDate

internal data class TrendsWindow(val start: String, val end: String) {
    fun contains(day: String): Boolean = day in start..end

    companion object {
        fun period(days: Int, offset: Int, today: String): TrendsWindow? {
            val date = parse(today) ?: return null
            val shift = offset.coerceAtMost(0)
            val start: LocalDate
            val end: LocalDate
            if (days == 7) {
                start = date.minusDays((date.dayOfWeek.value - 1).toLong()).plusWeeks(shift.toLong())
                end = start.plusDays(6)
            } else {
                val months = if (days == 180) 6 else 1
                start = date.withDayOfMonth(1).plusMonths((shift * months - (months - 1)).toLong())
                end = start.plusMonths(months.toLong()).minusDays(1)
            }
            return TrendsWindow(start.toString(), minOf(end, date).toString())
        }

        fun minimumOffset(days: Int, earliest: String?, today: String): Int {
            if (earliest == null || parse(earliest) == null) return 0
            var offset = 0
            while (offset > -520 && (period(days, offset, today)?.start ?: earliest) > earliest) offset -= 1
            return offset
        }

        private fun parse(day: String): LocalDate? = runCatching { LocalDate.parse(day) }.getOrNull()
    }
}
