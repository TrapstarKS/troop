package com.noop.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores the phone deadline after reboot and re-derives local schedules after clock changes. */
class SmartAlarmBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED -> {
                runCatching {
                    val alarm = SmartAlarmStore.from(context)
                    if (intent.action == Intent.ACTION_TIMEZONE_CHANGED || intent.action == Intent.ACTION_TIME_CHANGED) {
                        if (alarm.enabled) SmartAlarmScheduler.arm(context, alarm)
                    } else SmartAlarmScheduler.rearmPersisted(context, alarm)
                }
                // Recreate the next local plan reminder; AlarmManager schedules are cleared by reboot.
                runCatching {
                    val wind = WindDownStore.from(context)
                    if (wind.enabled || SleepPlannerStore.from(context).read().debtReminderEnabled) {

                        WindDownScheduler.schedule(
                            context, wind, com.noop.ui.NoopPrefs.smartAlarmMinutes(context),
                            com.noop.ui.NoopPrefs.smartAlarmDayOverrides(context),
                        )
                    }
                }
            }
        }
    }
}
