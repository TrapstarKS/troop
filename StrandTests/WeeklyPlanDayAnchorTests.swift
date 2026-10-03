import XCTest
@testable import Strand

func weeklyPlanDayAnchorOracleOutput() -> String {
    let cases: [(String, String, String, Int)] = [
        ("Thursday-Friday", "2026-10-01", "2026-10-02", 0),
        ("historical-Friday", "2026-10-01", "2026-10-02", -1),
        ("Sunday-Monday", "2026-10-04", "2026-10-05", 0),
        ("historical-Monday", "2026-10-04", "2026-10-05", -1),
        ("later-week", "2026-10-04", "2026-10-19", 0),
        ("historical-later", "2026-10-04", "2026-10-19", -2),
        ("year-boundary", "2026-12-31", "2027-01-04", -1),
        ("same-day", "2026-10-01", "2026-10-01", -2),
        ("invalid-tick", "2026-10-01", "2026-02-30", -1),
        ("clock-back", "2026-10-05", "2026-10-04", -1),
        ("clock-back-current", "2026-10-05", "2026-10-04", 0),
        ("leap", "2024-02-28", "2024-03-04", -1)
    ]
    return cases.map { label, opened, clockDay, offset in
        let anchor = WeeklyPlanDayAnchor(today: opened, weekOffset: offset).advanced(to: clockDay)
        let current = WeeklyPlanCalendar.weekStart(anchor.today)!
        let selected = WeeklyPlanCalendar.adding(days: anchor.weekOffset * 7, to: current)!
        let editorWeek = WeeklyPlanCalendar.adding(days: offset * 7, to: WeeklyPlanCalendar.weekStart(opened)!)!
        let notice = WeeklyPlanNoticeResolver.resolve(today: anchor.today, availableWeeks: [current, WeeklyPlanCalendar.adding(days: -7, to: current)!])
        return "\(label)|\(anchor.today)|\(anchor.weekOffset)|\(selected)|\(notice?.id ?? "-")|\(editorWeek)"
    }.joined(separator: "\n")
}

final class WeeklyPlanDayAnchorTests: XCTestCase {
    func testClockTicksAdvanceNoticesAndPreserveHistoricalSelection() {
        XCTAssertEqual(weeklyPlanDayAnchorOracleOutput(), Self.oracle)
    }

    private static let oracle = """
    Thursday-Friday|2026-10-02|0|2026-09-28|checkIn:2026-09-28|2026-09-28
    historical-Friday|2026-10-02|-1|2026-09-21|checkIn:2026-09-28|2026-09-21
    Sunday-Monday|2026-10-05|0|2026-10-05|recap:2026-09-28|2026-09-28
    historical-Monday|2026-10-05|-2|2026-09-21|recap:2026-09-28|2026-09-21
    later-week|2026-10-19|0|2026-10-19|recap:2026-10-12|2026-09-28
    historical-later|2026-10-19|-5|2026-09-14|recap:2026-10-12|2026-09-14
    year-boundary|2027-01-04|-2|2026-12-21|recap:2026-12-28|2026-12-21
    same-day|2026-10-01|-2|2026-09-14|-|2026-09-14
    invalid-tick|2026-10-01|-1|2026-09-21|-|2026-09-21
    clock-back|2026-10-04|0|2026-09-28|-|2026-09-28
    clock-back-current|2026-10-04|0|2026-09-28|-|2026-10-05
    leap|2024-03-04|-2|2024-02-19|recap:2024-02-26|2024-02-19
    """
}
