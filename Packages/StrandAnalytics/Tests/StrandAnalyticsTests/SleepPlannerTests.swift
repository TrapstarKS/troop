import Foundation
import XCTest
@testable import StrandAnalytics

final class SleepPlannerTests: XCTestCase {
    /// Verbatim standalone swiftc -O oracle stdout, also pinned by the Kotlin twin.
    func testPlannerAndAlarmPolicyMatchStandaloneOracle() {
        var lines: [String] = []
        let planCases: [[Int]] = [
            [480, 0, 100, 420, 30, 0],
            [480, 60, 85, 420, 30, 3],
            [480, 120, 70, 420, 45, 10],
            [480, 59, 85, 420, 30, 3],
            [480, 60, 85, 420, 30, 2],
            [300, 0, 85, 255, 0, 3],
            [301, 0, 85, 256, 0, 3],
            [301, 0, 70, 211, 1, 3],
            [302, 0, 70, 211, 0, 3],
            [660, 120, 100, 780, 120, 3],
            [660, 120, 100, 900, 120, 3],
            [660, 120, 100, 1439, 120, 3],
            [0, -1, 0, -1, -1, -1],
            [-300, -120, 101, 2000, 200, 3],
            [Int.min, Int.min, Int.min, Int.min, Int.min, Int.min],
            [Int.max, Int.max, Int.max, Int.max, Int.max, Int.max],
            [299, 119, 70, 0, 120, 3],
            [661, 121, 85, 1439, 0, 3],
            [479, 1, 85, 408, 120, 3],
            [479, 0, 85, 407, 0, 3],
            [480, 0, 70, 336, 0, 3],
            [481, 0, 70, 336, 0, 3],
            [480, 60, 100, 539, 0, 3],
            [480, 60, 100, 540, 1, 3],
            [480, 60, 100, 541, 1, 3],
        ]

        for (index, input) in planCases.enumerated() {
            let plan = SleepPlanner.plan(
                baseNeedMinutes: input[0], debtMinutes: input[1], goalPercent: input[2],
                wakeMinutes: input[3], leadMinutes: input[4], historyNights: input[5]
            )
            lines.append("plan\(index):\(plan.needMinutes),\(plan.debtMinutes),\(plan.targetSleepMinutes),\(plan.bedtimeMinutes),\(plan.bedtimeDayShift),\(plan.reminderMinutes),\(plan.reminderDayShift),\(plan.wakeMinutes),\(plan.historyReady),\(plan.debtNudge)")
        }

        let weekdayCases: [(Int, [Int: Int], Int)] = [
            (1, [1: 85, 7: 70], 100),
            (7, [1: 85, 7: 70], 100),
            (2, [1: 85], 70),
            (3, [3: 0], 85),
            (0, [0: 70], 85),
            (8, [8: 70], 85),
            (4, [:], 10),
            (Int.min, [Int.min: 70], 100),
            (Int.max, [Int.max: 70], 100),
        ]
        for (index, input) in weekdayCases.enumerated() {
            lines.append("weekday\(index):\(SleepPlanner.weekdayGoal(input.0, overrides: input.1, defaultPercent: input.2))")
        }
        lines.append("goals:\(SleepPlannerGoal.allCases.map { String($0.rawValue) }.joined(separator: ","))")

        let quietCases: [(Int, Bool, Int, Int)] = [
            (539, true, 540, 1020),
            (540, true, 540, 1020),
            (1019, true, 540, 1020),
            (1020, true, 540, 1020),
            (60, true, 1320, 420),
            (419, true, 1320, 420),
            (420, true, 1320, 420),
            (1319, true, 1320, 420),
            (1320, true, 1320, 420),
            (1320, false, 1320, 420),
            (0, true, 0, 0),
            (1439, true, 1439, 1439),
            (-1, true, -1, 300),
            (Int.min, true, Int.min, Int.max),
            (Int.max, true, Int.min, Int.max),
            (Int.min, true, Int.max, Int.min),
            (Int.max, true, Int.max, Int.min),
            (Int.max, true, Int.max, Int.max),
        ]
        for (index, input) in quietCases.enumerated() {
            lines.append("quiet\(index):\(PlannerAlarmPolicy.isQuietMinute(minute: input.0, enabled: input.1, startMinutes: input.2, endMinutes: input.3))")
        }

        let instantFormatter = ISO8601DateFormatter()
        let skipCases: [(String, String, String)] = [
            ("2026-10-01|420", "2026-10-02T07:00:00Z", "UTC"),
            ("2026-10-03|0", "2026-10-02T23:59:00Z", "UTC"),
            ("2026-10-02|419", "2026-10-02T07:00:00Z", "UTC"),
            ("2026-10-02|420", "2026-10-02T07:00:00Z", "UTC"),
            ("2026-10-02|421", "2026-10-02T07:00:00Z", "UTC"),
            ("2026-12-31|1439", "2027-01-01T00:00:00Z", "UTC"),
            ("2027-01-01|0", "2026-12-31T23:59:00Z", "UTC"),
            ("2026-10-02|100", "2026-10-02T01:39:00Z", "UTC"),
            ("2026-10-02|99", "2026-10-02T01:40:00Z", "UTC"),
            ("", "2026-10-02T07:00:00Z", "UTC"),
            ("bad", "2026-10-02T07:00:00Z", "UTC"),
            ("2026-10-02", "2026-10-02T07:00:00Z", "UTC"),
            ("2026-10-02|420|bad", "2026-10-02T07:00:00Z", "UTC"),
            ("2026-1-02|420", "2026-10-02T07:00:00Z", "UTC"),
            ("2026_10_02|420", "2026-10-02T07:00:00Z", "UTC"),
            ("2026-10-02|x", "2026-10-02T07:00:00Z", "UTC"),
            ("2026-10-02|-1", "2026-10-02T07:00:00Z", "UTC"),
            ("2026-10-02|1440", "2026-10-02T07:00:00Z", "UTC"),
            ("2026-10-02|0420", "2026-10-02T07:00:00Z", "UTC"),
            ("2026-10-02|+420", "2026-10-02T07:00:00Z", "UTC"),
            ("2026-10-02|420 ", "2026-10-02T07:00:00Z", "UTC"),
            ("2026-10-02|0", "2026-10-02T00:00:00Z", "UTC"),
            ("2026-10-02|1439", "2026-10-02T23:59:00Z", "UTC"),
            ("2026-10-02|999999999999999999999", "2026-10-02T07:00:00Z", "UTC"),
            ("２０２６-10-02|420", "2026-10-02T07:00:00Z", "UTC"),
            ("2026-02-30|420", "2026-02-28T07:00:00Z", "UTC"),
            ("1900-02-29|420", "1900-02-28T07:00:00Z", "UTC"),
            ("2000-02-29|420", "2000-02-28T07:00:00Z", "UTC"),
            ("2026-13-01|420", "2026-12-31T07:00:00Z", "UTC"),
            ("0000-01-01|420", "2026-10-02T07:00:00Z", "UTC"),
            ("2026-10-02|420", "2026-10-02T07:00:01Z", "UTC"),
            ("2026-10-02|420", "2026-10-02T07:00:59Z", "UTC"),
            ("2026-11-01|90", "2026-11-01T05:31:00Z", "America/New_York"),
            ("2026-11-01|90", "2026-11-01T06:30:00Z", "America/New_York"),
            ("2026-11-01|90", "2026-11-01T06:31:00Z", "America/New_York"),
            ("2026-11-01|90", "2026-11-01T05:30:00Z", "America/New_York"),
        ]
        for (index, input) in skipCases.enumerated() {
            let now = instantFormatter.date(from: input.1)!
            var clock = Calendar(identifier: .gregorian)
            clock.timeZone = TimeZone(identifier: input.2)!
            let pending = PlannerAlarmPolicy.isSkipPending(skippedOccurrence: input.0, from: now, calendar: clock)
            lines.append("skip\(index):\(pending),\(Int((now.timeIntervalSince1970 * 1000).rounded()))")
        }

        let dateCases: [[Int]] = [
            [3, 8, 150, 480],
            [11, 1, 90, 480],
            [3, 8, 420, 480],
            [11, 1, 420, 480],
            [3, 8, 0, 480],
            [11, 1, 0, 480],
            [3, 8, -1, 0],
            [11, 1, Int.max, Int.max],
            [3, 8, 420, Int.min],
            [11, 1, 420, 0],
        ]
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/New_York")!
        for (index, input) in dateCases.enumerated() {
            let on = calendar.date(from: DateComponents(year: 2026, month: input[0], day: input[1],
                                                       hour: 12, minute: 34, second: 42, nanosecond: 123_000_000))!
            let wake = SleepPlanner.wakeDate(minutes: input[2], on: on, calendar: calendar)!
            let bedtime = SleepPlanner.bedtime(wake: wake, targetSleepMinutes: input[3])
            lines.append("dates\(index):\(Int((wake.timeIntervalSince1970 * 1000).rounded())),\(Int((bedtime.timeIntervalSince1970 * 1000).rounded())),\(Int((on.timeIntervalSince1970 * 1000).rounded()))")
        }

        let firstFoldNow = instantFormatter.date(from: "2026-11-01T05:31:00Z")!
        let foldWake = SleepPlanner.wakeDate(minutes: 90, on: firstFoldNow, calendar: calendar)!
        let foldBedtime = SleepPlanner.bedtime(wake: foldWake, targetSleepMinutes: 480)
        lines.append("foldCurrent:\(Int((foldWake.timeIntervalSince1970 * 1000).rounded())),\(Int((foldBedtime.timeIntervalSince1970 * 1000).rounded())),\(Int((firstFoldNow.timeIntervalSince1970 * 1000).rounded()))")

        let preferredCalendarCases: [(String, String, String)] = [
            ("2024-02-29T05:30:00Z", "2024-02-29T05:29:00Z", "America/New_York"),
            ("2024-02-29T05:30:00Z", "2024-02-29T05:30:00Z", "America/New_York"),
            ("2024-02-29T05:30:00Z", "2024-02-29T05:31:00Z", "America/New_York"),
            ("2024-03-01T02:30:00Z", "2024-03-01T02:29:00Z", "America/New_York"),
            ("2024-02-28T17:05:00Z", "2024-02-28T17:04:00Z", "Asia/Bangkok"),
            ("2026-11-01T06:30:00Z", "2026-11-01T05:31:00Z", "America/New_York"),
            ("2026-03-08T07:30:00Z", "2026-03-08T07:29:00Z", "America/New_York"),
        ]
        for (index, input) in preferredCalendarCases.enumerated() {
            let wake = instantFormatter.date(from: input.0)!
            let now = instantFormatter.date(from: input.1)!
            var preferred = Calendar(identifier: .buddhist)
            preferred.timeZone = TimeZone(identifier: input.2)!
            let key = PlannerAlarmPolicy.occurrenceKey(for: wake, calendar: preferred)
            let pending = PlannerAlarmPolicy.isSkipPending(skippedOccurrence: key, from: now, calendar: preferred)
            lines.append("preferred\(index):\(preferred.component(.year, from: wake)),\(key),\(pending),\(Int((wake.timeIntervalSince1970 * 1000).rounded())),\(Int((now.timeIntervalSince1970 * 1000).rounded()))")
        }

        let occurrenceCases: [[Int]] = [
            [2026, 10, 2, 420],
            [2026, 1, 3, 0],
            [2028, 2, 29, 1439],
            [2026, 12, 31, 420],
            [2027, 1, 1, 420],
            [9, 1, 2, 5],
            [2026, 10, 2, 421],
        ]
        for (index, input) in occurrenceCases.enumerated() {
            lines.append("occurrence\(index):\(PlannerAlarmPolicy.occurrenceKey(year: input[0], month: input[1], day: input[2], minutes: input[3]))")
        }

        let expected = """
        plan0:480,0,480,1380,-1,1350,-1,420,false,false
        plan1:540,60,459,1401,-1,1371,-1,420,true,true
        plan2:600,120,420,0,0,1395,-1,420,true,true
        plan3:539,59,459,1401,-1,1371,-1,420,true,false
        plan4:540,60,459,1401,-1,1371,-1,420,false,false
        plan5:300,0,255,0,0,0,0,255,true,false
        plan6:301,0,256,0,0,0,0,256,true,false
        plan7:301,0,211,0,0,1439,-1,211,true,false
        plan8:302,0,212,1439,-1,1439,-1,211,true,false
        plan9:780,120,780,0,0,1320,-1,780,true,true
        plan10:780,120,780,120,0,0,0,900,true,true
        plan11:780,120,780,659,0,539,0,1439,true,true
        plan12:300,0,300,1140,-1,1140,-1,0,false,false
        plan13:300,0,300,1139,0,1019,0,1439,true,false
        plan14:300,0,300,1140,-1,1140,-1,0,false,false
        plan15:780,120,780,659,0,539,0,1439,true,true
        plan16:419,119,294,1146,-1,1026,-1,0,true,true
        plan17:780,120,663,776,0,776,0,1439,true,true
        plan18:480,1,408,0,0,1320,-1,408,true,false
        plan19:479,0,408,1439,-1,1439,-1,407,true,false
        plan20:480,0,336,0,0,0,0,336,true,false
        plan21:481,0,337,1439,-1,1439,-1,336,true,false
        plan22:540,60,540,1439,-1,1439,-1,539,true,true
        plan23:540,60,540,0,0,1439,-1,540,true,true
        plan24:540,60,540,1,0,0,0,541,true,true
        weekday0:85
        weekday1:70
        weekday2:70
        weekday3:100
        weekday4:85
        weekday5:85
        weekday6:100
        weekday7:100
        weekday8:100
        goals:100,85,70
        quiet0:false
        quiet1:true
        quiet2:true
        quiet3:false
        quiet4:true
        quiet5:true
        quiet6:false
        quiet7:false
        quiet8:true
        quiet9:false
        quiet10:false
        quiet11:false
        quiet12:true
        quiet13:true
        quiet14:false
        quiet15:false
        quiet16:true
        quiet17:false
        skip0:false,1790924400000
        skip1:true,1790985540000
        skip2:false,1790924400000
        skip3:true,1790924400000
        skip4:true,1790924400000
        skip5:false,1798761600000
        skip6:true,1798761540000
        skip7:true,1790905140000
        skip8:false,1790905200000
        skip9:false,1790924400000
        skip10:false,1790924400000
        skip11:false,1790924400000
        skip12:false,1790924400000
        skip13:false,1790924400000
        skip14:false,1790924400000
        skip15:false,1790924400000
        skip16:false,1790924400000
        skip17:false,1790924400000
        skip18:false,1790924400000
        skip19:false,1790924400000
        skip20:false,1790924400000
        skip21:true,1790899200000
        skip22:true,1790985540000
        skip23:false,1790924400000
        skip24:false,1790924400000
        skip25:false,1772262000000
        skip26:false,-2203952400000
        skip27:true,951721200000
        skip28:false,1798700400000
        skip29:false,1790924400000
        skip30:false,1790924401000
        skip31:false,1790924459000
        skip32:true,1793511060000
        skip33:true,1793514600000
        skip34:false,1793514660000
        skip35:true,1793511000000
        dates0:1772955000000,1772926200000,1772987682123
        dates1:1793514600000,1793485800000,1793554482123
        dates2:1772967600000,1772938800000,1772987682123
        dates3:1793534400000,1793505600000,1793554482123
        dates4:1772946000000,1772917200000,1772987682123
        dates5:1793505600000,1793476800000,1793554482123
        dates6:1772946000000,1772946000000,1772987682123
        dates7:1793595540000,1793548740000,1793554482123
        dates8:1772967600000,1772967600000,1772987682123
        dates9:1793534400000,1793534400000,1793554482123
        foldCurrent:1793514600000,1793485800000,1793511060000
        preferred0:2567,2024-02-29|30,true,1709184600000,1709184540000
        preferred1:2567,2024-02-29|30,true,1709184600000,1709184600000
        preferred2:2567,2024-02-29|30,false,1709184600000,1709184660000
        preferred3:2567,2024-02-29|1290,true,1709260200000,1709260140000
        preferred4:2567,2024-02-29|5,true,1709139900000,1709139840000
        preferred5:2569,2026-11-01|90,true,1793514600000,1793511060000
        preferred6:2569,2026-03-08|210,true,1772955000000,1772954940000
        occurrence0:2026-10-02|420
        occurrence1:2026-01-03|0
        occurrence2:2028-02-29|1439
        occurrence3:2026-12-31|420
        occurrence4:2027-01-01|420
        occurrence5:0009-01-02|5
        occurrence6:2026-10-02|421
        """
        XCTAssertEqual(lines.joined(separator: "\n"), expected)
    }
}
