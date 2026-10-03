import Foundation
import XCTest
@testable import StrandAnalytics

final class PlannerAdviceLedgerTests: XCTestCase {
    func testAdviceLedgerMatchesStandaloneOracle() {
        var lines: [String] = []
        let first = "2026-10-03|420"
        let second = "2026-10-04|420"
        let queues: [[String: Int64]] = [
            [second: 2000, first: 1000, "bad": 99, "2026-02-30|420": 100, "2026-10-03|0420": 100, "2026-10-05|420": 0],
            ["2000-02-29|0": 1, "2026-10-03|1439": Int64.max, "2026-10-03|1": -1],
        ]
        for (index, input) in queues.enumerated() {
            lines.append("queue\(index):\(PlannerAlarmPolicy.adviceQueue(input).replacingOccurrences(of: "\n", with: "/"))")
        }
        let cases: [(String, String, Int64, String)] = [
            ("\(first)=1000", "", 999, "2026-10-03"),
            ("\(first)=1000", "", 1000, "2026-10-03"),
            ("\(second)=2000", first, 2000, "2026-10-03"),
            ("\(second)=2000", first, 2000, "2026-10-04"),
            ("", "bad\n\(second)\n\(first)\n\(first)", 2000, "2026-10-03"),
            ("\(first)=+1000\n\(first)=01000\n\(first)=1000 \n\(first)=0\n\(first)=-1\n\(first)=1000=2\n\(first)=9223372036854775808", "", 2000, "2026-10-03"),
            ("\(first)=1000\n\(first)=1000", first, 2000, "2026-10-03"),
            ("\(first)=9223372036854775807", "", Int64.max, "2026-10-03"),
            ("\(first)=1", first, Int64.min, "2026-10-03"),
            ("\(first)=1000\n\(second)=2000", first, 2000, "2026-10-05"),
        ]
        for (index, input) in cases.enumerated() {
            lines.append("handled\(index):\(PlannerAlarmPolicy.handledAdvice(queued: input.0, previous: input.1, nowEpoch: input.2, localDay: input.3).replacingOccurrences(of: "\n", with: "/"))")
        }
        let expected = """
        queue0:2026-10-03|420=1000/2026-10-04|420=2000
        queue1:2000-02-29|0=1/2026-10-03|1439=9223372036854775807
        handled0:
        handled1:2026-10-03|420
        handled2:2026-10-03|420/2026-10-04|420
        handled3:2026-10-04|420
        handled4:2026-10-03|420/2026-10-04|420
        handled5:
        handled6:2026-10-03|420
        handled7:2026-10-03|420
        handled8:2026-10-03|420
        handled9:
        """
        XCTAssertEqual(lines.joined(separator: "\n"), expected)
    }
}
