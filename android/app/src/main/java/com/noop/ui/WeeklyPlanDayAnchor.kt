package com.noop.ui

import com.noop.data.WeeklyPlanCalendar

data class WeeklyPlanDayAnchor(val today: String, val weekOffset: Int) {
    fun advanced(day: String): WeeklyPlanDayAnchor {
        if (day == today) return this
        val oldWeek = WeeklyPlanCalendar.weekStart(today) ?: return this
        val nextWeek = WeeklyPlanCalendar.weekStart(day) ?: return this
        var offset = weekOffset
        if (weekOffset < 0) {
            val selected = WeeklyPlanCalendar.adding(weekOffset * 7, oldWeek)
            val selectedDate = selected?.let(WeeklyPlanCalendar::date)
            val nextDate = WeeklyPlanCalendar.date(nextWeek)
            if (selectedDate != null && nextDate != null) {
                offset = ((selectedDate.time - nextDate.time) / 604_800_000L).toInt().coerceAtMost(0)
            }
        }
        return WeeklyPlanDayAnchor(day, offset)
    }
}
