package com.noop.notif

data class LocalRecordedReport(
    val day: String,
    val recovery: Int?,
    val sleepMinutes: Int?,
    val strainTenths: Int?,
    val streak: Int,
    val sleepNeedMinutes: Int? = null,
    val sleepDebtMinutes: Int? = null,
) {
    companion object {
        fun forDisplay(context: LocalNotificationContext?, current: LocalRecordedReport?): LocalRecordedReport? =
            if (context != null) context.report else current
    }
}

data class LocalNotificationContext(
    val route: String,
    val eventID: String,
    val family: String? = null,
    val day: String? = null,
    val weekKey: String? = null,
    val workoutStartSec: Long? = null,
    val message: String? = null,
    val report: LocalRecordedReport? = null,
) {
    val identity: String get() = "$route:$eventID"

    val wireFields: Map<String, String> get() = buildMap {
        put("localNotificationRoute", route)
        put("localNotificationEvent", eventID)
        family?.let { put("localNotificationFamily", it) }
        (day ?: report?.day)?.let { put("localNotificationDay", it) }
        weekKey?.let { put("localNotificationWeek", it) }
        workoutStartSec?.let { put("localNotificationWorkoutStart", it.toString()) }
        message?.let { put("localNotificationMessage", it) }
        report?.let {
            put("localNotificationReport", "1")
            put("localNotificationDay", it.day)
            put("localNotificationStreak", it.streak.toString())
            it.recovery?.let { value -> put("localNotificationRecovery", value.toString()) }
            it.sleepMinutes?.let { value -> put("localNotificationSleep", value.toString()) }
            it.strainTenths?.let { value -> put("localNotificationStrain", value.toString()) }
            it.sleepNeedMinutes?.let { value -> put("localNotificationSleepNeed", value.toString()) }
            it.sleepDebtMinutes?.let { value -> put("localNotificationSleepDebt", value.toString()) }
        }
    }

    companion object {
        fun fromWireFields(fields: Map<String, String>): LocalNotificationContext? {
            val route = fields["localNotificationRoute"]?.takeIf { it.isNotEmpty() } ?: return null
            val day = fields["localNotificationDay"]
            val streak = fields["localNotificationStreak"]?.toIntOrNull()
            val report = if (fields["localNotificationReport"] == "1" && day != null && streak != null)
                LocalRecordedReport(day, fields["localNotificationRecovery"]?.toIntOrNull(),
                    fields["localNotificationSleep"]?.toIntOrNull(), fields["localNotificationStrain"]?.toIntOrNull(),
                    streak, fields["localNotificationSleepNeed"]?.toIntOrNull(), fields["localNotificationSleepDebt"]?.toIntOrNull())
            else null
            return LocalNotificationContext(route, fields["localNotificationEvent"] ?: route,
                fields["localNotificationFamily"], day, fields["localNotificationWeek"],
                fields["localNotificationWorkoutStart"]?.toLongOrNull(), fields["localNotificationMessage"], report)
        }
    }
}
