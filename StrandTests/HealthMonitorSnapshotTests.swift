import XCTest
import StrandAnalytics
import WhoopStore
@testable import Strand

final class HealthMonitorSnapshotTests: XCTestCase {
    private let now = ISO8601DateFormatter().date(from: "2026-06-20T12:00:00Z")!

    private func rows(currentHrv: Double? = 80, source: DailyMetricSource = .whoopImport) -> [SourcedDailyMetric] {
        (1...20).map { index in
            SourcedDailyMetric(metric: DailyMetric(day: String(format: "2026-06-%02d", index),
                                                   restingHr: 55, avgHrv: index == 20 ? currentHrv : 80,
                                                   spo2Pct: 97, skinTempDevC: 0.1, respRateBpm: 14.5),
                               source: source)
        }
    }

    func testFiveCurrentReadingsUsePersonalRanges() {
        let result = HealthMonitorSnapshot.rows(sourceRows: rows(), now: now, hrvBaselineEpoch: 0, recoveryBaselineEpoch: 0)
        XCTAssertEqual(result.map(\.id), ["hrv", "rhr", "resp", "spo2", "skin"])
        XCTAssertTrue(result.allSatisfy { $0.assessment.status == .withinRange })
        XCTAssertTrue(result.allSatisfy { $0.assessment.lower != nil && $0.assessment.upper != nil })
    }

    func testMissingTodayIsNeverCountedWithinRange() {
        let result = HealthMonitorSnapshot.rows(sourceRows: rows(currentHrv: nil), now: now, hrvBaselineEpoch: 0, recoveryBaselineEpoch: 0)
        let hrv = result.first { $0.id == "hrv" }!
        XCTAssertFalse(hrv.isCurrent)
        XCTAssertEqual(hrv.reading.day, "2026-06-19")
        XCTAssertEqual(hrv.assessment.status, .unavailable)
    }

    func testOverCountedCurrentHrvIsUnverified() {
        let values = rows(source: .noopComputed)
        let fresh = Dictionary(uniqueKeysWithValues: values.map { ($0.metric.day, 1.0) })
        let result = HealthMonitorSnapshot.rows(sourceRows: values, now: now,
                                                hrvOverCountByDay: ["2026-06-20": 1], freshScoringByDay: fresh, hrvBaselineEpoch: 0, recoveryBaselineEpoch: 0)
        XCTAssertEqual(result.first { $0.id == "hrv" }!.assessment.status, .unverified)
    }

    func testOverCountedHistoryDoesNotBuildTrustedBaseline() {
        let flags = Dictionary(uniqueKeysWithValues: (1...19).map { (String(format: "2026-06-%02d", $0), 1.0) })
        let values = rows(source: .noopComputed)
        let fresh = Dictionary(uniqueKeysWithValues: values.map { ($0.metric.day, 1.0) })
        let result = HealthMonitorSnapshot.rows(sourceRows: values, now: now, hrvOverCountByDay: flags, freshScoringByDay: fresh, hrvBaselineEpoch: 0, recoveryBaselineEpoch: 0)
        XCTAssertEqual(result.first { $0.id == "hrv" }!.assessment.status, .calibrating)
    }

    func testComputedHrvWithoutFreshScoringCannotClaimWithinRange() {
        let result = HealthMonitorSnapshot.rows(sourceRows: rows(source: .noopComputed), now: now, hrvBaselineEpoch: 0, recoveryBaselineEpoch: 0)
        XCTAssertEqual(result.first { $0.id == "hrv" }!.assessment.status, .unverified)
    }

    func testImportedHrvIsNotInvalidatedByComputedMarkers() {
        let result = HealthMonitorSnapshot.rows(sourceRows: rows(), now: now,
                                                hrvOverCountByDay: ["2026-06-20": 1],
                                                freshScoringByDay: ["2026-06-20": 0], hrvBaselineEpoch: 0, recoveryBaselineEpoch: 0)
        XCTAssertEqual(result.first { $0.id == "hrv" }!.assessment.status, .withinRange)
    }

    func testNonfiniteAndImplausibleHrvCannotBeFormattedAsAReading() {
        for value in [Double.nan, Double.infinity, 1e300] {
            let result = HealthMonitorSnapshot.rows(sourceRows: rows(currentHrv: value), now: now,
                                                    hrvBaselineEpoch: 0, recoveryBaselineEpoch: 0)
            let hrv = result.first { $0.id == "hrv" }!
            XCTAssertNil(hrv.formattedValue)
            XCTAssertNotEqual(hrv.assessment.status, .withinRange)
        }
    }

    func testAbsentOxygenStaysUnavailable() {
        let values = rows().map { source in
            SourcedDailyMetric(metric: DailyMetric(day: source.metric.day, avgHrv: 80, spo2Red: 100, spo2Ir: 110),
                               source: .noopComputed)
        }
        let result = HealthMonitorSnapshot.rows(sourceRows: values, now: now, hrvBaselineEpoch: 0, recoveryBaselineEpoch: 0)
        XCTAssertEqual(result.first { $0.id == "spo2" }!.assessment.status, .unavailable)
    }
}
