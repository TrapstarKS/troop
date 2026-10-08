package com.noop.ble

import android.content.SharedPreferences
import com.noop.protocol.WhoopFamilyDefaults
import org.junit.Assert.*
import org.junit.Test

class PuffinExperimentDefaultsTest {
    @Test
    fun launchAndRepeatedRegistryEmissionsApplyOnlyPassiveCapture() {
        val prefs = FakeSharedPreferences()
        val experiments = PuffinExperiment(prefs, FakeSharedPreferences())
        experiments.applyFamilyDefaults("whoop-b", "5.0", "WHOOP")
        experiments.applyFamilyDefaults("whoop-b", "5.0", "WHOOP")
        experiments.applyFamilyDefaults("whoop-a", "WHOOP MG", "WHOOP")
        assertTrue(experiments.isCaptureEnabled)
        assertEquals("[\"whoop-a\",\"whoop-b\"]", prefs.getString(WhoopFamilyDefaults.appliedStrapIdsKey, null))
        assertFalse(prefs.contains(WhoopFamilyDefaults.touchedKey(PuffinExperiment.KEY_CAPTURE)))
        assertTrue(PuffinExperiment.FIVE_MG_GATED_KEYS.none { prefs.contains(it) })
    }

    @Test
    fun existingOffChoiceMigratesBeforeAutomaticDefaults() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putBoolean(PuffinExperiment.LEGACY_KEY_CAPTURE, false).apply()
        val experiments = PuffinExperiment(prefs, FakeSharedPreferences())
        experiments.applyFamilyDefaults("whoop-a", "WHOOP 5.0 / MG", "WHOOP")
        assertTrue(prefs.contains(PuffinExperiment.KEY_CAPTURE))
        assertFalse(experiments.isCaptureEnabled)
    }

    @Test
    fun canonicalValueWinsOverLegacyValue() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putBoolean(PuffinExperiment.LEGACY_KEY_CAPTURE, true)
            .putBoolean(PuffinExperiment.KEY_CAPTURE, false).apply()
        val experiments = PuffinExperiment(prefs, FakeSharedPreferences())
        experiments.applyFamilyDefaults("whoop-a", "WHOOP 5.0 / MG", "WHOOP")
        assertFalse(experiments.isCaptureEnabled)
    }

    @Test
    fun manualOffIsKeptAcrossLaunchAndDifferentStraps() {
        val prefs = FakeSharedPreferences()
        val experiments = PuffinExperiment(prefs, FakeSharedPreferences())
        experiments.applyFamilyDefaults("whoop-a", "5.0", "WHOOP")
        experiments.isCaptureEnabled = false
        val relaunched = PuffinExperiment(prefs, FakeSharedPreferences())
        relaunched.applyFamilyDefaults("whoop-b", "5.0", "WHOOP")
        assertFalse(relaunched.isCaptureEnabled)
        assertTrue(prefs.getBoolean(WhoopFamilyDefaults.touchedKey(PuffinExperiment.KEY_CAPTURE), false))
    }

    @Test
    fun touchedChoiceStillWinsIfTheValueWasRemoved() {
        val prefs = FakeSharedPreferences()
        val experiments = PuffinExperiment(prefs, FakeSharedPreferences())
        experiments.isCaptureEnabled = false
        prefs.edit().remove(PuffinExperiment.KEY_CAPTURE).apply()
        experiments.applyFamilyDefaults("whoop-a", "5.0", "WHOOP")
        assertFalse(prefs.contains(PuffinExperiment.KEY_CAPTURE))
        assertFalse(experiments.isCaptureEnabled)
    }

    @Test
    fun familySwitchDisablesCaptureWithoutErasingItsChoice() {
        val prefs = FakeSharedPreferences()
        val experiments = PuffinExperiment(prefs, FakeSharedPreferences())
        experiments.applyFamilyDefaults("whoop-a", "5.0", "WHOOP")
        experiments.applyFamilyDefaults("whoop-four", "4.0", "WHOOP")
        experiments.resetFiveMGGatedProbes()
        assertFalse(experiments.isCaptureEnabled)
        assertTrue(prefs.getBoolean(PuffinExperiment.KEY_CAPTURE, false))
        experiments.applyFamilyDefaults("whoop-a", "5.0", "WHOOP")
        assertTrue(experiments.isCaptureEnabled)
    }

    @Test
    fun unconfirmedAndNonWhoopModelsNeverApplyDefaults() {
        val prefs = FakeSharedPreferences()
        val experiments = PuffinExperiment(prefs, FakeSharedPreferences())
        experiments.applyFamilyDefaults("my-whoop", "WHOOP", "WHOOP")
        experiments.applyFamilyDefaults("ring", "5.0", "Oura")
        experiments.applyFamilyDefaults(null, "5.0", "WHOOP")
        assertFalse(prefs.contains(PuffinExperiment.KEY_CAPTURE))
        assertFalse(prefs.contains(WhoopFamilyDefaults.appliedStrapIdsKey))
        experiments.applyFamilyDefaults("my-whoop", "WHOOP MG", "WHOOP")
        assertTrue(experiments.isCaptureEnabled)
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val map = HashMap<String, Any?>()
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            map[key] as? MutableSet<String> ?: defValues
        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = FakeEditor()

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private val removed = HashSet<String>()
            override fun putString(key: String, value: String?): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor { pending[key] = values; return this }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
            override fun remove(key: String): SharedPreferences.Editor { removed.add(key); return this }
            override fun clear(): SharedPreferences.Editor { map.clear(); return this }
            override fun commit(): Boolean { flush(); return true }
            override fun apply() { flush() }
            private fun flush() {
                for (k in removed) map.remove(k)
                map.putAll(pending)
                pending.clear(); removed.clear()
            }
        }
    }
}
