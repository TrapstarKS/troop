import XCTest
@testable import Strand

final class RecoveryStrainDetailLogicTests: XCTestCase {
    func testComparisonExcludesSelectedAndFutureDaysAndMissingValues() {
        let keys = ["2026-08-31", "2026-09-01", "2026-09-15", "2026-09-30", "2026-10-01", "2026-10-02", "2026-10-03"]
        let values: [Double?] = [999, 10, nil, 20, .nan, 100, 1000]
        XCTAssertEqual(RecoveryStrainDetailLogic.priorMean(dayKeys: keys, values: values,
                                                         fromDay: "2026-09-01", selectedDay: "2026-10-02"), 15)
        XCTAssertNil(RecoveryStrainDetailLogic.priorMean(dayKeys: ["2026-10-02"], values: [100],
                                                       fromDay: "2026-09-01", selectedDay: "2026-10-02"))
    }

    func testInclusiveTargetBoundariesAndUnavailableValues() {
        let values: [Double?] = [nil, .nan, 0, 3.99, 4, 10, 10.01, 21]
        let expected: [RecoveryStrainDetailLogic.TargetStatus] = [.unavailable, .unavailable, .under, .under, .optimal, .optimal, .over, .over]
        XCTAssertEqual(values.map { RecoveryStrainDetailLogic.targetStatus(strain21: $0, lower: 4, upper: 10) }, expected)
        XCTAssertEqual(RecoveryStrainDetailLogic.targetStatus(strain21: 12, lower: nil, upper: nil), .unavailable)
    }

    func testPresentationMappingPreservesOriginalEffort() {
        for value in stride(from: 0.0, through: 100.0, by: 0.25) {
            let shown = UnitFormatter.effortValue(value, scale: .whoop)
            XCTAssertEqual(shown / UnitFormatter.effortScaleFactor, value, accuracy: 0.000000001)
        }
    }
}
