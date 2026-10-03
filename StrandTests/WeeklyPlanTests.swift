import Foundation
import XCTest
#if canImport(Strand)
@testable import Strand
#endif

func weeklyPlanOracleOutput() -> String {
    var lines: [String] = []
    for preset in WeeklyPlanPreset.allCases {
        let goals = preset.goals
        lines.append("preset:\(preset.rawValue):\(goals.sleepMinutes),\(goals.sleepDays),\(goals.strainMinimum),\(goals.strainDays),\(goals.journalDays),\(goals.journalQuestion),\(goals.journalAnswer)")
    }
    for day in ["2026-2-01", "2026-02-30", "2024-02-29", "2026-10-02", "2026-12-31", "2027-01-01", "0001-01-01", "9999-12-31"] {
        lines.append("date:\(day):\(WeeklyPlanCalendar.weekStart(day) ?? "-"):\(WeeklyPlanCalendar.weekday(day).map(String.init) ?? "-")")
    }
    for value in [-999, 0, 1, 7, 8, 50, 100, 101, 1000] {
        let goals = WeeklyPlanGoals(sleepMinutes: value, sleepDays: value, strainMinimum: value,
                                    strainDays: value, journalDays: value, journalQuestion: "habit", journalAnswer: "invalid").normalized
        lines.append("goals:\(value):\(goals.sleepMinutes),\(goals.sleepDays),\(goals.strainMinimum),\(goals.strainDays),\(goals.journalDays),\(goals.journalAnswer)")
    }
    let sleep: [Double?] = [480, 420, nil, 500, 0, 999, 240]
    let strain: [Double?] = [60, 45, .nan, 100, -1, 200, 0]
    var days = [WeeklyPlanDay(day: "2026-09-28", sleepMinutes: 600, strain: 100),
                WeeklyPlanDay(day: "2026-02-30", sleepMinutes: 600, strain: 100)]
    for offset in 0..<7 {
        days.append(WeeklyPlanDay(day: WeeklyPlanCalendar.adding(days: offset, to: "2026-09-28")!,
                                  sleepMinutes: sleep[offset], strain: strain[offset]))
    }
    let entries = [WeeklyPlanJournalDay(day: "2026-09-28", question: "habit", answeredYes: true),
                   WeeklyPlanJournalDay(day: "2026-09-28", question: "habit", answeredYes: false),
                   WeeklyPlanJournalDay(day: "2026-09-29", question: "habit", answeredYes: true),
                   WeeklyPlanJournalDay(day: "2026-10-01", question: "habit", answeredYes: false),
                   WeeklyPlanJournalDay(day: "2026-10-02", question: "other", answeredYes: true),
                   WeeklyPlanJournalDay(day: "2026-10-04", question: "habit", answeredYes: true)]
    for today in ["2026-09-27", "2026-09-28", "2026-09-29", "2026-09-30", "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05"] {
        for answer in ["any", "yes", "no"] {
            let goals = WeeklyPlanGoals(sleepMinutes: 480, sleepDays: 3, strainMinimum: 50, strainDays: 2,
                                        journalDays: 3, journalQuestion: answer == "any" ? "" : "habit", journalAnswer: answer)
            let snapshot = WeeklyPlanEngine.snapshot(goals: goals, weekStart: "2026-09-28", today: today, days: days, journal: entries)!
            func result(_ progress: WeeklyPlanProgress) -> String {
                "\(progress.completedDays)/\(progress.observedDays)/\(progress.percent.map(String.init) ?? "-")"
            }
            lines.append("snapshot:\(today):\(answer):\(result(snapshot.sleep)):\(result(snapshot.strain)):\(result(snapshot.journal)):\(snapshot.overallPercent.map(String.init) ?? "-")")
        }
        let notice = WeeklyPlanNoticeResolver.resolve(today: today, availableWeeks: ["2026-09-21", "2026-09-28"])
        lines.append("notice:\(today):\(notice?.id ?? "-")")
    }
    for today in ["2026-10-02", "2026-10-05"] {
        let goals = WeeklyPlanEngine.suggestedGoals(days: days, today: today)
        lines.append("suggested:\(today):\(goals.sleepMinutes):\(goals.strainMinimum)")
    }
    let extreme = WeeklyPlanEngine.suggestedGoals(days: [WeeklyPlanDay(day: "2026-09-28", sleepMinutes: 1e308, strain: nil),
                                                       WeeklyPlanDay(day: "2026-09-29", sleepMinutes: 1e308, strain: nil)], today: "2026-10-02")
    lines.append("suggested:extreme:\(extreme.sleepMinutes):\(extreme.strainMinimum)")
    return lines.joined(separator: "\n")
}

func weeklyPlanEligibilityOracleOutput() -> String {
    let today = "2026-10-05"
    let valid = (0..<8).map {
        WeeklyPlanRecoveryDay(day: WeeklyPlanCalendar.adding(days: $0, to: "2026-09-28")!, recovery: 50, sleepProcessed: true)
    }
    var lines: [String] = []
    func append(_ label: String, _ rows: [WeeklyPlanRecoveryDay], _ day: String = "2026-10-05") {
        let result = WeeklyPlanEligibility.resolve(recoveries: rows, today: day)
        lines.append("\(label):\(result.completedRecoveries):\(result.remainingRecoveries):\(result.isEligible)")
    }
    for count in [0, 1, 6, 7, 8] { append("count-\(count)", Array(valid.prefix(count)), today) }
    append("duplicate-seven", Array(valid.prefix(7)) + Array(valid.prefix(7)))
    append("duplicate-one", Array(repeating: valid[0], count: 7))
    let scores: [(String, Double?)] = [("missing", nil), ("nan", .nan), ("infinity", .infinity),
        ("negative-infinity", -.infinity), ("negative", -1), ("above-range", 101), ("zero", 0), ("hundred", 100)]
    for (label, score) in scores {
        append(label, Array(valid.prefix(6)) + [WeeklyPlanRecoveryDay(day: "2026-10-04", recovery: score, sleepProcessed: true)])
    }
    for day in ["2026-02-30", "2026-2-01", "", "2026-10-06"] {
        append("day-\(day)", Array(valid.prefix(6)) + [WeeklyPlanRecoveryDay(day: day, recovery: 50, sleepProcessed: true)])
    }
    append("invalid-today", valid, "2026-02-30")
    append("before-seventh", Array(valid.prefix(7)), "2026-10-03")
    append("incomplete", Array(valid.prefix(6)) + [WeeklyPlanRecoveryDay(day: "2026-10-04", recovery: 50, sleepProcessed: false)])
    append("subsequent-refresh", Array(valid.prefix(7)))
    append("not-processed", valid.map { WeeklyPlanRecoveryDay(day: $0.day, recovery: $0.recovery, sleepProcessed: false) })
    return lines.joined(separator: "\n")
}

final class WeeklyPlanTests: XCTestCase {
    func testCompletedRecoveryEligibilityMatchesSwiftOracle() {
        XCTAssertEqual(weeklyPlanEligibilityOracleOutput(), Self.eligibilityOracle)
    }

    func testSwiftOracleRemainsStable() {
        XCTAssertEqual(weeklyPlanOracleOutput(), Self.expectedOracle)
    }

    func testUnavailableDoesNotBecomeZeroProgress() {
        let snapshot = WeeklyPlanEngine.snapshot(goals: WeeklyPlanGoals(), weekStart: "2026-09-28",
                                                  today: "2026-10-02", days: [], journal: [])!
        XCTAssertNil(snapshot.sleep.percent)
        XCTAssertNil(snapshot.strain.percent)
        XCTAssertEqual(snapshot.journal.percent, 0)
        XCTAssertNil(snapshot.overallPercent)
        XCTAssertNil(WeeklyPlanEngine.snapshot(goals: WeeklyPlanGoals(), weekStart: "2026-09-29",
                                               today: "2026-10-02", days: [], journal: []))
    }

    func testUnansweredHabitIsNotCountedAsNo() {
        let goals = WeeklyPlanGoals(journalQuestion: "habit", journalAnswer: "no")
        let snapshot = WeeklyPlanEngine.snapshot(goals: goals, weekStart: "2026-09-28", today: "2026-10-02",
            days: [], journal: [WeeklyPlanJournalDay(day: "2026-09-29", question: "other", answeredYes: false)])!
        XCTAssertEqual(snapshot.journal.completedDays, 0)
        XCTAssertNil(snapshot.journal.percent)
    }

    func testWeeklyTargetsAndDismissalsPersistWithoutChangingHistory() {
        let suite = "WeeklyPlanTests-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = WeeklyPlanPreferences(defaults: defaults)
        XCTAssertFalse(preferences.hasPlan(weekStart: "2026-09-28"))
        _ = preferences.goals(weekStart: "2026-09-28")
        XCTAssertFalse(preferences.hasPlan(weekStart: "2026-09-28"))
        let previous = WeeklyPlanGoals(sleepMinutes: 420, sleepDays: 4)
        preferences.save(previous, weekStart: "2026-09-28")
        let next = WeeklyPlanGoals(sleepMinutes: 510, sleepDays: 6)
        preferences.save(next, weekStart: "2026-10-05")
        XCTAssertEqual(preferences.goals(weekStart: "2026-09-28"), previous)
        XCTAssertEqual(preferences.goals(weekStart: "2026-10-12"), next)
        let eligible = WeeklyPlanEligibility.resolve(recoveries: (0..<7).map {
            WeeklyPlanRecoveryDay(day: WeeklyPlanCalendar.adding(days: $0, to: "2026-09-28")!, recovery: 50, sleepProcessed: true)
        }, today: "2026-10-05")
        XCTAssertNil(preferences.eligibleNotice(today: "2026-10-05", eligibility: WeeklyPlanEligibility(completedRecoveries: 6)))
        XCTAssertEqual(preferences.goals(weekStart: "2026-10-05"), next)
        let notice = preferences.eligibleNotice(today: "2026-10-05", eligibility: eligible)!
        XCTAssertEqual(notice.id, "recap:2026-09-28")
        preferences.dismiss(notice)
        XCTAssertNil(WeeklyPlanPreferences(defaults: defaults).eligibleNotice(today: "2026-10-05", eligibility: eligible))
        XCTAssertNil(preferences.eligibleNotice(today: "2026-10-06", eligibility: eligible))
        XCTAssertEqual(WeeklyPlanPreferences(defaults: defaults).goals(weekStart: "2026-09-28"), previous)
        seedWeeklyPlanDemo(defaults: defaults, today: "2026-10-05")
        XCTAssertEqual(preferences.goals(weekStart: "2026-09-28"), previous)
        XCTAssertEqual(preferences.goals(weekStart: "2026-10-05"), next)
    }

    static let eligibilityOracle = """
    count-0:0:7:false
    count-1:1:6:false
    count-6:6:1:false
    count-7:7:0:true
    count-8:8:0:true
    duplicate-seven:7:0:true
    duplicate-one:1:6:false
    missing:6:1:false
    nan:6:1:false
    infinity:6:1:false
    negative-infinity:6:1:false
    negative:6:1:false
    above-range:6:1:false
    zero:7:0:true
    hundred:7:0:true
    day-2026-02-30:6:1:false
    day-2026-2-01:6:1:false
    day-:6:1:false
    day-2026-10-06:6:1:false
    invalid-today:0:7:false
    before-seventh:6:1:false
    incomplete:6:1:false
    subsequent-refresh:7:0:true
    not-processed:0:7:false
    """

    static let expectedOracle = """
    preset:restRoutine:480,5,50,3,5,,any
    preset:activeWeek:480,5,60,4,5,,any
    preset:balancedWeek:450,5,40,3,5,,any
    date:2026-2-01:-:-
    date:2026-02-30:-:-
    date:2024-02-29:2024-02-26:4
    date:2026-10-02:2026-09-28:5
    date:2026-12-31:2026-12-28:4
    date:2027-01-01:2026-12-28:5
    date:0001-01-01:-:6
    date:9999-12-31:9999-12-27:5
    goals:-999:240,1,1,1,1,yes
    goals:0:240,1,1,1,1,yes
    goals:1:240,1,1,1,1,yes
    goals:7:240,7,7,7,7,yes
    goals:8:240,7,8,7,7,yes
    goals:50:240,7,50,7,7,yes
    goals:100:240,7,100,7,7,yes
    goals:101:240,7,100,7,7,yes
    goals:1000:720,7,100,7,7,yes
    snapshot:2026-09-27:any:0/0/-:0/0/-:0/0/-:-
    snapshot:2026-09-27:yes:0/0/-:0/0/-:0/0/-:-
    snapshot:2026-09-27:no:0/0/-:0/0/-:0/0/-:-
    notice:2026-09-27:-
    snapshot:2026-09-28:any:1/1/33:1/1/50:1/1/33:38
    snapshot:2026-09-28:yes:1/1/33:1/1/50:0/1/0:27
    snapshot:2026-09-28:no:1/1/33:1/1/50:1/1/33:38
    notice:2026-09-28:recap:2026-09-21
    snapshot:2026-09-29:any:1/2/33:1/2/50:2/2/66:49
    snapshot:2026-09-29:yes:1/2/33:1/2/50:1/2/33:38
    snapshot:2026-09-29:no:1/2/33:1/2/50:1/2/33:38
    notice:2026-09-29:-
    snapshot:2026-09-30:any:1/2/33:1/2/50:2/3/66:49
    snapshot:2026-09-30:yes:1/2/33:1/2/50:1/2/33:38
    snapshot:2026-09-30:no:1/2/33:1/2/50:1/2/33:38
    notice:2026-09-30:-
    snapshot:2026-10-01:any:2/3/66:2/3/100:3/4/100:88
    snapshot:2026-10-01:yes:2/3/66:2/3/100:1/3/33:66
    snapshot:2026-10-01:no:2/3/66:2/3/100:2/3/66:77
    notice:2026-10-01:-
    snapshot:2026-10-02:any:2/4/66:2/3/100:4/5/100:88
    snapshot:2026-10-02:yes:2/4/66:2/3/100:1/3/33:66
    snapshot:2026-10-02:no:2/4/66:2/3/100:2/3/66:77
    notice:2026-10-02:checkIn:2026-09-28
    snapshot:2026-10-03:any:3/5/100:2/3/100:4/6/100:100
    snapshot:2026-10-03:yes:3/5/100:2/3/100:1/3/33:77
    snapshot:2026-10-03:no:3/5/100:2/3/100:2/3/66:88
    notice:2026-10-03:-
    snapshot:2026-10-04:any:3/6/100:2/4/100:5/7/100:100
    snapshot:2026-10-04:yes:3/6/100:2/4/100:2/4/66:88
    snapshot:2026-10-04:no:3/6/100:2/4/100:2/4/66:88
    notice:2026-10-04:-
    snapshot:2026-10-05:any:3/6/100:2/4/100:5/7/100:100
    snapshot:2026-10-05:yes:3/6/100:2/4/100:2/4/66:88
    snapshot:2026-10-05:no:3/6/100:2/4/100:2/4/66:88
    notice:2026-10-05:recap:2026-09-28
    suggested:2026-10-02:467:68
    suggested:2026-10-05:528:51
    suggested:extreme:720:50
    """
}
