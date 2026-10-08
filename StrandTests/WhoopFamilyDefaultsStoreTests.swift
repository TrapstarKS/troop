import XCTest
import WhoopProtocol
@testable import Strand

final class WhoopFamilyDefaultsStoreTests: XCTestCase {
    private func withDefaults(_ test: (UserDefaults) -> Void) {
        let suite = "WhoopFamilyDefaultsStoreTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        test(defaults)
    }

    func testFirstKnownFiveEnablesOnlyPassiveCaptureOnce() {
        withDefaults { defaults in
            let writeKeys = [PuffinExperiment.defaultsKey, PuffinExperiment.deepDataKey,
                             PuffinExperiment.broadcastHrKey, PuffinExperiment.ecgKey,
                             PuffinExperiment.ecgRawDataKey]
            WhoopFamilyDefaultsStore.apply(activeStrapId: "whoop-b", family: .whoop5, defaults: defaults)
            XCTAssertTrue(WhoopFamilyDefaultsStore.captureEnabled(defaults: defaults))
            XCTAssertFalse(defaults.bool(forKey: WhoopFamilyDefaults.touchedKey(for: WhoopFamilyDefaults.captureKey)))
            XCTAssertTrue(writeKeys.allSatisfy { defaults.object(forKey: $0) == nil })
            XCTAssertEqual(defaults.string(forKey: WhoopFamilyDefaults.appliedStrapIdsKey), "[\"whoop-b\"]")

            defaults.removeObject(forKey: WhoopFamilyDefaults.captureKey)
            WhoopFamilyDefaultsStore.apply(activeStrapId: "whoop-b", family: .whoop5, defaults: defaults)
            XCTAssertNil(defaults.object(forKey: WhoopFamilyDefaults.captureKey))
        }
    }

    func testExistingFalseAndNewUserChoiceSurviveAnotherStrap() {
        withDefaults { defaults in
            defaults.set(false, forKey: WhoopFamilyDefaults.captureKey)
            WhoopFamilyDefaultsStore.apply(activeStrapId: "whoop-a", family: .whoop5, defaults: defaults)
            XCTAssertFalse(WhoopFamilyDefaultsStore.captureEnabled(defaults: defaults))

            WhoopFamilyDefaultsStore.setCapture(true, defaults: defaults)
            WhoopFamilyDefaultsStore.setCapture(false, defaults: defaults)
            defaults.removeObject(forKey: WhoopFamilyDefaults.captureKey)
            WhoopFamilyDefaultsStore.apply(activeStrapId: "whoop-b", family: .whoop5, defaults: defaults)
            XCTAssertNil(defaults.object(forKey: WhoopFamilyDefaults.captureKey))
            XCTAssertTrue(defaults.bool(forKey: WhoopFamilyDefaults.touchedKey(for: WhoopFamilyDefaults.captureKey)))
            XCTAssertEqual(defaults.string(forKey: WhoopFamilyDefaults.appliedStrapIdsKey), "[\"whoop-a\",\"whoop-b\"]")
        }
    }

    func testSwitchingFourOrUnknownGatesWithoutErasingCaptureChoice() {
        withDefaults { defaults in
            WhoopFamilyDefaultsStore.apply(activeStrapId: "whoop-five", family: .whoop5, defaults: defaults)
            WhoopFamilyDefaultsStore.apply(activeStrapId: "whoop-four", family: .whoop4, defaults: defaults)
            XCTAssertFalse(WhoopFamilyDefaultsStore.captureEnabled(defaults: defaults))
            XCTAssertTrue(defaults.bool(forKey: WhoopFamilyDefaults.captureKey))
            WhoopFamilyDefaultsStore.apply(activeStrapId: "oura", family: nil, defaults: defaults)
            XCTAssertFalse(WhoopFamilyDefaultsStore.captureEnabled(defaults: defaults))
            WhoopFamilyDefaultsStore.apply(activeStrapId: "whoop-five", family: .whoop5, defaults: defaults)
            XCTAssertTrue(WhoopFamilyDefaultsStore.captureEnabled(defaults: defaults))
            XCTAssertEqual(defaults.string(forKey: WhoopFamilyDefaults.appliedStrapIdsKey), "[\"whoop-five\"]")
        }
    }

    func testUnidentifiedLaunchWaitsForRegistryModelCorrection() {
        withDefaults { defaults in
            WhoopFamilyDefaultsStore.apply(activeStrapId: "my-whoop", family: nil, defaults: defaults)
            XCTAssertNil(defaults.object(forKey: WhoopFamilyDefaults.captureKey))
            XCTAssertNil(defaults.object(forKey: WhoopFamilyDefaults.appliedStrapIdsKey))
            WhoopFamilyDefaultsStore.apply(activeStrapId: "my-whoop", family: .whoop5, defaults: defaults)
            XCTAssertTrue(WhoopFamilyDefaultsStore.captureEnabled(defaults: defaults))
        }
    }
}
