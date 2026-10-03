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

    private func row(_ samples: [HealthspanPresentation.AgeSample], _ count: Int = 21, _ age: Double = 40) -> String {
        let s = HealthspanPresentation.snapshot(samples: samples, recoveryDays: count, chronologicalAge: age)
        return "\(s.age.map { String(format: "%.1f", $0) } ?? "nil")|\(s.paceTenths.map(String.init) ?? "nil")|\(s.recoveryDays)|\(s.recentSamples)|\(s.historySamples)"
    }
}
