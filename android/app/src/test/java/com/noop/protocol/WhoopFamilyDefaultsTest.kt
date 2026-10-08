package com.noop.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class WhoopFamilyDefaultsTest {
    @Test
    fun policyMatchesSwiftOracle() {
        val rows = mutableListOf<String>()
        val models = listOf(null, "", "WHOOP", "4.0", "WHOOP 4.0", "whoop 4.0", "5.0", "5.0 MG",
            "WHOOP 5.0", "WHOOP 5.0 MG", "WHOOP 5.0 / MG", "MG", "WHOOP MG", "whoop5", "Oura Ring Gen3", " 4.0")
        val brands = listOf(null, "WHOOP", "Oura")
        models.forEachIndexed { m, model ->
            brands.forEachIndexed { b, brand ->
                val family = WhoopFamilyDefaults.family(model, brand)
                val number = when (family) { DeviceFamily.WHOOP4 -> 4; DeviceFamily.WHOOP5 -> 5; null -> 0 }
                rows += "identity:$m:$b=$number"
            }
        }
        val ids = listOf(null, "", "whoop-a", "whoop-b")
        val families = listOf(null, DeviceFamily.WHOOP4, DeviceFamily.WHOOP5)
        ids.forEachIndexed { i, id ->
            families.forEachIndexed { f, family ->
                for (applied in listOf(false, true)) {
                    val result = WhoopFamilyDefaults.shouldApply(id, family, if (applied) setOf("whoop-a") else emptySet())
                    rows += "apply:$i:$f:${if (applied) 1 else 0}=${if (result) 1 else 0}"
                }
            }
        }
        for (exists in listOf(false, true)) {
            for (touched in listOf(false, true)) {
                val result = WhoopFamilyDefaults.shouldEnable(exists, touched)
                rows += "default:${if (exists) 1 else 0}:${if (touched) 1 else 0}=${if (result) 1 else 0}"
            }
        }
        for (preference in listOf(false, true)) {
            families.forEachIndexed { f, family ->
                val result = WhoopFamilyDefaults.captureEnabled(preference, family)
                rows += "effective:${if (preference) 1 else 0}:$f=${if (result) 1 else 0}"
            }
        }
        rows += "keys=${WhoopFamilyDefaults.captureKey}|${WhoopFamilyDefaults.appliedStrapIdsKey}|${WhoopFamilyDefaults.activeFamilyKey}|${WhoopFamilyDefaults.touchedKey(WhoopFamilyDefaults.captureKey)}"
        assertEquals(oracle, rows.joinToString("\n"))
    }

    companion object {
        // Verbatim stdout from the standalone optimized Swift twin; pinned by the Swift test too.
        private val oracle = """
            identity:0:0=0
            identity:0:1=0
            identity:0:2=0
            identity:1:0=0
            identity:1:1=0
            identity:1:2=0
            identity:2:0=0
            identity:2:1=0
            identity:2:2=0
            identity:3:0=4
            identity:3:1=4
            identity:3:2=0
            identity:4:0=4
            identity:4:1=4
            identity:4:2=0
            identity:5:0=4
            identity:5:1=4
            identity:5:2=0
            identity:6:0=5
            identity:6:1=5
            identity:6:2=0
            identity:7:0=5
            identity:7:1=5
            identity:7:2=0
            identity:8:0=5
            identity:8:1=5
            identity:8:2=0
            identity:9:0=5
            identity:9:1=5
            identity:9:2=0
            identity:10:0=5
            identity:10:1=5
            identity:10:2=0
            identity:11:0=5
            identity:11:1=5
            identity:11:2=0
            identity:12:0=5
            identity:12:1=5
            identity:12:2=0
            identity:13:0=5
            identity:13:1=5
            identity:13:2=0
            identity:14:0=0
            identity:14:1=0
            identity:14:2=0
            identity:15:0=0
            identity:15:1=0
            identity:15:2=0
            apply:0:0:0=0
            apply:0:0:1=0
            apply:0:1:0=0
            apply:0:1:1=0
            apply:0:2:0=0
            apply:0:2:1=0
            apply:1:0:0=0
            apply:1:0:1=0
            apply:1:1:0=0
            apply:1:1:1=0
            apply:1:2:0=0
            apply:1:2:1=0
            apply:2:0:0=0
            apply:2:0:1=0
            apply:2:1:0=0
            apply:2:1:1=0
            apply:2:2:0=1
            apply:2:2:1=0
            apply:3:0:0=0
            apply:3:0:1=0
            apply:3:1:0=0
            apply:3:1:1=0
            apply:3:2:0=1
            apply:3:2:1=1
            default:0:0=1
            default:0:1=0
            default:1:0=0
            default:1:1=0
            effective:0:0=0
            effective:0:1=0
            effective:0:2=0
            effective:1:0=0
            effective:1:1=0
            effective:1:2=1
            keys=noopPuffinCapture|noop.whoopFamilyDefaults.appliedStrapIds|noop.whoopFamilyDefaults.activeFamily|noopPuffinCapture.userTouched
        """.trimIndent()
    }
}
