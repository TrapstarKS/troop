package com.noop.notif

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.noop.R
import com.noop.ui.NoopPrefs
import com.noop.ui.NotifPrefs
import com.noop.ui.appLaunchIntent
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

const val LOCAL_NOTIFICATION_ROUTE = "localNotificationRoute"

/** Route-only producers (Coach brief, battery) keep the legacy contract: no event, so no typed context. */
fun localNotificationLaunchIntent(context: Context, route: String): Intent =
    appLaunchIntent(context).apply {
        routeOnlyNotificationFields(route).forEach { (key, value) -> putExtra(key, value) }
        data = Uri.Builder().scheme("noop").authority("local-notification").appendPath(route).build()
    }

fun routeOnlyNotificationFields(route: String): Map<String, String> = mapOf(LOCAL_NOTIFICATION_ROUTE to route)

/** Splits launch extras into a typed dated context (has an event) or a legacy route, never both. */
fun stagedLocalNotification(fields: Map<String, String>): Pair<LocalNotificationContext?, String?> {
    val typed = if (fields.containsKey("localNotificationEvent")) LocalNotificationContext.fromWireFields(fields) else null
    return typed to (if (typed == null) fields[LOCAL_NOTIFICATION_ROUTE] else null)
}

fun localNotificationLaunchIntent(context: Context, notification: LocalNotificationContext): Intent =
    appLaunchIntent(context).apply {
        notification.wireFields.forEach { (key, value) -> putExtra(key, value) }
        // Intent.filterEquals includes data: retained dates cannot share UPDATE_CURRENT extras.
        data = Uri.Builder().scheme("noop").authority("local-notification")
            .appendPath(notification.route).appendQueryParameter("event", notification.eventID).build()
    }

fun localNotificationContext(intent: Intent): LocalNotificationContext? =
    LocalNotificationContext.fromWireFields(intent.extras?.keySet()?.mapNotNull { key ->
        intent.getStringExtra(key)?.let { key to it }
    }?.toMap().orEmpty())

enum class LocalNotificationFamily(val key: String) {
    RECOVERY_READY("recoveryReady"), SLEEP_READY("sleepReady"), STRAIN_READY("strainReady"),
    MORNING_RECAP("morningRecap"), DAILY_OUTLOOK("dailyOutlook"), DAY_IN_REVIEW("dayInReview"),
    STREAK("streakSummary"), DISCONNECTED("disconnected"), WEAR("wearReminder"),
    FRIDAY_CHECK_IN("weeklyCheckIn"), MONDAY_RECAP("weeklyRecap"),
}

object LocalNotificationPrefs {
    fun enabled(context: Context, family: LocalNotificationFamily): Boolean =
        if (family == LocalNotificationFamily.MORNING_RECAP) NoopPrefs.morningReportEnabled(context)
        else NoopPrefs.of(context).getBoolean("localNotifications.${family.key}.enabled", false)

    fun setEnabled(context: Context, family: LocalNotificationFamily, enabled: Boolean) {
        if (family == LocalNotificationFamily.MORNING_RECAP) NoopPrefs.setMorningReportEnabled(context, enabled)
        else NoopPrefs.of(context).edit().putBoolean("localNotifications.${family.key}.enabled", enabled).apply()
    }

    fun lastEventKey(context: Context, family: LocalNotificationFamily): String? =
        NoopPrefs.of(context).getString("localNotifications.${family.key}.lastEventKey", null)

    fun setLastEventKey(context: Context, family: LocalNotificationFamily, key: String) {
        NoopPrefs.of(context).edit().putString("localNotifications.${family.key}.lastEventKey", key).apply()
    }

    fun quiet(context: Context, minute: Int): Boolean = LocalNotificationPolicy.isQuiet(
        minute, NotifPrefs.getInt(context, NotifPrefs.QUIET_START, 22 * 60),
        NotifPrefs.getInt(context, NotifPrefs.QUIET_END, 7 * 60),
        NotifPrefs.getBool(context, NotifPrefs.QUIET, false),
    )
}

data class LocalNotificationSnapshot(
    val day: String,
    val wakeSec: Long?,
    val recovery: Int?,
    val sleepMinutes: Int?,
    val strainTenths: Int?,
    val streak: Int,
    val syncPending: Boolean,
)

data class WeeklyPlanNotificationContent(val weekKey: String, val fridayCheckIn: String?, val mondayRecap: String?)

fun interface WeeklyPlanNotificationProvider {
    fun content(day: LocalDate): WeeklyPlanNotificationContent?
}

class LocalNotificationDispatcher(private val context: Context) {
    var weeklyPlanProvider: WeeklyPlanNotificationProvider? = null
    private var hasConnected = false
    private var disconnectedSince: Long? = null
    private var offWristSince: Long? = null
    private var observedDeviceId: String? = null
    private var observedWhoop: Boolean? = null
    private var previousWorn: Boolean? = null

    fun resetDeviceObservation() {
        hasConnected = false
        disconnectedSince = null
        offWristSince = null
        previousWorn = null
        observedDeviceId = null
        observedWhoop = null
    }

    fun observeDeviceState(connected: Boolean, worn: Boolean, nowSec: Long, activeWhoop: Boolean, activeDeviceId: String) {
        if (observedDeviceId != activeDeviceId || observedWhoop != activeWhoop) resetDeviceObservation()
        observedDeviceId = activeDeviceId
        observedWhoop = activeWhoop
        if (!activeWhoop) return
        if (connected) {
            hasConnected = true
            disconnectedSince = null
        } else if (hasConnected && disconnectedSince == null) disconnectedSince = nowSec
        if (!connected) {
            offWristSince = null
            previousWorn = null
            return
        }
        if (previousWorn == true && !worn) offWristSince = nowSec
        if (worn) offWristSince = null
        previousWorn = worn
    }

    fun evaluate(snapshot: LocalNotificationSnapshot?, connected: Boolean, worn: Boolean, nowSec: Long,
                 activeWhoop: Boolean, activeDeviceId: String) {
        observeDeviceState(connected, worn, nowSec, activeWhoop, activeDeviceId)
        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochSecond(nowSec).atZone(zone)
        val today = now.toLocalDate()
        disconnectedSince?.takeIf { nowSec - it >= 300 }?.let {
            deliver(LocalNotificationFamily.DISCONNECTED, today.toString(), it + 300, nowSec,
                context.getString(R.string.local_notify_disconnect), context.getString(R.string.local_notify_disconnect_body))
        }
        offWristSince?.takeIf { nowSec - it >= 1800 }?.let {
            deliver(LocalNotificationFamily.WEAR, today.toString(), it + 1800, nowSec,
                context.getString(R.string.local_notify_wear), context.getString(R.string.local_notify_wear_body))
        }
        if (snapshot != null && !snapshot.syncPending) {
            snapshot.wakeSec?.let { wake ->
                val available = buildSet {
                    if (snapshot.sleepMinutes != null) add("sleepReady")
                    if (snapshot.recovery != null) add("recoveryReady")
                    if (isNotEmpty()) addAll(listOf("morningRecap", "dailyOutlook"))
                }
                val report = LocalRecordedReport(snapshot.day, snapshot.recovery, snapshot.sleepMinutes,
                    null, snapshot.streak)
                deliverReport(LocalNotificationReportGroup.NIGHT, report, available, wake, nowSec)
            }
            if (snapshot.day == today.toString() && now.toLocalTime() >= LocalTime.of(20, 0)) {
                val evening = today.atTime(20, 0).atZone(zone).toEpochSecond()
                val available = buildSet {
                    if (snapshot.strainTenths != null) add("strainReady")
                    if (snapshot.streak > 0) add("streakSummary")
                    if (snapshot.recovery != null || snapshot.sleepMinutes != null || isNotEmpty()) add("dayInReview")
                }
                val report = LocalRecordedReport(snapshot.day, snapshot.recovery, snapshot.sleepMinutes,
                    snapshot.strainTenths, snapshot.streak)
                deliverReport(LocalNotificationReportGroup.EVENING, report, available, evening, nowSec)
            }
        }
        if (now.toLocalTime() < LocalTime.of(17, 0)) return
        val plan = weeklyPlanProvider?.content(today) ?: return
        val occurrence = today.atTime(17, 0).atZone(zone).toEpochSecond()
        if (today.dayOfWeek == DayOfWeek.FRIDAY) plan.fridayCheckIn?.let {
            deliver(LocalNotificationFamily.FRIDAY_CHECK_IN, plan.weekKey, occurrence, nowSec,
                context.getString(R.string.local_notify_friday), it,
                notification = LocalNotificationContext("weekly_plan", "weeklyCheckIn:${plan.weekKey}",
                    family = "weeklyCheckIn", day = today.toString(), weekKey = plan.weekKey, message = it))
        }
        if (today.dayOfWeek == DayOfWeek.MONDAY) plan.mondayRecap?.let {
            deliver(LocalNotificationFamily.MONDAY_RECAP, plan.weekKey, occurrence, nowSec,
                context.getString(R.string.local_notify_monday), it,
                notification = LocalNotificationContext("weekly_plan", "weeklyRecap:${plan.weekKey}",
                    family = "weeklyRecap", day = today.toString(), weekKey = plan.weekKey, message = it))
        }
    }

    private fun lastEventKey(family: LocalNotificationFamily): String? =
        LocalNotificationPrefs.lastEventKey(context, family)
            ?: if (family == LocalNotificationFamily.MORNING_RECAP)
                NoopPrefs.reportMorningDay(context)?.let { "${family.key}:$it" } else null

    private fun deliverReport(group: LocalNotificationReportGroup, report: LocalRecordedReport,
                              available: Set<String>, occurrence: Long, now: Long) {
        val families = LocalNotificationFamily.entries.associateBy { it.key }
        val enabled = group.families.filter { LocalNotificationPrefs.enabled(context, families.getValue(it)) }.toSet()
        val delivered = group.families.filter { lastEventKey(families.getValue(it)) == "$it:${report.day}" }.toSet()
        val minute = Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault()).let { it.hour * 60 + it.minute }
        val accepted = LocalNotificationDeliveryPlan.deliver(group, available, enabled, delivered,
            NotificationManagerCompat.from(context).areNotificationsEnabled(), LocalNotificationPrefs.quiet(context, minute),
            occurrence, now) { plan ->
            val family = families.getValue(plan.primaryFamily)
            val title = when (family) {
                LocalNotificationFamily.RECOVERY_READY -> R.string.local_notify_recovery
                LocalNotificationFamily.SLEEP_READY -> R.string.local_notify_sleep
                LocalNotificationFamily.STRAIN_READY -> R.string.local_notify_strain
                LocalNotificationFamily.MORNING_RECAP -> R.string.local_notify_morning
                LocalNotificationFamily.DAILY_OUTLOOK -> R.string.local_outlook
                LocalNotificationFamily.DAY_IN_REVIEW -> R.string.local_review
                else -> R.string.local_notify_streak
            }
            val summary = LocalBriefingCopy.summary(context, report.recovery, report.sleepMinutes, report.strainTenths, report.streak)
            deliver(family, report.day, occurrence, now, context.getString(title), summary,
                LocalNotificationContext("local_briefing", "${group.name.lowercase()}:${report.day}", family.key,
                    day = report.day, report = report))
        } ?: return
        // Commit coverage in one preferences edit only after NotificationManager accepted the post.
        NoopPrefs.of(context).edit().apply {
            accepted.coveredFamilies.forEach {
                putString("localNotifications.$it.lastEventKey", "$it:${report.day}")
            }
        }.apply()
        if ("morningRecap" in accepted.coveredFamilies) NoopPrefs.setReportMorningDay(context, report.day)
    }

    @SuppressLint("MissingPermission")
    private fun deliver(family: LocalNotificationFamily, day: String, occurrence: Long, now: Long,
                        title: String, body: String, notification: LocalNotificationContext? = null): Boolean {
        val key = "${family.key}:$day"
        val minute = Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault()).let { it.hour * 60 + it.minute }
        val manager = NotificationManagerCompat.from(context)
        if (!LocalNotificationPolicy.shouldDeliver(LocalNotificationPrefs.enabled(context, family),
                manager.areNotificationsEnabled(), LocalNotificationPrefs.quiet(context, minute), key, lastEventKey(family),
                occurrence, now)) return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val system = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                system.createNotificationChannel(NotificationChannel(CHANNEL,
                    context.getString(R.string.local_summary), NotificationManager.IMPORTANCE_LOW))
                if (system.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
            }
            val payload = notification ?: LocalNotificationContext("devices", key, family.key, day, message = body)
            val open = PendingIntent.getActivity(context, 4220 + family.ordinal, localNotificationLaunchIntent(context, payload),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            manager.notify(payload.identity, 4220 + family.ordinal, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_heart).setContentTitle(title).setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(open)
                .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_LOW).build())
            LocalNotificationPrefs.setLastEventKey(context, family, key)
            true
        }.getOrDefault(false)
    }

    private companion object { const val CHANNEL = "noop_local_summaries" }
}
