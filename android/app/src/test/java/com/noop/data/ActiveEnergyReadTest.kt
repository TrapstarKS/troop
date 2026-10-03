package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveEnergyReadTest {
    private fun row(total: Double? = 2345.0, active: Double? = null) = DailyMetric(
        deviceId = "my-whoop-noop", day = "2026-10-01", recovery = 72.0,
        activeKcalEst = total, activeEnergyKcalEst = active,
    )

    @Test fun activeFacadeNeverFallsBackToLegacyTotal() {
        assertNull(WhoopRepository.dailyColumn("active_kcal", row()))
        assertEquals(2345.0, WhoopRepository.dailyColumn("energy_kcal", row())!!, 0.0)
        assertEquals(456.0, WhoopRepository.dailyColumn("active_kcal", row(active = 456.0))!!, 0.0)
        assertEquals(0.0, WhoopRepository.dailyColumn("active_kcal", row(active = 0.0))!!, 0.0)
    }

    @Test fun coalescingPreservesActiveIndependently() {
        val merged = WhoopRepository.coalesceDay(row(total = 2000.0), row(active = 456.0))
        assertEquals(2000.0, merged.activeKcalEst!!, 0.0)
        assertEquals(456.0, merged.activeEnergyKcalEst!!, 0.0)
        assertEquals(72.0, merged.recovery!!, 0.0)
        assertEquals(0.0, WhoopRepository.coalesceDay(row(active = 0.0), row(active = 456.0)).activeEnergyKcalEst!!, 0.0)
    }
}
