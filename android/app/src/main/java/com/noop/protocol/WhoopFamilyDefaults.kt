package com.noop.protocol

/** Local defaults only; strap-write permissions never participate in this policy. */
object WhoopFamilyDefaults {
    const val captureKey = "noopPuffinCapture"
    const val appliedStrapIdsKey = "noop.whoopFamilyDefaults.appliedStrapIds"
    const val activeFamilyKey = "noop.whoopFamilyDefaults.activeFamily"

    fun touchedKey(key: String): String = "$key.userTouched"

    fun family(model: String?, brand: String?): DeviceFamily? {
        if (DeviceFamily.confirmedRegistryFamily(model, brand) == null) return null
        return DeviceFamily.forRegistryModel(model)
    }

    fun shouldApply(activeStrapId: String?, family: DeviceFamily?, appliedStrapIds: Set<String>): Boolean =
        !activeStrapId.isNullOrEmpty() && family == DeviceFamily.WHOOP5 && activeStrapId !in appliedStrapIds

    /** A persisted value predating touched markers also counts as a choice, including OFF. */
    fun shouldEnable(valueExists: Boolean, userTouched: Boolean): Boolean = !valueExists && !userTouched

    fun captureEnabled(preference: Boolean, family: DeviceFamily?): Boolean =
        preference && family == DeviceFamily.WHOOP5
}
