import XCTest
@testable import Strand

final class RecoveryStrainDetailLogicTests: XCTestCase {
    func testWholePercentPresentationKeepsTheStoredRecoveryBand() {
        for score in stride(from: 0.0, through: 100.0, by: 0.001) {
            let shown = RecoveryStrainDetailLogic.recoveryPercent(score)!
            XCTAssertEqual(shown >= 67, score >= 67)
            XCTAssertEqual(shown >= 34, score >= 34)
        }
        XCTAssertEqual(RecoveryStrainDetailLogic.recoveryPercent(33.999), 33)
        XCTAssertEqual(RecoveryStrainDetailLogic.recoveryPercent(66.999), 66)
        XCTAssertEqual(RecoveryStrainDetailLogic.recoveryPercent(34), 34)
        XCTAssertEqual(RecoveryStrainDetailLogic.recoveryPercent(67), 67)
        XCTAssertEqual(RecoveryStrainDetailLogic.recoveryPercent(100), 100)
        let invalidScores: [Double?] = [nil, .nan, .infinity, -1, 101]
        for score in invalidScores {
            XCTAssertNil(RecoveryStrainDetailLogic.recoveryPercent(score))
        }
    }

    func testComparisonExcludesSelectedAndFutureDaysAndMissingValues() {
        let keys = ["2026-08-31", "2026-09-01", "2026-09-15", "2026-09-30", "2026-10-01", "2026-10-02", "2026-10-03"]
        let values: [Double?] = [999, 10, nil, 20, .nan, 100, 1000]
        XCTAssertEqual(RecoveryStrainDetailLogic.priorMean(dayKeys: keys, values: values,
                                                         fromDay: "2026-09-01", selectedDay: "2026-10-02"), 15)
        XCTAssertNil(RecoveryStrainDetailLogic.priorMean(dayKeys: ["2026-10-02"], values: [100],
                                                       fromDay: "2026-09-01", selectedDay: "2026-10-02"))
    }

    func testInclusiveTargetBoundariesAndUnavailableValues() {
        let values: [String?] = [nil, "nan", "0.0", "3.99", "4.0", "10.0", "10.01", "21.0"]
        let expected: [RecoveryStrainDetailLogic.TargetStatus] = [.unavailable, .unavailable, .under, .under, .optimal, .optimal, .over, .over]
        XCTAssertEqual(values.map { RecoveryStrainDetailLogic.targetStatus(displayedStrain: $0, lower: 4, upper: 10) }, expected)
        XCTAssertEqual(RecoveryStrainDetailLogic.targetStatus(displayedStrain: "12.0", lower: nil, upper: nil), .unavailable)
    }

    func testTargetStatusMatchesTheVisibleOneDecimalScore() {
        let axisValues = [3.94, 3.99, 4.0, 10.0, 10.01, 10.06]
        let shown = axisValues.map { UnitFormatter.effortDisplay($0 / UnitFormatter.effortScaleFactor, scale: .whoop) }
        XCTAssertEqual(shown, ["3.9", "4.0", "4.0", "10.0", "10.0", "10.1"])
        XCTAssertEqual(shown.map { RecoveryStrainDetailLogic.targetStatus(displayedStrain: $0, lower: 4, upper: 10) },
                       [.under, .optimal, .optimal, .optimal, .optimal, .over])
    }

    func testPresentationMappingPreservesOriginalEffort() {
        for value in stride(from: 0.0, through: 100.0, by: 0.25) {
            let shown = UnitFormatter.effortValue(value, scale: .whoop)
            XCTAssertEqual(shown / UnitFormatter.effortScaleFactor, value, accuracy: 0.000000001)
        }
    }

    func testStandaloneSwiftOraclePinsCrossPlatformPresentation() {
        var lines: [String] = []
        let keys = ["2026-08-31", "2026-09-01", "2026-09-15", "2026-09-30", "2026-10-01", "2026-10-02", "2026-10-03"]
        let values: [Double?] = [999, 10, nil, 20, .nan, 100, 1000]
        lines.append(String(RecoveryStrainDetailLogic.priorMean(dayKeys: keys, values: values, fromDay: "2026-09-01", selectedDay: "2026-10-02")!))
        let targets: [String?] = [nil, "nan", "0.0", "3.99", "4.0", "10.0", "10.01", "21.0"]
        lines.append(targets.map { RecoveryStrainDetailLogic.targetStatus(displayedStrain: $0, lower: 4, upper: 10).rawValue }.joined(separator: ","))

        let recoveries: [Double?] = [nil, .nan, .infinity, -1, 0, 33.49, 33.999, 34, 66.49, 66.999, 67, 99.999, 100, 101]
        lines.append(recoveries.map { RecoveryStrainDetailLogic.recoveryPercent($0).map(String.init) ?? "unavailable" }.joined(separator: ","))

        let wholeValues: [Double?] = [nil, .nan, .infinity, -.infinity, -1, -0.0, 0, 0.49, 0.5, 1.49, 1.5, 1e300, 9223372036854775808.0, 9223372036854775808.0.nextDown]
        lines.append(wholeValues.map { RecoveryStrainDetailLogic.wholeNumber($0).map(String.init) ?? "unavailable" }.joined(separator: ","))
        let durations = [-1.0, Double.nan, 1e300, 59, 60, 89, 90, 119.9, 120]
        lines.append(durations.map { RecoveryStrainDetailLogic.durationMinutes(seconds: $0).map(String.init) ?? "unavailable" }.joined(separator: ","))
        let durationFallbacks: [(Double?, Double?)] = [(nil, nil), (nil, 3661), (0, 3661), (59, 3661), (60, 3661), (nil, -1), (nil, .nan), (nil, .infinity), (nil, 1e300), (-1, 3600), (.nan, 3600), (.infinity, 3600), (1e300, 3600)]
        lines.append(durationFallbacks.map { RecoveryStrainDetailLogic.durationMinutes(seconds: $0.0, fallbackSeconds: $0.1).map(String.init) ?? "unavailable" }.joined(separator: ","))
        let expected = """
        15.0
        unavailable,unavailable,under,under,optimal,optimal,over,over
        unavailable,unavailable,unavailable,unavailable,0,33,33,34,66,66,67,99,100,unavailable
        unavailable,unavailable,unavailable,unavailable,unavailable,0,0,0,1,1,2,unavailable,unavailable,9223372036854774784
        unavailable,unavailable,unavailable,0,1,1,1,1,2
        unavailable,61,0,0,1,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable
        """
        XCTAssertEqual(lines.joined(separator: "\n"), expected)
    }
}
