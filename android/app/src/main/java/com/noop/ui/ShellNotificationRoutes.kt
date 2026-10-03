package com.noop.ui

/** The tab and local detail installed for an explicit notification or Updates tap. */
internal data class ShellDetailDestination(val root: String, val detail: String)

internal const val LOCAL_NOTICE_ROUTE = "local_notice"

internal fun datedNotificationDestination(route: String): ShellDetailDestination? = when (route) {
    "devices" -> ShellDetailDestination("more", "devices")
    "workouts", "local_briefing" -> ShellDetailDestination("more", LOCAL_NOTICE_ROUTE)
    "weekly_plan" -> ShellDetailDestination("plan", LOCAL_NOTICE_ROUTE)
    else -> null
}

internal fun coachDestination(hasKey: Boolean): String = if (hasKey) "coach" else WhoopRoute.localBriefing

/** Only notification-producer keys can navigate; report taps always open the offline report. */
internal fun localNotificationDestination(route: String, hasCoachKey: Boolean): ShellDetailDestination? = when (route) {
    "devices", "workouts", "local_briefing" -> ShellDetailDestination("more", route)
    "weekly_plan" -> ShellDetailDestination("plan", WhoopRoute.weeklyPlan)
    "coach" -> ShellDetailDestination("more", coachDestination(hasCoachKey))
    else -> null
}

internal fun updatesDestination(key: String): ShellDetailDestination? = when (key) {
    "trends" -> ShellDetailDestination("plan", "trends")
    else -> null
}
