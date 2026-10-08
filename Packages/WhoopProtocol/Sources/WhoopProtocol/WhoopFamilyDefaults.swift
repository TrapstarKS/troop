import Foundation

/// Local defaults only. Strap-write permissions never participate in this policy.
public enum WhoopFamilyDefaults {
    public static let captureKey = "noopPuffinCapture"
    public static let appliedStrapIdsKey = "noop.whoopFamilyDefaults.appliedStrapIds"
    public static let activeFamilyKey = "noop.whoopFamilyDefaults.activeFamily"

    /// Twin of Kotlin `WhoopFamilyDefaults.touchedKey`; the user-choice marker has one shared name.
    public static func touchedKey(for key: String) -> String {
        key + ".userTouched"
    }

    /// Twin of Kotlin `WhoopFamilyDefaults.family`; unknown-model arithmetic is not identity evidence.
    public static func family(model: String?, brand: String?) -> DeviceFamily? {
        guard DeviceFamily.confirmedRegistryFamily(model: model, brand: brand) != nil else { return nil }
        return DeviceFamily.forRegistryModel(model)
    }

    /// Twin of Kotlin `WhoopFamilyDefaults.shouldApply`; the ledger belongs to the registry identity.
    public static func shouldApply(activeStrapId: String?, family: DeviceFamily?,
                                   appliedStrapIds: Set<String>) -> Bool {
        guard let activeStrapId, !activeStrapId.isEmpty, family == .whoop5 else { return false }
        return !appliedStrapIds.contains(activeStrapId)
    }

    /// Twin of Kotlin `WhoopFamilyDefaults.shouldEnable`; a persisted value also counts as a choice.
    public static func shouldEnable(valueExists: Bool, userTouched: Bool) -> Bool {
        !valueExists && !userTouched
    }

    /// Twin of Kotlin `WhoopFamilyDefaults.captureEnabled`; switching families preserves the preference.
    public static func captureEnabled(preference: Bool, family: DeviceFamily?) -> Bool {
        preference && family == .whoop5
    }
}
