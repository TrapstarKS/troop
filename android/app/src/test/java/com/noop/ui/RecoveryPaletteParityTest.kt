package com.noop.ui

import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryPaletteParityTest {
    @Test fun `Classic retains its ramp and Titanium uses exact 34 and 67 boundaries`() {
        val context = RuntimeEnvironment.getApplication<android.app.Application>()
        val previous = ChartStylePrefs.style
        try {
            for (style in listOf(ChartStyle.CLASSIC, ChartStyle.TITANIUM)) {
                ChartStylePrefs.set(context, style)
                for (score in listOf(-10.0, 0.0, 33.99, 34.0, 66.99, 67.0, 100.0, 110.0)) {
                    val expected = if (style == ChartStyle.CLASSIC) Palette.sample(Palette.recoveryStops, (score / 100).toFloat())
                        else when {
                            score < 34 -> Palette.recoveryLow
                            score < 67 -> Palette.recoveryMedium
                            else -> Palette.recoveryHigh
                        }
                    assertEquals(expected, Palette.recoveryColor(score))
                }
                for (score in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                    assertEquals(Palette.textTertiary, Palette.recoveryColor(score))
                }
            }
        } finally {
            ChartStylePrefs.set(context, previous)
        }
    }
}
