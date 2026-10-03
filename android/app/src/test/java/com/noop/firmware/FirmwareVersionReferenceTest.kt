package com.noop.firmware

import org.junit.Assert.assertEquals
import org.junit.Test

class FirmwareVersionReferenceTest {
    @Test
    fun `only strict comparable numeric versions yield a local comparison`() {
        assertEquals(FirmwareReferenceComparison.OLDER, FirmwareVersionReference.compare("50.2.0.0", "50.10.0.0"))
        assertEquals(FirmwareReferenceComparison.NEWER, FirmwareVersionReference.compare("41.17.6.0", "41.17.4.0"))
        assertEquals(FirmwareReferenceComparison.EQUAL, FirmwareVersionReference.compare("050.02.0.00", "50.2.00.0"))
        assertEquals(FirmwareReferenceComparison.EQUAL, FirmwareVersionReference.compare("50.9223372036854775807", "50.9223372036854775807"))
        val invalid = listOf(null, "", " ", "50", " 50.2", "50.2 ", "50..2", ".50.2", "50.2.", "50.+2", "50.-2", "50.２", "v50.2", "50.9223372036854775808")
        for (value in invalid) {
            assertEquals(value, FirmwareReferenceComparison.UNKNOWN, FirmwareVersionReference.compare(value, "50.2"))
            assertEquals(value, FirmwareReferenceComparison.UNKNOWN, FirmwareVersionReference.compare("50.2", value))
        }
        assertEquals(FirmwareReferenceComparison.UNKNOWN, FirmwareVersionReference.compare("41.2", "50.2"))
        assertEquals(FirmwareReferenceComparison.UNKNOWN, FirmwareVersionReference.compare("50.2", "50.2.0"))
    }
}
