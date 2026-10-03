package com.noop.ui

import com.noop.data.WeeklyPlanCalendar

object JournalCalendar {
    fun rolloverOffset(offset: Long, from: String, to: String, preserveDraft: Boolean): Long {
        val previous = WeeklyPlanCalendar.date(from) ?: return offset
        val current = WeeklyPlanCalendar.date(to) ?: return offset
        if (offset == 0L && !preserveDraft) return 0L
        val delta = (current.time - previous.time) / 86_400_000L
        return runCatching { Math.addExact(offset, delta) }.getOrDefault(offset)
    }
}
