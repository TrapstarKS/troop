import XCTest
@testable import StrandAnalytics

final class HealthspanPresentationTests: XCTestCase {
    func testVaryingInputsAndCoverageOracle() {
        let offsets = Array(stride(from: 0, through: 119, by: 7))
        var rows = [-0.02, 0, 0.005, 0.02].map { slope in
            row(offsets.map { .init(daysAgo: $0, age: 40 + Double($0) * slope) })
        }
        rows.append(row([]))
        rows.append(row(offsets.map { .init(daysAgo: $0, age: 40) }, 20))
        rows.append(row(offsets.map { .init(daysAgo: $0, age: 40) }, 21, 17))
        rows.append(row([.init(daysAgo: 0, age: .nan), .init(daysAgo: -1, age: 40), .init(daysAgo: 180, age: 40)]))
        rows.append(row([.init(daysAgo: 15, age: 40), .init(daysAgo: 89, age: 41)]))
        let minutes = HealthspanPresentation.zoneMinutes(hours: [(0,60),(0.999,60),(1,30),(1.999,60),(2,60),(3,10),(nil,60),(.nan,60),(-1,60),(4,60),(1,-1)])
        rows.append(minutes.map(String.init).joined(separator: ","))
        XCTAssertEqual(rows.joined(separator: "\n"), """
        40.0|28|21|5|18
        40.0|10|21|5|18
        40.0|5|21|5|18
        40.0|-8|21|5|18
        nil|nil|21|0|0
        nil|nil|20|5|18
        nil|nil|21|5|18
        nil|nil|21|0|0
        nil|nil|21|1|2
        120,90,70
        """)
    }

    func testDuplicateDaysAndSparseHistoryDoNotInventPace() {
        let result = HealthspanPresentation.snapshot(samples: [.init(daysAgo: 0, age: 40), .init(daysAgo: 0, age: 39)], recoveryDays: 21, chronologicalAge: 40)
        XCTAssertEqual(result.age, 39)
        XCTAssertEqual(result.historySamples, 1)
        XCTAssertNil(result.pace)
    }

    func testPaceClampsAndNeedsAdultProfile() {
        for (slope, expected) in [(-0.04, 30), (0.04, -10)] {
            let samples = stride(from: 0, through: 119, by: 7).map { HealthspanPresentation.AgeSample(daysAgo: $0, age: 40 + Double($0) * slope) }
            let snapshot = HealthspanPresentation.snapshot(samples: samples, recoveryDays: 21, chronologicalAge: 40)
            XCTAssertEqual(snapshot.paceTenths, expected)
            XCTAssertNil(HealthspanPresentation.snapshot(samples: samples, recoveryDays: 21, chronologicalAge: .nan).age)
        }
    }

    func testStepsSelectionMatchesStandaloneOracle() {
        typealias Step = HealthspanPresentation.StepSample
        let invalid = [Double.nan, .infinity, -.infinity, -1].map {
            Step(day: "2026-02-04", count: $0, source: "invalid")
        }
        let rows = [
            stepRow("empty", [], []),
            stepRow("lower", [.init(day: "2026-02-01", count: 1, source: "measured")], [
                .init(day: "2026-01-31", count: 100, source: "too-old"),
                .init(day: "2026-02-05", count: 100, source: "future"),
            ]),
            stepRow("upper", [], [
                .init(day: "2026-02-04", count: 4.5, source: "upper-import"),
                .init(day: "2026-02-05", count: 100, source: "future"),
            ]),
            stepRow("zero", [.init(day: "2026-02-04", count: 0, source: "measured-zero")], [
                .init(day: "2026-02-04", count: 1000, source: "imported"),
            ]),
            stepRow("maximum", [], [
                .init(day: "2026-02-04", count: 10, source: "first-import"),
                .init(day: "2026-02-04", count: 100, source: "maximum-import"),
                .init(day: "2026-02-04", count: 50, source: "last-import"),
            ]),
            stepRow("invalid", invalid, invalid + [.init(day: "2026-02-04", count: 7.5, source: "valid-import")]),
            stepRow("all-invalid", invalid, invalid),
            stepRow("newer-import", [.init(day: "2026-02-03", count: 300, source: "older-measured")], [
                .init(day: "2026-02-04", count: 20, source: "newer-import"),
            ]),
            stepRow("import-tie", [], [
                .init(day: "2026-02-04", count: 7, source: "first-import"),
                .init(day: "2026-02-04", count: 7, source: "second-import"),
            ]),
            stepRow("measured-first", [
                .init(day: "2026-02-04", count: 5, source: "first-measured"),
                .init(day: "2026-02-04", count: 6, source: "second-measured"),
            ], [.init(day: "2026-02-04", count: 100, source: "imported")]),
            stepRow("reversed-bounds", [], [.init(day: "2026-02-02", count: 1, source: "imported")],
                    "2026-02-04", "2026-02-01"),
        ]
        // Expected rows copied verbatim from the standalone Swift fixture stdout.
        XCTAssertEqual(rows.joined(separator: "\n") + "\n", """
        empty|nil
        lower|2026-02-01|1.0|measured
        upper|2026-02-04|4.5|upper-import
        zero|2026-02-04|0.0|measured-zero
        maximum|2026-02-04|100.0|maximum-import
        invalid|2026-02-04|7.5|valid-import
        all-invalid|nil
        newer-import|2026-02-04|20.0|newer-import
        import-tie|2026-02-04|7.0|first-import
        measured-first|2026-02-04|5.0|first-measured
        reversed-bounds|nil
        """ + "\n")
    }

    private func stepRow(_ name: String, _ measured: [HealthspanPresentation.StepSample],
                         _ imported: [HealthspanPresentation.StepSample],
                         _ fromDay: String = "2026-02-01", _ throughDay: String = "2026-02-04") -> String {
        guard let selected = HealthspanPresentation.latestSteps(
            measured: measured, imported: imported, fromDay: fromDay, throughDay: throughDay
        ) else { return "\(name)|nil" }
        return "\(name)|\(selected.day)|\(selected.count)|\(selected.source)"
    }

    private func row(_ samples: [HealthspanPresentation.AgeSample], _ count: Int = 21, _ age: Double = 40) -> String {
        let s = HealthspanPresentation.snapshot(samples: samples, recoveryDays: count, chronologicalAge: age)
        return "\(s.age.map { String(format: "%.1f", $0) } ?? "nil")|\(s.paceTenths.map(String.init) ?? "nil")|\(s.recoveryDays)|\(s.recentSamples)|\(s.historySamples)"
    }
}
