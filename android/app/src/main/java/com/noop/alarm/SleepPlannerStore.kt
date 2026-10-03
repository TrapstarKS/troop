package com.noop.alarm

import android.content.Context
import android.content.SharedPreferences
import com.noop.analytics.SleepPlan
import com.noop.analytics.SleepPlanner
import com.noop.ui.NoopPrefs

data class SleepPlannerSettings(
    val goalPercent: Int = 100,
    val goals: Map<Int, Int> = emptyMap(),
    val alarmMode: String = "exact",
    val skippedOccurrence: String = "",
    val baseNeedMinutes: Int = 480,
    val debtMinutes: Int = 0,
    val historyNights: Int = 0,
    val debtReminderEnabled: Boolean = false,
) {
    fun plan(weekday: Int, wakeMinutes: Int, leadMinutes: Int): SleepPlan =
        resolvedPlan(weekday, wakeMinutes, leadMinutes).second

    fun resolvedPlan(weekday: Int, wakeMinutes: Int, leadMinutes: Int): Pair<Int, SleepPlan> {
        val resolvedGoal = SleepPlanner.weekdayGoal(weekday, goals, goalPercent)
        return resolvedGoal to SleepPlanner.plan(
            baseNeedMinutes = baseNeedMinutes,
            debtMinutes = debtMinutes,
            goalPercent = resolvedGoal,
            wakeMinutes = wakeMinutes,
            leadMinutes = leadMinutes,
            historyNights = historyNights,
        )
    }
}

class SleepPlannerStore(private val prefs: SharedPreferences) {
    fun read(): SleepPlannerSettings = SleepPlannerSettings(
        goalPercent = validGoal(prefs.getInt("sleepPlanner.goalPercent", 100)),
        goals = (1..7).mapNotNull { day ->
            val key = "sleepPlanner.goal.$day"
            if (prefs.contains(key)) day to validGoal(prefs.getInt(key, 100)) else null
        }.toMap(),
        alarmMode = prefs.getString("sleepPlanner.alarmMode", "exact")
            ?.takeIf { it in setOf("exact", "sleepGoal", "recovery") } ?: "exact",
        skippedOccurrence = prefs.getString("sleepPlanner.skippedOccurrence", "") ?: "",
        baseNeedMinutes = prefs.getInt("sleepPlanner.baseNeedMinutes", 480).coerceIn(300, 660),
        debtMinutes = prefs.getInt("sleepPlanner.debtMinutes", 0).coerceAtLeast(0),
        historyNights = prefs.getInt("sleepPlanner.historyNights", 0).coerceAtLeast(0),
        debtReminderEnabled = prefs.getInt("sleepPlanner.debtReminderEnabled", 0) == 1,
    )

    fun write(settings: SleepPlannerSettings) {
        val edit = prefs.edit()
            .putInt("sleepPlanner.goalPercent", validGoal(settings.goalPercent))
            .putString("sleepPlanner.alarmMode", settings.alarmMode)
            .putString("sleepPlanner.skippedOccurrence", settings.skippedOccurrence)
            .putInt("sleepPlanner.baseNeedMinutes", settings.baseNeedMinutes)
            .putInt("sleepPlanner.debtMinutes", settings.debtMinutes)
            .putInt("sleepPlanner.historyNights", settings.historyNights)
            .putInt("sleepPlanner.debtReminderEnabled", if (settings.debtReminderEnabled) 1 else 0)
        for (day in 1..7) {
            val key = "sleepPlanner.goal.$day"
            settings.goals[day]?.let { edit.putInt(key, validGoal(it)) } ?: edit.remove(key)
        }
        edit.apply()
    }

    companion object {
        private fun validGoal(value: Int): Int = if (value == 85 || value == 70) value else 100
        fun from(context: Context): SleepPlannerStore = SleepPlannerStore(NoopPrefs.of(context))
    }
}
