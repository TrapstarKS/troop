package com.noop.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.ble.PuffinExperiment
import com.noop.ble.WhoopBleClient
import com.noop.data.PairedDeviceRow
import com.noop.protocol.DeviceFamily
import com.noop.protocol.Whoop5Variant
import com.noop.protocol.WhoopFamilyDefaults
import kotlinx.coroutines.launch

internal fun activeWhoopVariant(row: PairedDeviceRow?, attestation: Whoop5Variant,
    connectedAddress: String?): Whoop5Variant {
    if (WhoopFamilyDefaults.family(row?.model, row?.brand) != DeviceFamily.WHOOP5) return Whoop5Variant.UNKNOWN
    if (connectedAddress != null &&
        row?.peripheralId?.equals(connectedAddress, ignoreCase = true) == true) {
        return attestation
    }
    val model = row?.model?.trim()?.uppercase()
    if (model == "WHOOP MG" || model == "MG") return Whoop5Variant.MG
    if (model == "WHOOP 5.0" || model == "5.0") return Whoop5Variant.FIVE_ZERO
    return Whoop5Variant.UNKNOWN
}

@Composable
internal fun detectedWhoopModel(vm: AppViewModel): String {
    val row by vm.activeRegistryDevice.collectAsStateWithLifecycle()
    val variant by vm.ble.whoop5VariantFlow.collectAsStateWithLifecycle()
    val address by vm.ble.connectedPeripheralAddress.collectAsStateWithLifecycle()
    return when (WhoopFamilyDefaults.family(row?.model, row?.brand)) {
        DeviceFamily.WHOOP4 -> "WHOOP 4.0"
        DeviceFamily.WHOOP5 -> when (activeWhoopVariant(row, variant, address)) {
            Whoop5Variant.MG -> "WHOOP MG"
            Whoop5Variant.FIVE_ZERO -> "WHOOP 5.0"
            Whoop5Variant.UNKNOWN -> stringResource(R.string.whoop_model_unconfirmed_variant)
        }
        null -> stringResource(R.string.whoop_model_unidentified)
    }
}

private enum class StrapChange {
    PROBES, EXPLICIT_BOND, UNBONDED_OFFLOAD, CLEAR_STALE_BOND,
    BROADCAST_ON, BROADCAST_OFF, R22_ON, R22_OFF, ECG_GATE_ON, ECG_GATE_OFF,
    ECG_START, ECG_STOP,
}

@Composable
internal fun WhoopOptionalFeaturesCard(vm: AppViewModel, onOpenGroundTruthCollector: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val row by vm.activeRegistryDevice.collectAsStateWithLifecycle()
    val family = WhoopFamilyDefaults.family(row?.model, row?.brand)
    val live by vm.live.collectAsStateWithLifecycle()
    val variant by vm.ble.whoop5VariantFlow.collectAsStateWithLifecycle()
    val address by vm.ble.connectedPeripheralAddress.collectAsStateWithLifecycle()
    val isMG = activeWhoopVariant(row, variant, address).isMG
    val experiments = remember { PuffinExperiment.from(context) }
    var revision by remember { mutableStateOf(0) }
    var pending by remember(row?.id, family, isMG) { mutableStateOf<StrapChange?>(null) }
    var shareBusy by remember { mutableStateOf(false) }
    var ecgMayBeRunning by remember(row?.id) { mutableStateOf(vm.ble.ecgMayBeRunning) }
    val r22Report by vm.ble.r22DisableReport.collectAsStateWithLifecycle()
    val ecgReport by vm.ble.ecgRawDataGate.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        val prefs = context.getSharedPreferences(PuffinExperiment.PREFS, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val probes = remember(revision) { experiments.isEnabled }
    val explicitBond = remember(revision) { experiments.explicitBond }
    val unbondedOffload = remember(revision) { experiments.unbondedOffload }
    val clearStaleBond = remember(revision) { experiments.clearStaleBond }
    val broadcast = remember(revision) { experiments.broadcastHr }
    val capture = remember(revision, family) { experiments.isCaptureEnabled && family == DeviceFamily.WHOOP5 }
    val ownsLink = address != null && row?.peripheralId?.equals(address, ignoreCase = true) == true
    val canWrite = live.encryptedBond && live.whoop5Detected && ownsLink

    if (family != DeviceFamily.WHOOP5) return

    SettingsCard(Icons.Filled.Science, stringResource(R.string.whoop_optional_features_title),
        stringResource(R.string.whoop_optional_features_detail)) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            SettingsToggleRow(stringResource(R.string.raw_diag_protocol_probes),
                stringResource(R.string.whoop_protocol_probes_consent), probes) {
                if (it) pending = StrapChange.PROBES else experiments.isEnabled = false
            }
            SettingsToggleRow(stringResource(R.string.raw_diag_broadcast_hr),
                stringResource(R.string.whoop_broadcast_reconnect), broadcast) {
                pending = if (it) StrapChange.BROADCAST_ON else StrapChange.BROADCAST_OFF
            }
            SettingsToggleRow(stringResource(R.string.raw_diag_pair),
                stringResource(R.string.whoop_explicit_bond_consent), explicitBond) {
                if (it) pending = StrapChange.EXPLICIT_BOND else experiments.explicitBond = false
            }
            SettingsToggleRow(stringResource(R.string.raw_diag_unbonded_offload),
                stringResource(R.string.whoop_unbonded_offload_consent), unbondedOffload) {
                if (it) pending = StrapChange.UNBONDED_OFFLOAD else experiments.unbondedOffload = false
            }
            SettingsToggleRow(stringResource(R.string.raw_diag_clear_stale_bond),
                stringResource(R.string.whoop_clear_stale_bond_consent), clearStaleBond) {
                if (it) pending = StrapChange.CLEAR_STALE_BOND else experiments.clearStaleBond = false
            }
            Text(stringResource(R.string.raw_diag_r22), style = NoopType.subhead, color = Palette.textPrimary)
            Text(stringResource(R.string.whoop_r22_detail), style = NoopType.caption, color = Palette.textTertiary)
            NoopButton(stringResource(R.string.raw_diag_r22_enable), kind = NoopButtonKind.Secondary,
                fullWidth = true, enabled = canWrite && live.worn,
                onClick = { pending = StrapChange.R22_ON })
            NoopButton(stringResource(R.string.raw_diag_r22_clear), kind = NoopButtonKind.Secondary,
                fullWidth = true, enabled = canWrite && r22Report != WhoopBleClient.WAITING_DEVICE_CONFIG_PROBE,
                onClick = { pending = StrapChange.R22_OFF })
            r22Report?.let { Text(it, style = NoopType.caption, color = Palette.textSecondary) }
            if (isMG) {
                Text(stringResource(R.string.raw_diag_ecg), style = NoopType.subhead, color = Palette.textPrimary)
                Text(stringResource(R.string.whoop_ecg_research_detail), style = NoopType.caption, color = Palette.textTertiary)
                NoopButton(stringResource(R.string.raw_diag_ecg_on), kind = NoopButtonKind.Secondary,
                    fullWidth = true, enabled = canWrite, onClick = { pending = StrapChange.ECG_GATE_ON })
                NoopButton(stringResource(R.string.raw_diag_ecg_off), kind = NoopButtonKind.Secondary,
                    fullWidth = true, enabled = canWrite, onClick = { pending = StrapChange.ECG_GATE_OFF })
                ecgReport?.let { Text(it.summary, style = NoopType.caption, color = Palette.textSecondary) }
                NoopButton(stringResource(R.string.raw_diag_ecg_probe_start), kind = NoopButtonKind.Secondary,
                    fullWidth = true, enabled = canWrite && !ecgMayBeRunning,
                    onClick = { pending = StrapChange.ECG_START })
                NoopButton(stringResource(R.string.raw_diag_ecg_probe_stop), kind = NoopButtonKind.Secondary,
                    fullWidth = true, enabled = canWrite, onClick = { pending = StrapChange.ECG_STOP })
                if (ecgMayBeRunning) Text(stringResource(R.string.raw_diag_ecg_probe_running),
                    style = NoopType.caption, color = Palette.textSecondary)
            }
            SettingsToggleRow(stringResource(R.string.raw_diag_passive),
                stringResource(R.string.whoop_passive_capture_detail), capture) { experiments.isCaptureEnabled = it }
            NoopButton(stringResource(R.string.ground_truth_open), kind = NoopButtonKind.Secondary,
                fullWidth = true, onClick = onOpenGroundTruthCollector)
            NoopButton(stringResource(R.string.raw_diag_share), kind = NoopButtonKind.Secondary,
                fullWidth = true, enabled = !shareBusy, onClick = {
                    shareBusy = true
                    scope.launch {
                        try { LogExport.shareWhoop5Capture(context, live.whoop5Detected, live.encryptedBond) }
                        finally { shareBusy = false }
                    }
                })
            NoopButton(stringResource(R.string.raw_diag_export_log), kind = NoopButtonKind.Secondary,
                fullWidth = true, enabled = !shareBusy, onClick = {
                    shareBusy = true
                    scope.launch {
                        try { LogExport.shareRawAndLog(context, vm.ble.exportLogText(), live.whoop5Detected, live.encryptedBond) }
                        finally { shareBusy = false }
                    }
                })
        }
    }
    pending?.let { change ->
        val message = when (change) {
            StrapChange.PROBES -> stringResource(R.string.whoop_protocol_probes_consent)
            StrapChange.EXPLICIT_BOND -> stringResource(R.string.whoop_explicit_bond_consent)
            StrapChange.UNBONDED_OFFLOAD -> stringResource(R.string.whoop_unbonded_offload_consent)
            StrapChange.CLEAR_STALE_BOND -> stringResource(R.string.whoop_clear_stale_bond_consent)
            StrapChange.BROADCAST_ON, StrapChange.BROADCAST_OFF -> stringResource(R.string.whoop_broadcast_reconnect)
            StrapChange.R22_ON -> stringResource(R.string.whoop_r22_detail)
            StrapChange.R22_OFF -> stringResource(R.string.l10n_settings_screen_r22disable_confirm_body)
            StrapChange.ECG_GATE_ON, StrapChange.ECG_GATE_OFF -> stringResource(R.string.l10n_settings_screen_ecg_gate_persistent_warning)
            StrapChange.ECG_START -> stringResource(R.string.whoop_ecg_start_consent)
            StrapChange.ECG_STOP -> stringResource(R.string.whoop_ecg_stop_consent)
        }
        val localConsent = change in setOf(StrapChange.PROBES, StrapChange.EXPLICIT_BOND,
            StrapChange.UNBONDED_OFFLOAD, StrapChange.CLEAR_STALE_BOND)
        AlertDialog(onDismissRequest = { pending = null }, containerColor = Palette.surfaceOverlay,
            title = { Text(stringResource(R.string.whoop_apply_strap_change), style = NoopType.title2,
                color = Palette.textPrimary) },
            text = { Text(message, style = NoopType.body, color = Palette.textSecondary) },
            confirmButton = {
                TextButton(enabled = localConsent || canWrite || change in setOf(StrapChange.BROADCAST_ON, StrapChange.BROADCAST_OFF),
                    onClick = {
                        pending = null
                        when (change) {
                            StrapChange.PROBES -> experiments.isEnabled = true
                            StrapChange.EXPLICIT_BOND -> experiments.explicitBond = true
                            StrapChange.UNBONDED_OFFLOAD -> experiments.unbondedOffload = true
                            StrapChange.CLEAR_STALE_BOND -> experiments.clearStaleBond = true
                            StrapChange.BROADCAST_ON, StrapChange.BROADCAST_OFF -> {
                                val enabled = change == StrapChange.BROADCAST_ON
                                experiments.broadcastHr = enabled
                                if (canWrite) vm.ble.setBroadcastHr(enabled)
                            }
                            StrapChange.R22_ON -> { experiments.isDeepDataEnabled = true; vm.ble.enableWhoop5DeepData() }
                            StrapChange.R22_OFF -> { vm.ble.disableWhoop5DeepData(); experiments.isDeepDataEnabled = false }
                            StrapChange.ECG_GATE_ON, StrapChange.ECG_GATE_OFF -> {
                                experiments.ecgRawData = true
                                vm.ble.setEcgRawDataGate(change == StrapChange.ECG_GATE_ON)
                            }
                            StrapChange.ECG_START -> { experiments.ecgEnabled = true; vm.ble.ecgStartCapture(); ecgMayBeRunning = vm.ble.ecgMayBeRunning }
                            StrapChange.ECG_STOP -> { vm.ble.ecgStopCapture(); ecgMayBeRunning = vm.ble.ecgMayBeRunning }
                        }
                    }) { Text(stringResource(if (localConsent) R.string.whoop_enable else R.string.whoop_apply), color = Palette.accent) }
            }, dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text(stringResource(R.string.ground_truth_cancel), color = Palette.textSecondary)
                }
            })
    }
}
