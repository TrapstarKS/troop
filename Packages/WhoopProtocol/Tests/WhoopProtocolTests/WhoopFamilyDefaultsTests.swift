import XCTest
@testable import WhoopProtocol

final class WhoopFamilyDefaultsTests: XCTestCase {
    func testPolicyMatchesPinnedOracle() {
        XCTAssertEqual(projection(), Self.oracle)
    }

    private func projection() -> String {
        var rows: [String] = []
        let models: [String?] = [nil, "", "WHOOP", "4.0", "WHOOP 4.0", "whoop 4.0", "5.0", "5.0 MG", "WHOOP 5.0", "WHOOP 5.0 MG", "WHOOP 5.0 / MG", "MG", "WHOOP MG", "whoop5", "Oura Ring Gen3", " 4.0"]
        let brands: [String?] = [nil, "WHOOP", "Oura"]
        for (m, model) in models.enumerated() {
            for (b, brand) in brands.enumerated() {
                let family = WhoopFamilyDefaults.family(model: model, brand: brand)
                rows.append("identity:\(m):\(b)=\(family == .whoop4 ? 4 : family == .whoop5 ? 5 : 0)")
            }
        }
        let ids: [String?] = [nil, "", "whoop-a", "whoop-b"]
        let families: [DeviceFamily?] = [nil, .whoop4, .whoop5]
        for (i, id) in ids.enumerated() {
            for (f, family) in families.enumerated() {
                for applied in [false, true] {
                    let result = WhoopFamilyDefaults.shouldApply(activeStrapId: id, family: family,
                        appliedStrapIds: applied ? ["whoop-a"] : [])
                    rows.append("apply:\(i):\(f):\(applied ? 1 : 0)=\(result ? 1 : 0)")
                }
            }
        }
        for exists in [false, true] {
            for touched in [false, true] {
                rows.append("default:\(exists ? 1 : 0):\(touched ? 1 : 0)=\(WhoopFamilyDefaults.shouldEnable(valueExists: exists, userTouched: touched) ? 1 : 0)")
            }
        }
        for preference in [false, true] {
            for (f, family) in families.enumerated() {
                rows.append("effective:\(preference ? 1 : 0):\(f)=\(WhoopFamilyDefaults.captureEnabled(preference: preference, family: family) ? 1 : 0)")
            }
        }
        rows.append("keys=\(WhoopFamilyDefaults.captureKey)|\(WhoopFamilyDefaults.appliedStrapIdsKey)|\(WhoopFamilyDefaults.activeFamilyKey)|\(WhoopFamilyDefaults.touchedKey(for: WhoopFamilyDefaults.captureKey))")
        return rows.joined(separator: "\n")
    }

    // Optimized standalone Swift stdout; the Kotlin twin pins the same output.
    private static let oracle = """
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
    """
}
