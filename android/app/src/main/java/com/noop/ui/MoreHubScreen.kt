package com.noop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.ai.AiKeyStore

@Composable
fun MoreHubScreen(
    vm: AppViewModel,
    onNavigate: (String) -> Unit,
    onOpenSettings: (SettingsCategory) -> Unit = { onNavigate("settings") },
) {
    val context = LocalContext.current
    val streaks by vm.streaks.collectAsStateWithLifecycle()
    var allFeaturesOpen by remember { mutableStateOf(false) }
    ScreenScaffold(title = uiString(R.string.nav_more), subtitle = uiString(R.string.more_private)) {
        NoopCard {
            Row(modifier = Modifier.fillMaxWidth().clickable { onOpenSettings(SettingsCategory.PROFILE) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                ProfileAvatar(size = Metrics.iconButton + Metrics.screenPadding,
                    contentDescription = uiString(R.string.more_profile))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.selectorSpacing)) {
                    Text(uiString(R.string.more_profile), style = NoopType.title2, color = Palette.textPrimary)
                    Text(androidx.compose.ui.res.pluralStringResource(R.plurals.settings_streak_run,
                        streaks.current, streaks.current), style = NoopType.footnote, color = Palette.textSecondary)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Palette.textTertiary)
            }
        }
        MoreHubSection(uiString(R.string.nav_devices)) {
            MoreHubRow(uiString(R.string.nav_devices), Icons.Filled.Sensors) { onNavigate("devices") }
            MoreHubRow(uiString(R.string.nav_live), Icons.Filled.Sensors) { onNavigate("live") }
            MoreHubRow(uiString(R.string.nav_power_saving), Icons.Filled.Settings) { onNavigate("power_saving") }
        }
        MoreHubSection(uiString(R.string.more_app)) {
            MoreHubRow(uiString(R.string.nav_settings), Icons.Filled.Settings) { onNavigate("settings") }
            MoreHubRow(uiString(R.string.settings_scores), Icons.Filled.Settings) { onOpenSettings(SettingsCategory.SCORES) }
            MoreHubRow(uiString(R.string.nav_automations), Icons.Filled.Settings) { onNavigate("automations") }
        }
        MoreHubSection(uiString(R.string.nav_notifications)) {
            MoreHubRow(uiString(R.string.nav_notifications), Icons.Filled.Notifications) { onNavigate("local_notifications") }
            MoreHubRow(uiString(R.string.nav_alarms), Icons.Filled.Notifications) { onNavigate("smart_alarm") }
        }
        MoreHubSection(uiString(R.string.more_integrations)) {
            MoreHubRow(uiString(R.string.l10n_data_sources_screen_health_connect_be6bca3e), Icons.Filled.Storage) { onNavigate("data_sources") }
            MoreHubRow(uiString(R.string.nav_apple_health), Icons.Filled.Storage) { onNavigate("data_sources") }
            MoreHubRow(uiString(R.string.nav_data_sources), Icons.Filled.Storage) { onNavigate("data_sources") }
            MoreHubRow(uiString(R.string.coach_settings), Icons.Filled.AutoAwesome) { onNavigate("coach_settings") }
            MoreHubRow(uiString(R.string.nav_coach), Icons.Filled.AutoAwesome) {
                onNavigate(if (AiKeyStore.hasKey(context)) "coach" else "local_briefing")
            }
        }
        MoreHubSection(uiString(R.string.more_data)) {
            MoreHubRow(uiString(R.string.nav_backup_sync), Icons.Filled.Storage) { onNavigate("backup_sync") }
            MoreHubRow(uiString(R.string.nav_fused_record), Icons.Filled.Storage) { onNavigate("fused_record") }
            MoreHubRow(uiString(R.string.more_data), Icons.Filled.Storage) { onOpenSettings(SettingsCategory.DATA) }
        }
        MoreHubSection(uiString(R.string.more_help)) {
            MoreHubRow(uiString(R.string.nav_noop_limitations), Icons.Filled.Info) { onNavigate("noop_limitations") }
            MoreHubRow(uiString(R.string.l10n_settings_screen_test_centre_37b36828), Icons.Filled.Info) { onNavigate("test_centre") }
            MoreHubRow(uiString(R.string.l10n_settings_screen_about_6b21fb79), Icons.Filled.Info) { onOpenSettings(SettingsCategory.HELP) }
        }
        MoreHubSection(uiString(R.string.more_all)) {
            MoreHubRow(uiString(R.string.more_all), Icons.Filled.Settings) { allFeaturesOpen = !allFeaturesOpen }
            if (allFeaturesOpen) drawerGroups.flatMap { it.items }.distinctBy { it.route }.forEach { destination ->
                MoreHubRow(uiString(destination.titleRes), destination.icon) {
                    onNavigate(if (destination.route == "coach" && !AiKeyStore.hasKey(context)) "local_briefing" else destination.route)
                }
            }
        }
    }
}

@Composable
internal fun MoreHubSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.selectorSpacing)) {
        Overline(title)
        NoopCard { Column { content() } }
    }
}

@Composable
internal fun MoreHubRow(title: String, icon: ImageVector = Icons.Filled.AccountCircle, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.iconButton + Metrics.gap)
        .clickable(onClick = onClick).padding(vertical = Metrics.selectorSpacing),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        Icon(icon, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(Metrics.iconSmall))
        Text(title, style = NoopType.body, color = Palette.textPrimary, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = Palette.textTertiary, modifier = Modifier.size(Metrics.iconSmall))
    }
}
