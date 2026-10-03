package com.noop.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.core.content.FileProvider
import com.noop.R
import com.noop.analytics.SkinTempDisplay
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

internal object HealthMonitorReportShare {
    suspend fun export(context: Context, report: HealthMonitorReport, skinKind: SkinTempDisplay.Kind) {
        runCatching {
            val file = withContext(Dispatchers.IO) { render(context, report, skinKind) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.health_report_title))
                clipData = ClipData.newRawUri(context.getString(R.string.health_report_title), uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, context.getString(R.string.health_report_share)))
        }.onFailure {
            if (it is CancellationException) throw it
            Toast.makeText(context, context.getString(R.string.health_report_error), Toast.LENGTH_LONG).show()
        }
    }

    private fun render(context: Context, report: HealthMonitorReport, skinKind: SkinTempDisplay.Kind): File {
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(612, 850, 1).create())
            val canvas = page.canvas
            canvas.drawColor(Palette.surfaceBase.toArgb())
            val margin = Metrics.screenPadding.value
            val width = page.info.pageWidth - margin * 2
            var y = margin
            fun text(value: String, style: TextStyle, color: Int = Palette.textPrimary.toArgb()) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = style.fontSize.value
                    typeface = if ((style.fontWeight?.weight ?: 400) >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    this.color = color
                }
                val lineHeight = paint.fontSpacing + Metrics.space4.value
                var remaining = value
                while (remaining.isNotEmpty()) {
                    val fit = paint.breakText(remaining, true, width, null).coerceAtLeast(1)
                    val breakAt = if (fit < remaining.length) remaining.lastIndexOf(' ', fit).takeIf { it > 0 } ?: fit else fit
                    val line = remaining.take(breakAt)
                    y += lineHeight
                    canvas.drawText(line, margin, y, paint)
                    remaining = remaining.drop(breakAt).trimStart()
                }
            }
            text(context.getString(R.string.health_report_title), NoopType.title1)
            text(context.getString(R.string.health_report_window, report.start, report.end), NoopType.caption,
                Palette.textSecondary.toArgb())
            y += Metrics.space16.value
            val fahrenheit = UnitPrefs.temperature(context) == TemperatureUnit.FAHRENHEIT
            report.rows.forEach { row ->
                text(context.getString(monitorShortLabel(row.key)), NoopType.headline, Palette.statusPositive.toArgb())
                val unit = when (row.key) {
                    "hrv" -> "ms"
                    "rhr" -> "bpm"
                    "resp" -> context.getString(R.string.health_monitor_resp_unit)
                    "spo2" -> "%"
                    else -> SkinTempDisplay.unitSymbol(skinKind, fahrenheit)
                }
                fun format(value: Double?): String = value?.let {
                    if (row.key == "skin") SkinTempDisplay.numberString(it, skinKind, fahrenheit, decimals = 1)
                    else String.format(Locale.getDefault(), "%.1f", it)
                } ?: "—"
                text(context.getString(R.string.health_report_summary, format(row.mean), format(row.minimum),
                    format(row.maximum), unit, row.nights), NoopType.caption, Palette.textSecondary.toArgb())
                y += Metrics.space12.value
            }
            y += Metrics.space12.value
            text(context.getString(if (skinKind == SkinTempDisplay.Kind.ABSOLUTE) R.string.health_report_temp_absolute else R.string.health_report_temp_delta),
                NoopType.footnote, Palette.textSecondary.toArgb())
            text(context.getString(R.string.health_report_sources), NoopType.footnote, Palette.textSecondary.toArgb())
            text(context.getString(R.string.health_report_exclusions), NoopType.footnote, Palette.textSecondary.toArgb())
            text(context.getString(R.string.health_monitor_wellness_note), NoopType.footnote, Palette.textTertiary.toArgb())
            document.finishPage(page)
            val directory = File(context.cacheDir, "reports").apply { mkdirs() }
            val file = File(directory, "NOOP-health-${report.start}_to_${report.end}-${UUID.randomUUID()}.pdf")
            file.outputStream().use { document.writeTo(it) }
            return file
        } finally {
            document.close()
        }
    }
}
