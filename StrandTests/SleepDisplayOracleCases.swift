import Foundation
#if canImport(Strand)
@testable import Strand
#endif

enum SleepDisplayOracleCases {
    static let inputs: [(String, [Double?])] = [
        ("stored-stage-disagreement", [420, 450, 480, 100, nil, 88]),
        ("fragmented", [240, 255, 420, 420.0 / 450.0 * 100, nil, 96]),
        ("imported-need-without-day", [390, 400, nil, nil, 450, 95]),
        ("all-awake", [0, 60, 480, 100, nil, 90]),
        ("import-only-summary", [nil, nil, 420, 420.0 / 450.0 * 100, 480, 0.88]),
        ("daily-fallback", [nil, nil, 420, 420.0 / 450.0 * 100, nil, 88]),
        ("missing-asleep", [nil, nil, nil, nil, 450, 88]),
        ("zero-daily", [nil, nil, 0, nil, 450, nil]),
        ("empty-recorded", [0, 0, 420, 420.0 / 450.0 * 100, nil, 0.88]),
        ("imported-need-wins", [420, 450, 480, 100, 600, 88]),
        ("missing-need", [390, 400, nil, 0, nil, 95]),
        ("nonfinite-recorded", [.nan, .infinity, 420, 420.0 / 450.0 * 100, 450, 0.9]),
        ("invalid-recorded", [450, 400, 420, 0, 0, 0.88]),
        ("missing", [nil, nil, nil, nil, nil, nil]),
    ]

    static func render() -> String {
        inputs.map { name, input in
            let value = SleepDisplayAmounts.resolve(recordedAsleep: input[0], recordedTotal: input[1],
                dailyAsleep: input[2], dailySufficiencyPct: input[3], importedNeed: input[4], storedEfficiency: input[5])
            return ([name] + [value.asleepMin, value.needMin, value.sufficiencyPct, value.efficiencyPct].map {
                $0.map { String(format: "%016llx", $0.bitPattern) } ?? "null"
            }).joined(separator: "|")
        }.joined(separator: "\n")
    }

    // Actual stdout from swiftc -O; columns are the four display values as IEEE-754 bits.
    static let expected = """
stored-stage-disagreement|407a400000000000|407e000000000000|4055e00000000000|4057555555555555
fragmented|406e000000000000|407c200000000000|404aaaaaaaaaaaab|4057878787878787
imported-need-without-day|4078600000000000|407c200000000000|4055aaaaaaaaaaab|4058600000000000
all-awake|0000000000000000|407e000000000000|0000000000000000|0000000000000000
import-only-summary|407a400000000000|407e000000000000|4055e00000000000|4056000000000000
daily-fallback|407a400000000000|407c200000000000|4057555555555555|4056000000000000
missing-asleep|null|407c200000000000|null|4056000000000000
zero-daily|null|407c200000000000|null|null
empty-recorded|407a400000000000|407c200000000000|4057555555555555|4056000000000000
imported-need-wins|407a400000000000|4082c00000000000|4051800000000000|4057555555555555
missing-need|4078600000000000|null|null|4058600000000000
nonfinite-recorded|407a400000000000|407c200000000000|4057555555555555|4056800000000000
invalid-recorded|407a400000000000|null|null|4056000000000000
missing|null|null|null|null
"""
}
