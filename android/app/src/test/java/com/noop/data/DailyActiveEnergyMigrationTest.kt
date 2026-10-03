package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyActiveEnergyMigrationTest {
    @Test
    fun migrationAddsOnlyANullableActiveColumnAndNeverBackfillsTotals() {
        val sql = WhoopDatabase.DAILY_ACTIVE_ENERGY_MIGRATION_SQL
        assertEquals(listOf("ALTER TABLE `dailyMetric` ADD COLUMN `activeEnergyKcalEst` REAL"), sql)
        val upper = sql.single().uppercase()
        assertTrue(!upper.contains("NOT NULL") && !upper.contains("DEFAULT"))
        for (banned in listOf("DROP ", "DELETE ", "UPDATE ", "INSERT ", "RENAME ")) {
            assertTrue("migration must not contain $banned", !upper.contains(banned))
        }
        assertEquals(41, WhoopDatabase.MIGRATION_41_42.startVersion)
        assertEquals(42, WhoopDatabase.MIGRATION_41_42.endVersion)
    }

    @Test
    fun oldRowsKeepTotalAndActiveRemainsIndependentAndUnknown() {
        val old = DailyMetric(deviceId = "my-whoop-noop", day = "2026-10-01", activeKcalEst = 2345.0, recovery = 72.0)
        assertNull(old.activeEnergyKcalEst)
        val updated = old.copy(activeEnergyKcalEst = 456.0)
        assertEquals(456.0, updated.activeEnergyKcalEst!!, 0.0)
        assertEquals(2345.0, updated.activeKcalEst!!, 0.0)
        assertEquals(72.0, updated.recovery!!, 0.0)
    }
}
