package com.noop.ui

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/** Display rounding for observed behavior impacts; does not alter comparison values. */
internal object InsightsImpactFormatting {
    fun percentage(percent: Double): String {
        if (!percent.isFinite()) return "—"
        if (percent == 0.0) return "0%"
        val sign = if (percent > 0) "+" else "−"
        val magnitude = abs(percent)
        val whole = floor(magnitude)
        val rounded = if (magnitude - whole >= 0.5) whole + 1 else whole
        return sign + (if (magnitude < 1) "<1" else String.format(Locale.ROOT, "%.0f", rounded)) + "%"
    }
}
