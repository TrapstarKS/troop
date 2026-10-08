package com.noop.ui

import com.noop.data.PairedDeviceRow
import com.noop.protocol.Whoop5Variant
import org.junit.Assert.assertEquals
import org.junit.Test

class WhoopOptionalFeaturesCardTest {
    private fun row(id: String = "whoop-MGB12345", model: String = "WHOOP 5.0 / MG",
        brand: String = "WHOOP", address: String? = null) = PairedDeviceRow(
        id, brand, model, null, address, "bleWhoop", "hr", "active", 0, 0,
    )

    @Test
    fun wizardCombinedModelDoesNotAuthorizeMgControls() {
        assertEquals(Whoop5Variant.UNKNOWN, activeWhoopVariant(row(model = "5.0 MG"), Whoop5Variant.UNKNOWN, null))
        assertEquals(Whoop5Variant.UNKNOWN, activeWhoopVariant(row(), Whoop5Variant.UNKNOWN, null))
    }

    @Test
    fun preciseAttestedModelSupportsOfflineMgControls() {
        assertEquals(Whoop5Variant.MG, activeWhoopVariant(row(model = "WHOOP MG"), Whoop5Variant.UNKNOWN, null))
        assertEquals(Whoop5Variant.UNKNOWN, activeWhoopVariant(row(id = "whoop-5AM12345"), Whoop5Variant.UNKNOWN, null))
    }

    @Test
    fun liveAttestationMustBelongToTheActiveRow() {
        val active = row(address = "aa:bb")
        assertEquals(Whoop5Variant.UNKNOWN, activeWhoopVariant(active, Whoop5Variant.MG, "cc:dd"))
        assertEquals(Whoop5Variant.MG, activeWhoopVariant(active, Whoop5Variant.MG, "AA:BB"))
    }

    @Test
    fun liveContradictionCannotFallBackToAnOldMgModelOrSerial() {
        val active = row(id = "whoop-5AM12345", model = "WHOOP MG", address = "aa:bb")
        assertEquals(Whoop5Variant.UNKNOWN, activeWhoopVariant(active, Whoop5Variant.UNKNOWN, "aa:bb"))
        assertEquals(Whoop5Variant.FIVE_ZERO, activeWhoopVariant(active, Whoop5Variant.FIVE_ZERO, "aa:bb"))
    }

    @Test
    fun otherDeviceFamiliesNeverAuthorizeMgControls() {
        assertEquals(Whoop5Variant.UNKNOWN, activeWhoopVariant(row(model = "4.0"), Whoop5Variant.MG, "aa:bb"))
        assertEquals(Whoop5Variant.UNKNOWN, activeWhoopVariant(row(model = "WHOOP MG", brand = "Oura"), Whoop5Variant.MG, null))
    }
}
