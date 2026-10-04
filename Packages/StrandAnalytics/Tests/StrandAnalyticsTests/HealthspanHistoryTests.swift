import XCTest
@testable import StrandAnalytics

final class HealthspanHistoryTests: XCTestCase {
    func testCalibrationHistoryAndContributorOracle() {
        // Actual optimized standalone Swift stdout, pinned in the Kotlin twin.
        XCTAssertEqual(healthspanHistoryOracleRows(), """
        one-fresh|initialCalibration|21|21
        age-one-fresh|initialCalibration|nil|nil
        initial-89|initialCalibration|89|21
        age-initial-89|initialCalibration|nil|nil
        initial-90|ready|90|21
        age-initial-90|ready|4044000000000000|nil
        lost-recent|recentCoverage|120|20
        age-lost-recent|recentCoverage|nil|nil
        recovered|ready|120|21
        age-recovered|ready|4044000000000000|nil
        stale|unavailable|120|21
        age-stale|unavailable|nil|nil
        missing|unavailable|120|21
        age-missing|unavailable|nil|nil
        minor|adultOnly|120|31
        age-minor|adultOnly|nil|nil
        invalid-profile|adultOnly|120|31
        age-invalid-profile|adultOnly|nil|nil
        duplicate|ready|90|21
        age-duplicate|ready|4044000000000000|nil
        empty|initialCalibration|0|0
        age-empty|initialCalibration|nil|nil
        history-0|0|initialCalibration|0|0
        history-1|0|initialCalibration|1|1
        history-31|0|initialCalibration|31|31
        history-89|58|initialCalibration|31|31
        history-90|59|initialCalibration|31|31
        history-800|769|initialCalibration|31|31
        history-1000|969|initialCalibration|31|31
        history-4000|3969|initialCalibration|31|31
        history-4100|3969|initialCalibration|31|31
        compare-0-false|nil|nil|0|0
        compare-0-true|nil|nil|0|0
        select-0-30|nil
        select-0-180|nil
        compare-1-false|100|100|1|1
        compare-1-true|100|100|1|1
        select-1-30|0|4024000000000000
        select-1-180|0|4024000000000000
        compare-2-false|250|350|2|4
        compare-2-true|250|467|2|3
        select-2-30|29|403e000000000000
        select-2-180|30|4044000000000000
        compare-3-false|0|0|1|1
        compare-3-true|0|0|1|1
        select-3-30|4|0
        select-3-180|4|0
        compare-4-false|48|298|30|180
        compare-4-true|290|2065|5|26
        select-4-30|29|4023555555555555
        select-4-180|31|4024aaaaaaaaaaab
        compare-5-false|9|9|3|3
        compare-5-true|9|9|3|3
        select-5-30|29|0
        select-5-180|29|0
        activity-0|0:4042000000000000,7:0,179:4024000000000000
        activity-1|0:4028000000000000,7:403e000000000000,179:0
        activity-2|0:4034000000000000,7:403e000000000000
        strength|Strength|my-whoop|true
        strength|Traditional Strength Training|apple-health|true
        strength|functional_strength-training|health-connect|true
        strength|Walking|lifting|true
        strength|Walking|my-whoop|false
        strength|Yoga|apple-health|false
        """ + "\n")
    }

    func testSourceReplacementUsesItsOwnBoundAndSelectedWindow() {
        let a = Array(0..<1000)
        let b = Array(0..<90)
        XCTAssertEqual(HealthspanHistory.oldestReferenceOffset(dayOffsets: a), 969)
        XCTAssertEqual(HealthspanHistory.oldestReferenceOffset(dayOffsets: b), 59)
        let selected = a.map { $0 - 969 }.filter { (0..<31).contains($0) }
        XCTAssertEqual(HealthspanHistory.eligibility(recoveryOffsets: selected, chronologicalAge: 40, latestAgeDaysAgo: 0).recoveryDays, 31)
        XCTAssertEqual(HealthspanHistory.eligibility(recoveryOffsets: b, chronologicalAge: 40, latestAgeDaysAgo: 0).state, .ready)
        XCTAssertEqual(HealthspanHistory.eligibility(recoveryOffsets: b, chronologicalAge: 40, latestAgeDaysAgo: nil).state, .unavailable)
    }
}

import Foundation
private func healthspanHistoryOracleRows() -> String {
    var rows: [String] = []
    let cases: [(String, [Int], Double, Int?)] = [
        ("one-fresh", Array(0..<21), 40.0, Optional(0)),
        ("initial-89", Array(0..<21) + [88], 40.0, Optional(0)),
        ("initial-90", Array(0..<21) + [89], 40.0, Optional(0)),
        ("lost-recent", Array(0..<20) + [119], 40.0, Optional(0)),
        ("recovered", Array(0..<21) + [119], 40.0, Optional(0)),
        ("stale", Array(0..<21) + [119], 40.0, Optional(15)),
        ("missing", Array(0..<21) + [119], 40.0, nil),
        ("minor", Array(0..<31) + [119], 17.0, Optional(0)),
        ("invalid-profile", Array(0..<31) + [119], Double.nan, Optional(0)),
        ("duplicate", Array(0..<21) + Array(0..<21) + [89, -1, 4000], 18.0, Optional(14)),
        ("empty", [], 40.0, Optional(0)),
    ]
    for (name, offsets, age, latest) in cases {
        let e = HealthspanHistory.eligibility(recoveryOffsets: offsets, chronologicalAge: age, latestAgeDaysAgo: latest)
        rows.append("\(name)|\(e.state.rawValue)|\(e.initialDays)|\(e.recoveryDays)")
        let ageSamples = latest.map { [HealthspanPresentation.AgeSample(daysAgo: $0, age: 40)] } ?? []
        let snapshot = HealthspanPresentation.snapshot(samples: ageSamples, recoveryOffsets: offsets, chronologicalAge: age)
        rows.append("age-\(name)|\(snapshot.eligibility.state.rawValue)|\(snapshot.age.map { String($0.bitPattern, radix: 16) } ?? "nil")|\(snapshot.paceTenths.map(String.init) ?? "nil")")
    }
    for count in [0, 1, 31, 89, 90, 800, 1000, 4000, 4100] {
        let offsets = Array(0..<count)
        let bound = HealthspanHistory.oldestReferenceOffset(dayOffsets: offsets)
        let selectedOffsets = offsets.filter { (0..<4000).contains($0) }.map { $0 - bound }
        let e = HealthspanHistory.eligibility(recoveryOffsets: selectedOffsets, chronologicalAge: 40, latestAgeDaysAgo: 0)
        rows.append("history-\(count)|\(bound)|\(e.state.rawValue)|\(e.initialDays)|\(e.recoveryDays)")
    }
    typealias Sample = HealthspanHistory.Sample
    let sets: [[Sample]] = [[], [Sample(daysAgo: 0, value: 10)],
        [Sample(daysAgo: 0, value: 10), Sample(daysAgo: 0, value: 20), Sample(daysAgo: 29, value: 30), Sample(daysAgo: 30, value: 40), Sample(daysAgo: 179, value: 50), Sample(daysAgo: 180, value: 100)],
        [Sample(daysAgo: -1, value: 10), Sample(daysAgo: 1, value: .nan), Sample(daysAgo: 2, value: .infinity), Sample(daysAgo: 3, value: -1), Sample(daysAgo: 4, value: 0)],
        (0..<180).map { Sample(daysAgo: $0, value: Double($0) / 3) },
        [Sample(daysAgo: 7, value: 1.25), Sample(daysAgo: 14, value: 1.35), Sample(daysAgo: 29, value: 0)],
    ]
    for (index, samples) in sets.enumerated() {
        for weekly in [false, true] {
            let c = HealthspanHistory.comparison(samples: samples, weekly: weekly)
            rows.append("compare-\(index)-\(weekly)|\(c.recentTenths.map(String.init) ?? "nil")|\(c.longTermTenths.map(String.init) ?? "nil")|\(c.recentCount)|\(c.longTermCount)")
        }
        for range in [30, 180] {
            let p = HealthspanHistory.selected(samples: samples, daysAgo: 31, windowDays: range)
            rows.append("select-\(index)-\(range)|" + (p.map { "\($0.daysAgo)|\(String($0.value.bitPattern, radix: 16))" } ?? "nil"))
        }
    }
    let activities: [HealthspanHistory.Activity] = [
        .init(daysAgo: 0, durationSeconds: 3600, zones: [10,20,30,15,5], strength: false),
        .init(daysAgo: 0, durationSeconds: 1200, zones: nil, strength: true),
        .init(daysAgo: 7, durationSeconds: 1800, zones: [0,0,0,0,100], strength: true),
        .init(daysAgo: 179, durationSeconds: 600, zones: [100,0,0,0,0], strength: false),
        .init(daysAgo: 180, durationSeconds: 9999, zones: [100,0,0,0,0], strength: true),
        .init(daysAgo: 1, durationSeconds: .nan, zones: [10,20,30,15,5], strength: true),
        .init(daysAgo: 2, durationSeconds: -1, zones: [10,20,30,15,5], strength: true),
        .init(daysAgo: 3, durationSeconds: 600, zones: [.nan,0,0,0,0], strength: false),
        .init(daysAgo: 4, durationSeconds: 600, zones: [0,0,0,0,0], strength: false),
    ]
    for (index, points) in HealthspanHistory.activitySeries(activities).enumerated() {
        rows.append("activity-\(index)|" + points.map { "\($0.daysAgo):\(String($0.value.bitPattern, radix: 16))" }.joined(separator: ","))
    }
    for (sport, source) in [("Strength", "my-whoop"), ("Traditional Strength Training", "apple-health"), ("functional_strength-training", "health-connect"), ("Walking", "lifting"), ("Walking", "my-whoop"), ("Yoga", "apple-health")] {
        rows.append("strength|\(sport)|\(source)|\(HealthspanHistory.isStrength(sport: sport, source: source))")
    }
    return rows.joined(separator: "\n") + "\n"
}
