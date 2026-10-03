package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.noop.R
import com.noop.firmware.FirmwareReferenceComparison
import com.noop.firmware.FirmwareVersionReference

@Composable
internal fun FirmwareGuidanceRow(reportedVersion: String?, isCurrentConnection: Boolean) {
    var showHelp by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
        Text(
            when {
                reportedVersion == null -> uiString(R.string.firmware_not_reported)
                isCurrentConnection -> uiString(R.string.firmware_reported, reportedVersion)
                else -> uiString(R.string.firmware_last_reported, reportedVersion)
            },
            style = NoopType.footnote,
            color = Palette.textTertiary,
        )
        TextButton(onClick = { showHelp = true }) {
            Text(uiString(R.string.firmware_guidance_title), style = NoopType.subhead, color = Palette.accent)
        }
    }
    if (showHelp) FirmwareGuidanceDialog(reportedVersion) { showHelp = false }
}

@Composable
private fun FirmwareGuidanceDialog(reportedVersion: String?, onClose: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var reference by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Palette.surfaceRaised,
        title = { Text(uiString(R.string.firmware_guidance_title), style = NoopType.headline, color = Palette.textPrimary) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = Metrics.dialogScrollableMaxHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Metrics.space12),
            ) {
                for (key in listOf(R.string.firmware_guidance_intro, R.string.firmware_guidance_handoff,
                    R.string.firmware_guidance_steps, R.string.firmware_guidance_return)) {
                    Text(uiString(key), style = NoopType.subhead, color = Palette.textSecondary)
                }
                TextButton(onClick = {
                    uriHandler.openUri("https://support.whoop.com/s/article/WHOOP-3-0-and-4-0-How-to-Update-Your-Product-s-Firmware")
                }) {
                    Text(uiString(R.string.firmware_guide_link), style = NoopType.subhead, color = Palette.accent)
                }
                OutlinedTextField(
                    value = reference,
                    onValueChange = { reference = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(uiString(R.string.firmware_reference_label), style = NoopType.footnote, color = Palette.textSecondary) },
                    singleLine = true,
                    textStyle = NoopType.subhead.copy(color = Palette.textPrimary),
                    colors = devicesFieldColors(),
                )
                Text(uiString(R.string.firmware_reference_detail), style = NoopType.footnote, color = Palette.textTertiary)
                if (reference.isNotEmpty()) {
                    val comparison = FirmwareVersionReference.compare(reportedVersion, reference)
                    Text(
                        uiString(when (comparison) {
                            FirmwareReferenceComparison.OLDER -> R.string.firmware_reference_older
                            FirmwareReferenceComparison.EQUAL -> R.string.firmware_reference_equal
                            FirmwareReferenceComparison.NEWER -> R.string.firmware_reference_newer
                            FirmwareReferenceComparison.UNKNOWN -> R.string.firmware_reference_unknown
                        }),
                        style = NoopType.footnote,
                        color = Palette.textSecondary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text(uiString(R.string.l10n_devices_screen_close_bbfa773e), style = NoopType.subhead, color = Palette.accent)
            }
        },
    )
}
