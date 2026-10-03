import XCTest
@testable import Strand

func journalCalendarOracleOutput() -> String {
    let cases: [(String, Int, String, String, Bool)] = [
        ("sameDay", 0, "2026-10-02", "2026-10-02", false),
        ("cleanToday", 0, "2026-10-02", "2026-10-03", false),
        ("dirtyToday", 0, "2026-10-02", "2026-10-03", true),
        ("historical", 6, "2026-10-02", "2026-10-03", false),
        ("dirtyHistory", 2, "2026-10-02", "2026-10-03", true),
        ("tomorrow", -1, "2026-10-02", "2026-10-03", false),
        ("future", -3, "2026-10-02", "2026-10-03", false),
        ("multiDayDraft", 0, "2026-10-02", "2026-10-07", true),
        ("multiDayHistory", 4, "2026-10-02", "2026-10-07", false),
        ("backwardDraft", 0, "2026-10-02", "2026-09-30", true),
        ("backwardHistory", 3, "2026-10-02", "2026-09-30", false),
        ("backwardCleanToday", 0, "2026-10-02", "2026-09-30", false),
        ("year", 2, "2026-12-31", "2027-01-01", false),
        ("leap", 0, "2024-02-28", "2024-03-01", true),
        ("nonLeap", 1, "2025-02-28", "2025-03-01", false),
        ("usSpring", 0, "2026-03-08", "2026-03-09", true),
        ("usFall", 2, "2026-11-01", "2026-11-02", false),
        ("euSpring", -1, "2026-03-29", "2026-03-30", false),
        ("euFall", 0, "2026-10-25", "2026-10-26", true),
        ("brazilSpring", 6, "2018-11-03", "2018-11-04", false),
        ("invalidFrom", 3, "bad", "2026-10-03", false),
        ("invalidTo", -1, "2026-10-02", "2026-02-30", true),
        ("invalidWidth", 0, "2026-2-01", "2026-03-01", true),
        ("month", 0, "2026-01-31", "2026-02-02", true),
        ("ancient", 1, "0001-01-01", "0001-01-02", false),
        ("upperOverflow", Int.max, "2026-10-02", "2026-10-03", true),
        ("lowerOverflow", Int.min, "2026-10-03", "2026-10-02", true),
    ]
    return cases.map { label, offset, from, to, dirty in
        let resolved = JournalCalendar.rolloverOffset(offset: offset, from: from, to: to, preserveDraft: dirty)
        return "\(label)|\(offset)|\(from)|\(to)|\(dirty)|\(resolved)"
    }.joined(separator: "\n")
}

final class JournalCalendarTests: XCTestCase {
    func testRolloverMatchesSwiftOracle() {
        XCTAssertEqual(journalCalendarOracleOutput(), Self.expectedOracle)
    }

    func testPreservesSelectedDateAcrossCalendarTransitions() {
        for (from, to) in [("2026-10-02", "2026-10-03"), ("2026-10-02", "2026-10-07"),
                           ("2026-10-02", "2026-09-30"), ("2026-12-31", "2027-01-01"),
                           ("2024-02-28", "2024-03-01"), ("2026-03-08", "2026-03-09"),
                           ("2026-11-01", "2026-11-02")] {
            for offset in -1...6 {
                let resolved = JournalCalendar.rolloverOffset(offset: offset, from: from, to: to, preserveDraft: true)
                XCTAssertEqual(WeeklyPlanCalendar.adding(days: -offset, to: from), WeeklyPlanCalendar.adding(days: -resolved, to: to))
            }
        }
    }

    static let expectedOracle = """
    sameDay|0|2026-10-02|2026-10-02|false|0
    cleanToday|0|2026-10-02|2026-10-03|false|0
    dirtyToday|0|2026-10-02|2026-10-03|true|1
    historical|6|2026-10-02|2026-10-03|false|7
    dirtyHistory|2|2026-10-02|2026-10-03|true|3
    tomorrow|-1|2026-10-02|2026-10-03|false|0
    future|-3|2026-10-02|2026-10-03|false|-2
    multiDayDraft|0|2026-10-02|2026-10-07|true|5
    multiDayHistory|4|2026-10-02|2026-10-07|false|9
    backwardDraft|0|2026-10-02|2026-09-30|true|-2
    backwardHistory|3|2026-10-02|2026-09-30|false|1
    backwardCleanToday|0|2026-10-02|2026-09-30|false|0
    year|2|2026-12-31|2027-01-01|false|3
    leap|0|2024-02-28|2024-03-01|true|2
    nonLeap|1|2025-02-28|2025-03-01|false|2
    usSpring|0|2026-03-08|2026-03-09|true|1
    usFall|2|2026-11-01|2026-11-02|false|3
    euSpring|-1|2026-03-29|2026-03-30|false|0
    euFall|0|2026-10-25|2026-10-26|true|1
    brazilSpring|6|2018-11-03|2018-11-04|false|7
    invalidFrom|3|bad|2026-10-03|false|3
    invalidTo|-1|2026-10-02|2026-02-30|true|-1
    invalidWidth|0|2026-2-01|2026-03-01|true|0
    month|0|2026-01-31|2026-02-02|true|2
    ancient|1|0001-01-01|0001-01-02|false|2
    upperOverflow|9223372036854775807|2026-10-02|2026-10-03|true|9223372036854775807
    lowerOverflow|-9223372036854775808|2026-10-03|2026-10-02|true|-9223372036854775808
    """
}
