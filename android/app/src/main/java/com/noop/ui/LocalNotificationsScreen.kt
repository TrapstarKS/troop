package com.noop.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.ai.AiKeyStore
import com.noop.notif.LocalNotificationFamily
import com.noop.notif.LocalNotificationPrefs

@Composable
fun LocalNotificationsScreen(
    vm: AppViewModel,
    onOpenWristAlerts: () -> Unit,
    onOpenAutomations: () -> Unit,
    onOpenAlarms: () -> Unit,
    onOpenCoachSettings: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var authorized by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var revision by remember { mutableStateOf(0) }
    val illness by vm.illnessWatchEnabled.collectAsStateWithLifecycle()
    val battery by vm.batteryAlertsEnabled.collectAsStateWithLifecycle()
    val predictive by vm.predictiveBatteryAlertsEnabled.collectAsStateWithLifecycle()
    val strapAlarm by vm.smartAlarmEnabled.collectAsStateWithLifecycle()
    val phoneAlarm by vm.phoneAlarmEnabled.collectAsStateWithLifecycle()
    val windDown by vm.windDownEnabled.collectAsStateWithLifecycle()
    fun change(block: () -> Unit) { block(); revision++ }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                authorized = NotificationManagerCompat.from(context).areNotificationsEnabled()
                revision++
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    ScreenScaffold(title = uiString(R.string.nav_notifications), subtitle = uiString(R.string.local_notify_intro)) {
        @Suppress("UNUSED_VARIABLE") val refresh = revision
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                Text(uiString(if (authorized) R.string.local_notify_authorized else R.string.local_notify_blocked),
                    style = NoopType.body, color = if (authorized) Palette.textSecondary else Palette.statusWarning)
                NoopButton(text = uiString(R.string.local_notify_system), kind = NoopButtonKind.Secondary,
                    fullWidth = true, onClick = {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    })
                LocalNotificationToggle(uiString(R.string.l10n_notifications_settings_screen_quiet_hours_706b24d0),
                    NotifPrefs.getBool(context, NotifPrefs.QUIET, false)) {
                    change { NotifPrefs.setBool(context, NotifPrefs.QUIET, it) }
                }
                Text(uiString(R.string.local_quiet_disabled), style = NoopType.footnote, color = Palette.textSecondary)
                if (NotifPrefs.getBool(context, NotifPrefs.QUIET, false)) Row(
                    horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    TimeChip(NotifPrefs.getInt(context, NotifPrefs.QUIET_START, 22 * 60),
                        uiString(R.string.local_quiet_start)) { value ->
                        change { NotifPrefs.setInt(context, NotifPrefs.QUIET_START, value) }
                    }
                    TimeChip(NotifPrefs.getInt(context, NotifPrefs.QUIET_END, 7 * 60),
                        uiString(R.string.local_quiet_end)) { value ->
                        change { NotifPrefs.setInt(context, NotifPrefs.QUIET_END, value) }
                    }
                }
            }
        }
        MoreHubSection(uiString(R.string.local_notify_ready_group)) {
            listOf(LocalNotificationFamily.RECOVERY_READY, LocalNotificationFamily.SLEEP_READY,
                LocalNotificationFamily.STRAIN_READY, LocalNotificationFamily.MORNING_RECAP,
                LocalNotificationFamily.DAILY_OUTLOOK, LocalNotificationFamily.DAY_IN_REVIEW,
                LocalNotificationFamily.STREAK).forEach { family ->
                LocalNotificationToggle(uiString(family.titleRes), LocalNotificationPrefs.enabled(context, family)) {
                    change { LocalNotificationPrefs.setEnabled(context, family, it) }
                }
            }
            LocalNotificationToggle(uiString(R.string.l10n_notifications_settings_screen_post_workout_summary_13e488f5),
                NoopPrefs.postWorkoutReportEnabled(context)) {
                change {
                    if (it) vm.seedWorkoutReportFrontier()
                    NoopPrefs.setPostWorkoutReportEnabled(context, it)
                }
            }
            LocalNotificationToggle(uiString(R.string.l10n_notifications_settings_screen_optimal_strain_reached_2862ec2b),
                NoopPrefs.strainTargetEnabled(context)) { change { NoopPrefs.setStrainTargetEnabled(context, it) } }
        }
        MoreHubSection(uiString(R.string.local_notify_device_group)) {
            Text(uiString(R.string.local_notify_battery_detail), style = NoopType.footnote, color = Palette.textSecondary)
            LocalNotificationToggle(uiString(R.string.l10n_automations_screen_notify_on_low_and_full_battery_d1903bb8),
                battery, onChange = vm::setBatteryAlertsEnabled)
            LocalNotificationToggle(uiString(R.string.l10n_automations_screen_predictive_runtime_warning_4d85f5a6),
                predictive, enabled = battery, onChange = vm::setPredictiveBatteryAlertsEnabled)
            LocalNotificationToggle(uiString(R.string.local_notify_charging_attention),
                false, enabled = false, onChange = {})
            Text(uiString(R.string.local_notify_charging_unavailable), style = NoopType.footnote, color = Palette.textSecondary)
            listOf(LocalNotificationFamily.DISCONNECTED, LocalNotificationFamily.WEAR).forEach { family ->
                LocalNotificationToggle(uiString(family.titleRes), LocalNotificationPrefs.enabled(context, family)) {
                    change { LocalNotificationPrefs.setEnabled(context, family, it) }
                }
            }
        }
        MoreHubSection(uiString(R.string.local_notify_sleep_group)) {
            LocalNotificationToggle(uiString(R.string.l10n_smart_alarm_screen_strap_wake_alarm_1828fff3),
                strapAlarm, enabled = false, onChange = {})
            LocalNotificationToggle(uiString(R.string.l10n_smart_alarm_screen_wake_alarm_37af3ecf), phoneAlarm) {
                if (!vm.setPhoneAlarmEnabled(it)) onOpenAlarms()
            }
            LocalNotificationToggle(uiString(R.string.l10n_smart_alarm_screen_wind_down_nudge_5ca87a0f),
                windDown, onChange = vm::setWindDownEnabled)
            LocalNotificationToggle(uiString(R.string.l10n_sleep_screen_sleep_debt_3aec7d9c),
                NoopPrefs.of(context).getInt("sleepPlanner.debtReminderEnabled", 0) == 1) {
                change { NoopPrefs.of(context).edit().putInt("sleepPlanner.debtReminderEnabled", if (it) 1 else 0).apply() }
            }
            MoreHubRow(uiString(R.string.nav_alarms), onClick = onOpenAlarms)
        }
        MoreHubSection(uiString(R.string.local_notify_health)) {
            LocalNotificationToggle(uiString(R.string.l10n_automations_screen_watch_for_early_illness_signs_4c22e127),
                illness, onChange = vm::setIllnessWatchEnabled)
            LocalNotificationToggle(uiString(R.string.l10n_automations_screen_inactivity_reminder_ca49b1ba),
                InactivityPrefs.enabled(context)) { change { InactivityPrefs.setBool(context, InactivityPrefs.ENABLED, it) } }
            LocalNotificationToggle(uiString(R.string.l10n_settings_screen_stress_check_ins_haptic_bf2746ba),
                BiofeedbackPrefs.checkInEnabled(context)) {
                change {
                    BiofeedbackPrefs.setCheckInEnabled(context, it)
                    if (!it) BiofeedbackPrefs.setAutoNudge(context, false)
                }
            }
            LocalNotificationToggle(uiString(R.string.l10n_settings_screen_offer_a_breath_automatically_6c709dee),
                BiofeedbackPrefs.autoNudge(context), enabled = BiofeedbackPrefs.checkInEnabled(context)) {
                change { BiofeedbackPrefs.setAutoNudge(context, it) }
            }
            MoreHubRow(uiString(R.string.nav_automations), onClick = onOpenAutomations)
        }
        MoreHubSection(uiString(R.string.local_notify_plan_group)) {
            Text(uiString(R.string.local_notify_plan_detail), style = NoopType.footnote, color = Palette.textSecondary)
            listOf(LocalNotificationFamily.FRIDAY_CHECK_IN, LocalNotificationFamily.MONDAY_RECAP).forEach { family ->
                LocalNotificationToggle(uiString(family.titleRes), LocalNotificationPrefs.enabled(context, family)) {
                    change { LocalNotificationPrefs.setEnabled(context, family, it) }
                }
            }
        }
        MoreHubSection(uiString(R.string.nav_coach)) {
            Text(uiString(R.string.local_notify_provider_detail), style = NoopType.footnote, color = Palette.textSecondary)
            LocalNotificationToggle(uiString(R.string.coach_morning_brief), CoachBriefSettings.from(context).enabled,
                enabled = NoopPrefs.coachEnabled(context) && AiKeyStore.hasKey(context) && AiKeyStore.readConsent(context)) {
                change {
                    CoachBriefSettings.from(context).enabled = it
                    CoachBriefScheduler.reschedule(context)
                }
            }
            MoreHubRow(uiString(R.string.coach_settings), onClick = onOpenCoachSettings)
        }
        MoreHubSection(uiString(R.string.l10n_notifications_settings_screen_wrist_alerts_75581d51)) {
            LocalNotificationToggle(uiString(R.string.l10n_notifications_settings_screen_enable_wrist_alerts_462b9e0f),
                NotifPrefs.getBool(context, NotifPrefs.MASTER, false)) {
                change { NotifPrefs.setBool(context, NotifPrefs.MASTER, it) }
            }
            MoreHubRow(uiString(R.string.l10n_notifications_settings_screen_wrist_alerts_75581d51), onClick = onOpenWristAlerts)
        }
    }
}

private val LocalNotificationFamily.titleRes: Int get() = when (this) {
    LocalNotificationFamily.RECOVERY_READY -> R.string.local_notify_recovery
    LocalNotificationFamily.SLEEP_READY -> R.string.local_notify_sleep
    LocalNotificationFamily.STRAIN_READY -> R.string.local_notify_strain
    LocalNotificationFamily.MORNING_RECAP -> R.string.local_notify_morning
    LocalNotificationFamily.DAILY_OUTLOOK -> R.string.local_outlook
    LocalNotificationFamily.DAY_IN_REVIEW -> R.string.local_review
    LocalNotificationFamily.STREAK -> R.string.local_notify_streak
    LocalNotificationFamily.DISCONNECTED -> R.string.local_notify_disconnect
    LocalNotificationFamily.WEAR -> R.string.local_notify_wear
    LocalNotificationFamily.FRIDAY_CHECK_IN -> R.string.local_notify_friday
    LocalNotificationFamily.MONDAY_RECAP -> R.string.local_notify_monday
}

@Composable
private fun LocalNotificationToggle(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.iconButton + Metrics.gap)
        .padding(vertical = Metrics.selectorSpacing), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        Text(title, style = NoopType.body, color = Palette.textPrimary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = title },
            colors = SwitchDefaults.colors(checkedThumbColor = Palette.surfaceBase,
                checkedTrackColor = Palette.accent, uncheckedThumbColor = Palette.textSecondary,
                uncheckedTrackColor = Palette.surfaceInset, uncheckedBorderColor = Palette.hairline))
    }
}
