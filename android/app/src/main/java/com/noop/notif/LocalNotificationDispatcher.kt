package com.noop.notif

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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

fun localNotificationLaunchIntent(context: Context, route: String): Intent =
    appLaunchIntent(context).putExtra(LOCAL_NOTIFICATION_ROUTE, route)

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

    fun evaluate(snapshot: LocalNotificationSnapshot?, connected: Boolean, worn: Boolean, nowSec: Long) {
        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochSecond(nowSec).atZone(zone)
        val today = now.toLocalDate()
        if (connected) {
            hasConnected = true
            disconnectedSince = null
        } else if (hasConnected && disconnectedSince == null) disconnectedSince = nowSec
        if (connected && !worn) {
            if (offWristSince == null) offWristSince = nowSec
        } else offWristSince = null
        disconnectedSince?.takeIf { nowSec - it >= 300 }?.let {
            deliver(LocalNotificationFamily.DISCONNECTED, today.toString(), it + 300, nowSec,
                context.getString(R.string.local_notify_disconnect), context.getString(R.string.local_notify_disconnect_body))
        }
        offWristSince?.takeIf { nowSec - it >= 1800 }?.let {
            deliver(LocalNotificationFamily.WEAR, today.toString(), it + 1800, nowSec,
                context.getString(R.string.local_notify_wear), context.getString(R.string.local_notify_wear_body))
        }
        if (snapshot != null && !snapshot.syncPending) {
            val summary = LocalBriefingCopy.summary(context, snapshot.recovery, snapshot.sleepMinutes,
                snapshot.strainTenths, snapshot.streak)
            snapshot.wakeSec?.let { wake ->
                if (snapshot.sleepMinutes != null) {
                    deliver(LocalNotificationFamily.SLEEP_READY, snapshot.day, wake, nowSec,
                        context.getString(R.string.local_notify_sleep), summary)
                }
                if (snapshot.recovery != null) deliver(LocalNotificationFamily.RECOVERY_READY, snapshot.day, wake,
                    nowSec, context.getString(R.string.local_notify_recovery), summary)
                if (snapshot.recovery != null || snapshot.sleepMinutes != null) {
                    deliver(LocalNotificationFamily.MORNING_RECAP, snapshot.day, wake, nowSec,
                        context.getString(R.string.local_notify_morning), summary)
                    deliver(LocalNotificationFamily.DAILY_OUTLOOK, snapshot.day, wake, nowSec,
                        context.getString(R.string.local_outlook), summary)
                }
            }
            if (snapshot.day == today.toString() && now.toLocalTime() >= LocalTime.of(20, 0)) {
                val evening = today.atTime(20, 0).atZone(zone).toEpochSecond()
                if (snapshot.strainTenths != null) deliver(LocalNotificationFamily.STRAIN_READY, snapshot.day,
                    evening, nowSec, context.getString(R.string.local_notify_strain), summary)
                if (snapshot.recovery != null || snapshot.sleepMinutes != null || snapshot.strainTenths != null)
                    deliver(LocalNotificationFamily.DAY_IN_REVIEW, snapshot.day, evening, nowSec,
                        context.getString(R.string.local_review), summary)
                if (snapshot.streak > 0) deliver(LocalNotificationFamily.STREAK, snapshot.day, evening, nowSec,
                    context.getString(R.string.local_notify_streak), summary)
            }
        }
        if (now.toLocalTime() < LocalTime.of(17, 0)) return
        val plan = weeklyPlanProvider?.content(today) ?: return
        val occurrence = today.atTime(17, 0).atZone(zone).toEpochSecond()
        if (today.dayOfWeek == DayOfWeek.FRIDAY) plan.fridayCheckIn?.let {
            deliver(LocalNotificationFamily.FRIDAY_CHECK_IN, plan.weekKey, occurrence, nowSec,
                context.getString(R.string.local_notify_friday), it)
        }
        if (today.dayOfWeek == DayOfWeek.MONDAY) plan.mondayRecap?.let {
            deliver(LocalNotificationFamily.MONDAY_RECAP, plan.weekKey, occurrence, nowSec,
                context.getString(R.string.local_notify_monday), it)
        }
    }

    @SuppressLint("MissingPermission")
    private fun deliver(family: LocalNotificationFamily, day: String, occurrence: Long, now: Long, title: String, body: String) {
        val key = "${family.key}:$day"
        val lastKey = LocalNotificationPrefs.lastEventKey(context, family)
            ?: if (family == LocalNotificationFamily.MORNING_RECAP)
                NoopPrefs.reportMorningDay(context)?.let { "${family.key}:$it" } else null
        val minute = Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault()).let { it.hour * 60 + it.minute }
        val manager = NotificationManagerCompat.from(context)
        if (!LocalNotificationPolicy.shouldDeliver(LocalNotificationPrefs.enabled(context, family),
                manager.areNotificationsEnabled(), LocalNotificationPrefs.quiet(context, minute), key, lastKey,
                occurrence, now)) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val system = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                system.createNotificationChannel(NotificationChannel(CHANNEL,
                    context.getString(R.string.local_summary), NotificationManager.IMPORTANCE_LOW))
                if (system.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
            }
            val route = when (family) {
                LocalNotificationFamily.DISCONNECTED, LocalNotificationFamily.WEAR -> "devices"
                LocalNotificationFamily.FRIDAY_CHECK_IN, LocalNotificationFamily.MONDAY_RECAP -> "weekly_plan"
                else -> "local_briefing"
            }
            val open = PendingIntent.getActivity(context, 4220 + family.ordinal, localNotificationLaunchIntent(context, route),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            manager.notify(4220 + family.ordinal, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_heart).setContentTitle(title).setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(open)
                .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_LOW).build())
            LocalNotificationPrefs.setLastEventKey(context, family, key)
            if (family == LocalNotificationFamily.MORNING_RECAP) NoopPrefs.setReportMorningDay(context, day)
        }
    }

    private companion object { const val CHANNEL = "noop_local_summaries" }
}
