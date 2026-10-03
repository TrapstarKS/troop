import XCTest
@testable import StrandImport

final class HealthAuthorizationSignatureTests: XCTestCase {
    func testRolesCategoriesAndSeriesArePartOfTheAuthorizationSet() {
        let read = ["heartRate", "sleepAnalysis", "workout", "workoutRoute"]
        let write = ["heartRate", "workoutRoute"]
        let signature = HealthWriteback.authorizationTypeSignature(read: read, write: write)
        XCTAssertEqual(signature,
            "read:heartRate,read:sleepAnalysis,read:workout,read:workoutRoute,write:heartRate,write:workoutRoute")
        XCTAssertEqual(signature, HealthWriteback.authorizationTypeSignature(
            read: Array(read.reversed()) + ["heartRate"], write: Array(write.reversed())))
        XCTAssertNotEqual(signature, HealthWriteback.authorizationTypeSignature(read: read, write: []))
        XCTAssertNotEqual(signature, read.sorted().joined(separator: ","),
                          "The legacy read-only fingerprint must request the expanded authorization set")
        XCTAssertNotEqual(signature, HealthWriteback.authorizationTypeSignature(
            read: read, write: write + ["activeEnergy"]))
    }
}
