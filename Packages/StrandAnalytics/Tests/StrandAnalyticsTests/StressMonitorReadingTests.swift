import XCTest
@testable import StrandAnalytics

final class StressMonitorReadingTests: XCTestCase {
    func testRecordedReadingOracle() {
        typealias W = StressMonitorReading.Window
        let scored = W(startTs: 32_400, endTs: 32_699, level: 1.5)
        let gap = W(startTs: 36_000, endTs: 36_299, level: nil)
        let active = W(startTs: 36_000, endTs: 36_299, level: nil, maskedForActivity: true)
        let cases: [(String, [W], Bool, Int, Bool, Int?)] = [
            ("partial", [scored], true, 32_699, true, nil),
            ("fresh900", [scored], true, 33_599, true, nil),
            ("delayed901", [scored], true, 33_600, true, nil),
            ("historical", [scored], true, 86_400, false, nil),
            ("recentHrUnscored", [scored, gap], true, 36_300, true, nil),
            ("selectedOld", [scored, gap], true, 36_300, true, 32_400),
            ("noHr", [], false, 36_300, true, nil),
            ("nightOnly", [], true, 18_000, true, nil),
            ("under300", [gap], true, 36_300, true, nil),
            ("activity", [active], true, 36_300, true, nil),
            ("selectedActivity", [scored, active], true, 36_300, true, 36_000),
            ("selectedGap", [scored, gap], true, 36_300, true, 36_000),
            ("selectedMissing", [scored], true, 36_300, true, 1),
            ("invalid", [W(startTs: 36_000, endTs: 36_299, level: .nan), W(startTs: 37_800, endTs: 38_000, level: 3.1)], true, 38_000, true, nil),
            ("unsorted", [W(startTs: 36_000, endTs: 36_299, level: 2), scored], true, 36_300, true, nil),
        ]
        let output = cases.map { name, windows, hasHr, now, today, selection in
            let reading = StressMonitorReading.resolve(windows: windows, hasHeartRate: hasHr,
                                                       now: now, isToday: today, selectedStartTs: selection)
            let score = reading.window?.level.map { String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), $0) } ?? "nil"
            return "\(name)|\(reading.state.rawValue)|\(reading.window?.startTs.description ?? "nil")|\(score)"
        }
        // Verbatim stdout from swiftc -O StressMonitorReading.swift main.swift; Kotlin pins the same table.
        XCTAssertEqual(output.joined(separator: "\n"), """
        partial|recorded|32400|1.5
        fresh900|recorded|32400|1.5
        delayed901|delayed|32400|1.5
        historical|recorded|32400|1.5
        recentHrUnscored|delayed|32400|1.5
        selectedOld|recorded|32400|1.5
        noHr|noHeartRate|nil|nil
        nightOnly|noWakingHeartRate|nil|nil
        under300|insufficientSamples|nil|nil
        activity|activityExcluded|nil|nil
        selectedActivity|activityExcluded|36000|nil
        selectedGap|insufficientSamples|36000|nil
        selectedMissing|insufficientSamples|nil|nil
        invalid|insufficientSamples|nil|nil
        unsorted|recorded|36000|2.0
        """)
    }
}
