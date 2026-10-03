package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.noop.R
import com.noop.firmware.FirmwareEvent
import com.noop.firmware.FirmwareFailure
import com.noop.firmware.FirmwarePhase
import com.noop.firmware.FirmwareSimulation

/** Standalone manual mock: the composition root deliberately accepts no device or application model. */
@Composable
internal fun FirmwareSimulationCard() {
    var enabled by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(FirmwareSimulation()) }
    fun send(event: FirmwareEvent) { state = state.applying(event) }
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Text(uiString(R.string.firmware_sim_title), style = NoopType.headline, color = Palette.textPrimary)
            Text(uiString(R.string.firmware_sim_banner), style = NoopType.footnote, color = Palette.statusWarning)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                Text(uiString(R.string.firmware_sim_enable), style = NoopType.subhead, color = Palette.textSecondary, modifier = Modifier.weight(1f))
                Switch(checked = enabled, colors = settingsSwitchColors(), onCheckedChange = {
                    enabled = it
                    if (!it) send(FirmwareEvent.Reset)
                })
            }
            if (enabled) {
                Text(uiString(phaseText(state.phase)), style = NoopType.subhead, color = Palette.textPrimary)
                if (state.paused) Text(uiString(R.string.workout_action_paused), style = NoopType.footnote, color = Palette.textSecondary)
                state.failure?.let {
                    Text(uiString(failureText(it)), style = NoopType.footnote, color = Palette.statusWarning)
                }
                if (state.phase == FirmwarePhase.DOWNLOAD || state.phase == FirmwarePhase.TRANSFER) {
                    Text(uiString(R.string.firmware_sim_progress, state.progress), style = NoopType.footnote, color = Palette.textSecondary)
                    LinearProgressIndicator(progress = state.progress / 100f, modifier = Modifier.fillMaxWidth(), color = Palette.accent, trackColor = Palette.surfaceRaised)
                }
                if (state.phase.isActive) {
                    NoopButton(
                        text = uiString(R.string.firmware_sim_next), fullWidth = true, enabled = !state.paused,
                        onClick = {
                            if ((state.phase == FirmwarePhase.DOWNLOAD || state.phase == FirmwarePhase.TRANSFER) && state.progress < 100) {
                                send(FirmwareEvent.Progress(state.runId, state.phase, state.progress + 25))
                            } else send(FirmwareEvent.Advance(state.runId, state.phase))
                        },
                    )
                    NoopButton(
                        text = uiString(if (state.paused) R.string.workout_action_resume else R.string.workout_action_pause),
                        kind = NoopButtonKind.Secondary, fullWidth = true,
                        onClick = { send(if (state.paused) FirmwareEvent.Resume(state.runId) else FirmwareEvent.Pause(state.runId)) },
                    )
                    NoopButton(
                        text = uiString(R.string.firmware_sim_fail), kind = NoopButtonKind.Secondary, fullWidth = true,
                        onClick = {
                            val reason = when (state.phase) {
                                FirmwarePhase.CHECK -> FirmwareFailure.PREREQUISITES
                                FirmwarePhase.VERIFY -> FirmwareFailure.VERIFICATION
                                else -> FirmwareFailure.MOCK_FAILURE
                            }
                            send(FirmwareEvent.Fail(state.runId, state.phase, reason))
                        },
                    )
                } else {
                    if (state.checkpoint != null) NoopButton(
                        text = uiString(R.string.workout_action_resume), fullWidth = true,
                        onClick = { send(FirmwareEvent.Resume(state.runId)) },
                    )
                    NoopButton(text = uiString(R.string.firmware_sim_start), kind = NoopButtonKind.Secondary, fullWidth = true,
                        onClick = { send(FirmwareEvent.Start) })
                }
                if (state.phase.isActive || state.phase == FirmwarePhase.FAILED) NoopButton(
                    text = uiString(R.string.l10n_devices_screen_cancel_77dfd213), kind = NoopButtonKind.Secondary, fullWidth = true,
                    onClick = { send(FirmwareEvent.Cancel(state.runId)) },
                )
                if (state.phase != FirmwarePhase.IDLE) NoopButton(
                    text = uiString(R.string.timeline_reset), kind = NoopButtonKind.Secondary, fullWidth = true,
                    onClick = { send(FirmwareEvent.Reset) },
                )
            }
        }
    }
}

private fun phaseText(phase: FirmwarePhase): Int = when (phase) {
    FirmwarePhase.IDLE -> R.string.firmware_sim_idle
    FirmwarePhase.CHECK -> R.string.firmware_sim_check
    FirmwarePhase.DOWNLOAD -> R.string.firmware_sim_download
    FirmwarePhase.VERIFY -> R.string.firmware_sim_verify
    FirmwarePhase.TRANSFER -> R.string.firmware_sim_transfer
    FirmwarePhase.REBOOT -> R.string.firmware_sim_reboot
    FirmwarePhase.DONE -> R.string.firmware_sim_done
    FirmwarePhase.FAILED -> R.string.firmware_sim_failed
    FirmwarePhase.CANCELLED -> R.string.firmware_sim_cancelled
}

private fun failureText(failure: FirmwareFailure): Int = when (failure) {
    FirmwareFailure.PREREQUISITES -> R.string.firmware_sim_prerequisites_failure
    FirmwareFailure.VERIFICATION -> R.string.firmware_sim_verification_failure
    FirmwareFailure.TIMEOUT -> R.string.firmware_sim_timeout_failure
    FirmwareFailure.DISCONNECTED -> R.string.firmware_sim_disconnected_failure
    FirmwareFailure.MOCK_FAILURE -> R.string.firmware_sim_mock_failure
}
