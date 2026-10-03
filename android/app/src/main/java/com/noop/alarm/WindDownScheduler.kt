package com.noop.alarm

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.noop.R
import com.noop.ui.appLaunchIntent
import java.util.Calendar

/**
 * The wind-down nudge (#207) — a gentle, NON-safety-critical evening local notification.
 *
 * An inexact one-shot local reminder derived from the same per-day plan as the screen.
 * The receiver schedules the following occurrence after delivery or quiet-hours suppression.
 *
 * The fired notification is low-key (default importance, no full-screen, no DND bypass) — it's a
 * suggestion, not an alarm.
 */
object WindDownScheduler {

    private const val REQUEST_CODE = 7311
    const val ACTION_NUDGE = "com.noop.alarm.action.WIND_DOWN_NUDGE"
    const val CHANNEL_ID = "noop_wind_down"
    private const val NOTIF_ID = 4311

    /**
     * Replaces the next reminder with the earliest eligible plan occurrence. Stable request and
     * occurrence keys keep edits, restarts and receiver retries from stacking reminders.
     */
    fun schedule(
        context: Context,
        store: WindDownStore,
        wakeMinutes: Int,
        perDayWake: Map<Int, Int> = emptyMap(),
    ) {
        cancel(context)
        if (!notificationsAllowed(context)) return
        val settings = SleepPlannerStore.from(context).read()
        val weekdays = if (com.noop.ui.NoopPrefs.smartAlarmEnabled(context)) com.noop.ui.NoopPrefs.smartAlarmWeekdays(context) else emptySet()
        val reminder = nextPlannerReminder(
            now = Calendar.getInstance(),
            settings = settings,
            weekdays = weekdays,
            wakeMinutes = wakeMinutes,
            perDayWake = perDayWake,
            leadMinutes = store.leadMinutes,
            debtOnly = !store.enabled,
        ) ?: return
        com.noop.ui.NoopPrefs.of(context).edit()
            .putString("windDown.pendingOccurrence", reminder.occurrenceKey)
            .putLong("windDown.pendingReminderMs", reminder.at.timeInMillis).apply()
        val intent = Intent(context, WindDownReceiver::class.java).setAction(ACTION_NUDGE)
            .putExtra("occurrence", reminder.occurrenceKey)
            .putExtra("reminderMs", reminder.at.timeInMillis)
            .putExtra("wakeMs", reminder.wakeAt.timeInMillis)
            .putExtra("debtNudge", settings.debtReminderEnabled && reminder.plan.debtNudge)
            .putExtra("bedtimeMs", reminder.bedtimeAt.timeInMillis)
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.set(AlarmManager.RTC, reminder.at.timeInMillis, pending)
    }

    internal data class PlannerReminder(
        val at: Calendar,
        val occurrenceKey: String,
        val plan: com.noop.analytics.SleepPlan,
        val wakeAt: Calendar,
        val bedtimeAt: Calendar,
    )

    internal fun nextPlannerReminder(
        now: Calendar,
        settings: SleepPlannerSettings,
        weekdays: Set<Int>,
        wakeMinutes: Int,
        perDayWake: Map<Int, Int>,
        leadMinutes: Int,
        debtOnly: Boolean = false,
    ): PlannerReminder? {
        var next: PlannerReminder? = null
        for (offset in 0..14) {
            val day = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
            val weekday = day.get(Calendar.DAY_OF_WEEK)
            if (weekdays.isNotEmpty() && weekday !in weekdays) continue
            val requestedMinute = perDayWake[weekday] ?: wakeMinutes
            val wake = com.noop.analytics.SleepPlanner.wakeDate(requestedMinute, day)
            val minute = wake.get(Calendar.HOUR_OF_DAY) * 60 + wake.get(Calendar.MINUTE)
            val key = com.noop.analytics.PlannerAlarmPolicy.occurrenceKey(
                wake.get(Calendar.YEAR), wake.get(Calendar.MONTH) + 1, wake.get(Calendar.DAY_OF_MONTH), minute,
            )
            if (key == settings.skippedOccurrence) continue
            val plan = settings.plan(weekday, minute, leadMinutes)
            if (debtOnly && (!settings.debtReminderEnabled || !plan.debtNudge)) continue
            val bedtime = com.noop.analytics.SleepPlanner.bedtime(wake, plan.targetSleepMinutes)
            val reminder = (bedtime.clone() as Calendar).apply {
                timeInMillis = bedtime.timeInMillis - leadMinutes.coerceIn(0, 120) * 60_000L
            }
            if (reminder.timeInMillis <= now.timeInMillis) continue
            if (next == null || reminder.timeInMillis < next.at.timeInMillis) {
                next = PlannerReminder(reminder, key, plan, wake, bedtime)
            }
        }
        return next
    }

    /** Cancel every shape the nudge can be scheduled in — the single daily one AND all seven weekday
     *  pins. Unconditional on purpose: the caller does not always know which shape is live, and a
     *  cancel that misses one leaves a reminder firing at a time the user has already changed. */
    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(nudgePendingIntent(context))
        for (weekday in 1..7) am.cancel(nudgePendingIntent(context, weekday))
    }

    /** Raise the low-key nudge notification. Called from [WindDownReceiver]. */
    fun fireNotification(context: Context, intent: Intent) {
        val prefs = com.noop.ui.NoopPrefs.of(context)
        val key = intent.getStringExtra("occurrence") ?: return
        val settings = SleepPlannerStore.from(context).read()
        val debtNudge = intent.getBooleanExtra("debtNudge", false) && settings.debtReminderEnabled
        if ((!WindDownStore.from(context).enabled && !debtNudge) || key != prefs.getString("windDown.pendingOccurrence", "")) return
        if (intent.getLongExtra("reminderMs", 0) != prefs.getLong("windDown.pendingReminderMs", 0)) return
        if (key == prefs.getString("windDown.lastDeliveredOccurrence", "")) return
        if (!notificationsAllowed(context)) return
        val now = Calendar.getInstance()
        if (now.timeInMillis >= intent.getLongExtra("wakeMs", 0L)) return
        val scheduled = Calendar.getInstance().apply { timeInMillis = intent.getLongExtra("reminderMs", 0L) }
        if (quietAt(context, scheduled.get(Calendar.HOUR_OF_DAY) * 60 + scheduled.get(Calendar.MINUTE))) return
        if (quietAt(context, now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE))) return
        ensureChannel(context)
        runCatching {
            val open = PendingIntent.getActivity(
                context, 0, appLaunchIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_heart)
                .setContentTitle(context.getString(R.string.sleep_planner_reminder_title))
                .setContentText(context.getString(
                    if (debtNudge) R.string.sleep_planner_debt_notification else R.string.sleep_planner_reminder_notification,
                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                        .format(java.util.Date(intent.getLongExtra("bedtimeMs", 0))),
                ))
                .setContentIntent(open)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .build()
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, n)
            prefs.edit().putString("windDown.lastDeliveredOccurrence", key).apply()
        }
    }

    fun notificationsAllowed(context: Context): Boolean {
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun quietAt(context: Context, minute: Int): Boolean {
        if (!com.noop.ui.NotifPrefs.getBool(context, com.noop.ui.NotifPrefs.QUIET, false)) return false
        val start = com.noop.ui.NotifPrefs.getInt(context, com.noop.ui.NotifPrefs.QUIET_START, 1320)
        val end = com.noop.ui.NotifPrefs.getInt(context, com.noop.ui.NotifPrefs.QUIET_END, 420)
        return com.noop.analytics.PlannerAlarmPolicy.isQuietMinute(minute, true, start, end)
    }

    /** [weekday] null = the single daily nudge; 1..7 = that weekday's own pin, on its own request code
     *  so the seven do not collide with each other or with the daily one. */
    private fun nudgePendingIntent(context: Context, weekday: Int? = null): PendingIntent {
        val intent = Intent(context, WindDownReceiver::class.java).setAction(ACTION_NUDGE)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE + (weekday ?: 0), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Wind-down nudge", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "An optional evening reminder to start winding down before bed."
                    setShowBadge(false)
                },
            )
        }
    }

    /**
     * Move a `Calendar.DAY_OF_WEEK` by [shift] days, wrapping through the week end (1=Sun…7=Sat).
     *
     * [shift] is normally 0 or -1 — an early wake puts its wind-down on the previous evening — but the
     * arithmetic is general so a long sleep-need plus lead cannot produce an out-of-range weekday.
     */
    internal fun shiftWeekday(weekday: Int, shift: Int): Int =
        Math.floorMod(weekday - 1 + shift, 7) + 1

    /**
     * The next time [minuteOfDay] falls on [weekday] (Calendar 1=Sun…7=Sat) — the anchor for a weekly
     * repeat. Walks forward at most seven days, so today counts only if the minute is still ahead.
     *
     * `internal` so the day/time arithmetic is unit-testable without an AlarmManager, which is the whole
     * of what could go wrong here.
     */
    internal fun nextWeeklyOccurrence(
        minuteOfDay: Int,
        weekday: Int,
        now: Calendar = Calendar.getInstance(),
    ): Calendar {
        val cal = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var guard = 0
        while ((cal.get(Calendar.DAY_OF_WEEK) != weekday || cal.timeInMillis <= now.timeInMillis) &&
            guard < 8
        ) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            guard++
        }
        return cal
    }

    private fun nextOccurrence(minuteOfDay: Int): Calendar =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
}

/** Delivers the next plan reminder and schedules the following occurrence. Not exported. */
class WindDownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != WindDownScheduler.ACTION_NUDGE) return
        WindDownScheduler.fireNotification(context, intent)
        val store = WindDownStore.from(context)
        if (store.enabled || SleepPlannerStore.from(context).read().debtReminderEnabled) runCatching {
            WindDownScheduler.schedule(
                context, store, com.noop.ui.NoopPrefs.smartAlarmMinutes(context),
                com.noop.ui.NoopPrefs.smartAlarmDayOverrides(context),
            )
        }
    }
}
