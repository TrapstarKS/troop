package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.noop.R
import com.noop.data.HrBucket

internal fun sleepHeartRateRuns(buckets: List<HrBucket>, from: Long?, to: Long?): List<List<HrBucket>> {
    if (from == null || to == null || to <= from) return emptyList()
    val runs = mutableListOf<MutableList<HrBucket>>()
    for (bucket in buckets.filter { it.avgBpm.isFinite() && it.bucket in from..to }.sortedBy { it.bucket }) {
        if (runs.isEmpty() || bucket.bucket - runs.last().last().bucket > 300L) runs.add(mutableListOf())
        runs.last().add(bucket)
    }
    return runs
}

@Composable
internal fun SleepHeartRateCard(buckets: List<HrBucket>, from: Long?, to: Long?) {
    val runs = sleepHeartRateRuns(buckets, from, to)
    val points = runs.flatten()
    val title = stringResource(R.string.l10n_sleep_screen_sleep_heart_rate_chart_8ec47ae1)
    val is24h = ClockPrefs.uses24Hour(LocalContext.current)
    NoopCard(tint = Palette.sleepPrimary) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Text(title, style = NoopType.overline, color = Palette.textSecondary)
            if (points.size >= 2 && from != null && to != null) {
                val low = points.minOf { it.avgBpm } - 5.0
                val high = points.maxOf { it.avgBpm } + 5.0
                val color = Palette.sleepPrimary
                Canvas(Modifier.fillMaxWidth().height(Metrics.compactChartHeight)
                    .semantics { contentDescription = title }) {
                    fun position(point: HrBucket) = Offset(
                        ((point.bucket - from).toDouble() / (to - from) * size.width).toFloat(),
                        ((1.0 - (point.avgBpm - low) / (high - low)) * size.height).toFloat(),
                    )
                    val width = WhoopChartStyle.lineWidth.toPx()
                    for (run in runs) {
                        val path = Path()
                        run.forEachIndexed { index, point ->
                            val offset = position(point)
                            if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
                            if (run.size == 1) drawCircle(color, width / 2, offset)
                        }
                        drawPath(path, color, style = Stroke(width = width))
                    }
                }
            } else {
                Text(stringResource(R.string.l10n_apple_health_screen_no_readings_recorded_05018b26),
                    style = NoopType.footnote, color = Palette.textTertiary)
            }
            if (from != null && to != null && to > from) {
                Row(Modifier.fillMaxWidth()) {
                    Text(clockTimeLabel(from, is24h), style = NoopType.caption, color = Palette.textTertiary)
                    Spacer(Modifier.weight(1f))
                    Text(clockTimeLabel(to, is24h), style = NoopType.caption, color = Palette.textTertiary)
                }
            }
        }
    }
}
