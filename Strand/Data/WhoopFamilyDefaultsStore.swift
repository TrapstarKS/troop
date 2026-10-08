import Foundation
import WhoopProtocol

enum WhoopFamilyDefaultsStore {
    static func captureEnabled(defaults: UserDefaults = .standard) -> Bool {
        let family: DeviceFamily? = defaults.integer(forKey: WhoopFamilyDefaults.activeFamilyKey) == 5 ? .whoop5 : nil
        return WhoopFamilyDefaults.captureEnabled(
            preference: defaults.bool(forKey: WhoopFamilyDefaults.captureKey), family: family)
    }

    static func apply(activeStrapId: String?, family: DeviceFamily?, defaults: UserDefaults = .standard) {
        let activeFamily: Int
        switch family {
        case .whoop4: activeFamily = 4
        case .whoop5: activeFamily = 5
        case nil: activeFamily = 0
        }
        defaults.set(activeFamily, forKey: WhoopFamilyDefaults.activeFamilyKey)

        let stored = defaults.string(forKey: WhoopFamilyDefaults.appliedStrapIdsKey) ?? "[]"
        let data = Data(stored.utf8)
        var applied = Set((try? JSONDecoder().decode([String].self, from: data)) ?? [])
        guard WhoopFamilyDefaults.shouldApply(activeStrapId: activeStrapId, family: family,
                                             appliedStrapIds: applied), let activeStrapId else { return }
        let key = WhoopFamilyDefaults.captureKey
        if WhoopFamilyDefaults.shouldEnable(valueExists: defaults.object(forKey: key) != nil,
                                            userTouched: defaults.bool(forKey: WhoopFamilyDefaults.touchedKey(for: key))) {
            defaults.set(true, forKey: key)
        }
        applied.insert(activeStrapId)
        if let encoded = try? JSONEncoder().encode(applied.sorted()),
           let json = String(data: encoded, encoding: .utf8) {
            defaults.set(json, forKey: WhoopFamilyDefaults.appliedStrapIdsKey)
        }
    }

    static func setCapture(_ enabled: Bool, defaults: UserDefaults = .standard) {
        defaults.set(true, forKey: WhoopFamilyDefaults.touchedKey(for: WhoopFamilyDefaults.captureKey))
        defaults.set(enabled, forKey: WhoopFamilyDefaults.captureKey)
    }
}
