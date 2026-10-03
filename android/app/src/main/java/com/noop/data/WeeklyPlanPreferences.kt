package com.noop.data

import android.content.Context

class WeeklyPlanPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("noop_prefs", Context.MODE_PRIVATE)
    private val prefix = "noop.weeklyPlan."

    // Swift twin: `WeeklyPlanPreferences.hasPlan`.
    fun hasPlan(weekStart: String): Boolean = preferences.contains("$prefix$weekStart.sleepMinutes")

    // Swift twin: `WeeklyPlanPreferences.goals`.
    fun goals(weekStart: String, suggested: WeeklyPlanGoals = WeeklyPlanGoals()): WeeklyPlanGoals =
        read(if (hasPlan(weekStart)) weekStart else "template", suggested)

    // Swift twin: `WeeklyPlanPreferences.save`.
    fun save(goals: WeeklyPlanGoals, weekStart: String, updateTemplate: Boolean = true) {
        if (WeeklyPlanCalendar.weekStart(weekStart) != weekStart) return
        write(goals.normalized, weekStart)
        if (updateTemplate) write(goals.normalized, "template")
    }

    // Swift twin: `WeeklyPlanPreferences.notice`.
    fun notice(today: String): WeeklyPlanNotice? {
        val week = WeeklyPlanCalendar.weekStart(today) ?: return null
        val previous = WeeklyPlanCalendar.adding(-7, week) ?: return null
        val available = listOf(week, previous).filter { hasPlan(it) }.toSet()
        val dismissed = listOf("checkIn", "recap").mapNotNull { preferences.getString("${prefix}dismissed.$it", null) }.toSet()
        return WeeklyPlanNoticeResolver.resolve(today, available, dismissed)
    }

    // Swift twin: `WeeklyPlanPreferences.dismiss`.
    fun dismiss(notice: WeeklyPlanNotice) {
        preferences.edit().putString("${prefix}dismissed.${notice.kind.key}", notice.id).apply()
    }

    // Swift twin: `WeeklyPlanPreferences.read`.
    private fun read(scope: String, fallback: WeeklyPlanGoals): WeeklyPlanGoals {
        val key = "$prefix$scope."
        return WeeklyPlanGoals(
            preferences.getInt(key + "sleepMinutes", fallback.sleepMinutes),
            preferences.getInt(key + "sleepDays", fallback.sleepDays),
            preferences.getInt(key + "strainMinimum", fallback.strainMinimum),
            preferences.getInt(key + "strainDays", fallback.strainDays),
            preferences.getInt(key + "journalDays", fallback.journalDays),
            preferences.getString(key + "journalQuestion", fallback.journalQuestion) ?: fallback.journalQuestion,
            preferences.getString(key + "journalAnswer", fallback.journalAnswer) ?: fallback.journalAnswer,
        ).normalized
    }

    // Swift twin: `WeeklyPlanPreferences.write`.
    private fun write(goals: WeeklyPlanGoals, scope: String) {
        val key = "$prefix$scope."
        preferences.edit()
            .putInt(key + "sleepMinutes", goals.sleepMinutes)
            .putInt(key + "sleepDays", goals.sleepDays)
            .putInt(key + "strainMinimum", goals.strainMinimum)
            .putInt(key + "strainDays", goals.strainDays)
            .putInt(key + "journalDays", goals.journalDays)
            .putString(key + "journalQuestion", goals.journalQuestion)
            .putString(key + "journalAnswer", goals.journalAnswer)
            .apply()
    }
}

// Swift twin: `seedWeeklyPlanDemo`.
fun seedWeeklyPlanDemo(context: Context, today: String) {
    val week = WeeklyPlanCalendar.weekStart(today) ?: return
    val previous = WeeklyPlanCalendar.adding(-7, week) ?: return
    val preferences = WeeklyPlanPreferences(context)
    val goals = WeeklyPlanGoals(sleepMinutes = 420, sleepDays = 5, strainMinimum = 50, strainDays = 3, journalDays = 5)
    if (!preferences.hasPlan(previous)) preferences.save(goals, previous, updateTemplate = false)
    if (!preferences.hasPlan(week)) preferences.save(goals, week)
}
