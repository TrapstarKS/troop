import XCTest
@testable import StrandAnalytics

final class HealthSignalReliabilityTests: XCTestCase {
    func testFreshComputedProvenanceAndImportedPrecedence() {
        XCTAssertNil(HealthSignalReliability.hrv(50, computed: true))
        XCTAssertNil(HealthSignalReliability.hrv(50, computed: true, freshScoringValid: 0))
        XCTAssertNil(HealthSignalReliability.hrv(50, computed: true, freshScoringValid: 1, overcount: 1))
        XCTAssertEqual(HealthSignalReliability.hrv(50, computed: true, freshScoringValid: 1), 50)
        XCTAssertEqual(HealthSignalReliability.hrv(50, computed: false, freshScoringValid: 0, overcount: 1), 50)
        XCTAssertNil(HealthSignalReliability.hrv(.nan, computed: false))
    }

    func testCalendarGapsAndRecalibrationCannotBorrowTrust() {
        let keys = HealthSignalReliability.dayKeys(ending: "2026-02-02", count: 28, daysAgo: 3)
        let values = keys.map { $0 >= "2026-01-27" ? Optional(60.0) : nil }
        let provisional = Baselines.foldHistory(values, dayKeys: keys, cfg: Baselines.hrvCfg, baselineEpoch: 0)
        XCTAssertTrue(provisional.usable)
        XCTAssertFalse(provisional.trusted)
        let trusted = Baselines.foldHistory(Array(repeating: 60.0, count: keys.count), dayKeys: keys,
                                            cfg: Baselines.hrvCfg, baselineEpoch: 0)
        XCTAssertTrue(trusted.trusted)
        let reset = Baselines.foldHistory(Array(repeating: 60.0, count: keys.count), dayKeys: keys,
                                          cfg: Baselines.hrvCfg, baselineEpoch: 1_769_947_200)
        XCTAssertFalse(reset.trusted)
    }

    func testEligibilityOracle() {
        let values: [Double?] = [nil, 4, 5, 50, 250, 251, .nan, .infinity]
        let markers: [Double?] = [nil, 0, 1, .nan]
        var actual = ""
        for computed in [false, true] {
            for fresh in markers {
                for overcount in markers {
                    for value in values {
                        actual += HealthSignalReliability.hrv(value, computed: computed,
                            freshScoringValid: fresh, overcount: overcount) == nil ? "0" : "1"
                    }
                }
            }
        }
        XCTAssertEqual(actual, "0011100000111000001110000011100000111000001110000011100000111000001110000011100000111000001110000011100000111000001110000011100000000000000000000000000000000000000000000000000000000000000000000011100000111000000000000000000000000000000000000000000000000000")
    }

    func testCalendarOracle() {
        let cases: [(String, Int, Int, Double)] = [("2024-03-01", 4, 0, 0), ("2026-01-03", 5, 1, 0),
            ("bad", 4, 0, 0), ("2026-02-30", 4, 0, 0), ("2026-01-04", 4, 0, 1_767_355_200)]
        let actual = cases.map { testCase in
            let (day, count, offset, epoch) = testCase
            return HealthSignalReliability.dayKeys(ending: day, count: count, daysAgo: offset,
                                           baselineEpoch: epoch).joined(separator: ",")
        }.joined(separator: "\n")
        XCTAssertEqual(actual, "2024-02-27,2024-02-28,2024-02-29,2024-03-01\n2025-12-29,2025-12-30,2025-12-31,2026-01-01,2026-01-02\n\n\n2026-01-03,2026-01-04")
    }

    func testDurableEdgeOracle() {
        var actual = ""
        for alert in [nil, "strained"] as [String?] {
            for previous in [nil, false, true] as [Bool?] {
                for last in [nil, "2026-06-09", "2026-06-10"] as [String?] {
                    actual += IllnessAlertPolicy.shouldNotify(alert: alert, previouslyRaised: previous,
                        lastNotifiedDay: last, today: "2026-06-10") ? "1" : "0"
                }
            }
        }
        XCTAssertEqual(actual, "000000000000110000")
    }
    func testOptOutAndLoadingPreserveARaisedEdgeUntilAValidClear() {
        var previous: Bool? = true
        var notifications = 0
        let events: [(Bool, Bool, String?)] = [(false, true, nil), (true, false, nil),
            (true, true, "still raised"), (true, true, nil), (true, true, "new raised")]
        for (enabled, valid, alert) in events {
            guard IllnessAlertPolicy.shouldRecordEvaluation(enabled: enabled, valid: valid) else { continue }
            if IllnessAlertPolicy.shouldNotify(alert: alert, previouslyRaised: previous,
                lastNotifiedDay: "2026-06-09", today: "2026-06-10") { notifications += 1 }
            previous = alert != nil
        }
        XCTAssertEqual(notifications, 1)
        XCTAssertEqual(previous, true)
    }
    func testUnknownCurrentHrvDoesNotClearThenRenotifyARaisedPattern() {
        var previous: Bool? = true
        let unknownHrv = HealthSignalReliability.hrv(20, computed: true)
        let unknownReady = [true, unknownHrv != nil].filter { $0 }.count >= IllnessSignalEngine.minCorroboratingSignals
        XCTAssertFalse(unknownReady)
        if IllnessAlertPolicy.shouldRecordEvaluation(enabled: true, valid: unknownReady) { previous = false }
        let freshHrv = HealthSignalReliability.hrv(20, computed: true, freshScoringValid: 1)
        let ready = [true, freshHrv != nil].filter { $0 }.count >= IllnessSignalEngine.minCorroboratingSignals
        XCTAssertTrue(ready)
        XCTAssertFalse(IllnessAlertPolicy.shouldNotify(alert: "still raised", previouslyRaised: previous,
            lastNotifiedDay: "2026-02-01", today: "2026-02-02"))
        if IllnessAlertPolicy.shouldRecordEvaluation(enabled: true, valid: ready) { previous = false }
        XCTAssertTrue(IllnessAlertPolicy.shouldNotify(alert: "new raised", previouslyRaised: previous,
            lastNotifiedDay: "2026-02-01", today: "2026-02-02"))
    }
    func testValueBoundRecordOracle() {
        var actual = ""
        for stored in [4.0, 50.0, Double.nan, Double.infinity] {
            for eligible in [false, true] {
                for current in [nil, 4.0, 50.0, Double.nan, Double.infinity] as [Double?] {
                    actual += HealthSignalReliability.Record(value: stored, eligible: eligible).matches(current) ? "1" : "0"
                }
            }
        }
        XCTAssertEqual(actual, "0000001000000000010000000000000000000000")
    }
    func testPhysicalSourceOwnerOracle() {
        let sources = ["import", "active", "canonical"]
        let values = [60.0, 40.0, 20.0]
        var actual = ""
        for order in [sources, Array(sources.reversed())] {
            for present in 0..<8 {
                for eligible in 0..<8 {
                    var bySource: [String: HealthSignalReliability.Record] = [:]
                    for index in 0..<3 where present & (1 << index) != 0 {
                        bySource[sources[index]] = HealthSignalReliability.Record(value: values[index], eligible: eligible & (1 << index) != 0)
                    }
                    for current in [20.0, 40.0, 60.0] {
                        let record = HealthSignalReliability.firstRecord(sourceIds: order, bySource: bySource)
                        actual += record == nil ? "0" : "1"
                        actual += record?.eligible == true ? "1" : "0"
                        actual += record?.matches(current) == true ? "1" : "0"
                    }
                }
            }
        }
        XCTAssertEqual(actual, "000000000000000000000000000000000000000000000000000000000000000000000000100100100110110111100100100110110111100100100110110111100100100110110111100100100100100100110111110110111110100100100100100100110111110110111110100100100110110111100100100110110111100100100110110111100100100110110111100100100100100100100100100100100100111110110111110110111110110111110110100100100110110111100100100110110111100100100110110111100100100110110111100100100100100100110111110110111110100100100100100100110111110110111110100100100110110111100100100110110111100100100110110111100100100110110111000000000000000000000000000000000000000000000000000000000000000000000000100100100110110111100100100110110111100100100110110111100100100110110111100100100100100100110111110110111110100100100100100100110111110110111110100100100100100100110111110110111110100100100100100100110111110110111110100100100100100100100100100100100100111110110111110110111110110111110110100100100100100100100100100100100100111110110111110110111110110111110110100100100100100100100100100100100100111110110111110110111110110111110110100100100100100100100100100100100100111110110111110110111110110111110110")
    }
    func testIndependentRespiratoryEligibilityOracle() {
        XCTAssertNil(HealthSignalReliability.hrv(50, computed: true, freshScoringValid: 0, overcount: 1))
        XCTAssertEqual(HealthSignalReliability.respiration(16, computed: true, freshScoringValid: 1), 16)
        XCTAssertEqual(HealthSignalReliability.respiration(16, computed: false, freshScoringValid: 0), 16)
        var actual = ""
        for computed in [false, true] {
            for fresh in [nil, 0, 1, Double.nan] as [Double?] {
                for value in [nil, 7.0, 8.0, 16.0, 25.0, 26.0, Double.nan, Double.infinity] as [Double?] {
                    actual += HealthSignalReliability.respiration(value, computed: computed, freshScoringValid: fresh) == nil ? "0" : "1"
                }
            }
        }
        XCTAssertEqual(actual, "0111110001111100011111000111110000000000000000000111110000000000")
    }
    func testEvaluationRecordOracle() {
        var actual = ""
        for enabled in [false, true] {
            for valid in [false, true] {
                actual += IllnessAlertPolicy.shouldRecordEvaluation(enabled: enabled, valid: valid) ? "1" : "0"
            }
        }
        XCTAssertEqual(actual, "0001")
    }
}
