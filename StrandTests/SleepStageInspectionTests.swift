import XCTest
import StrandDesign
import WhoopStore
@testable import Strand

final class SleepStageInspectionTests: XCTestCase {
    private func timestamp(_ value: String) -> Int {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        formatter.timeZone = .current
        return Int(formatter.date(from: value)!.timeIntervalSince1970)
    }

    func testConsistencyUsesOneMainNightPerDayAndExcludesNaps() {
        let sessions = (1...3).flatMap { day -> [CachedSleepSession] in
            let date = String(format: "2026-09-%02d", day)
            return [("00:00", "07:00"), ("14:00", "14:30")].map { start, end in
                CachedSleepSession(startTs: timestamp("\(date) \(start)"), endTs: timestamp("\(date) \(end)"),
                                   efficiency: nil, restingHr: nil, avgHrv: nil, stagesJSON: nil)
            }
        }
        let nights = SleepView.mainNightConsistencySessions(sessions, habitualMidsleepSec: nil)
        XCTAssertEqual(nights.count, 3)
        let consistency = SleepModel.consistencySeries(days: [], sleeps: nights, importedSleep: [:])
        XCTAssertEqual(consistency.latest ?? -1, 100, accuracy: 1e-9)
    }

    func testHistoricalMetricsUseTheSelectedNightClock() {
        let day = DailyMetric(day: "2026-09-03", totalSleepMin: 420, efficiency: 90,
                              deepMin: 60, remMin: 90, lightMin: 270, disturbances: nil,
                              restingHr: nil, avgHrv: nil, recovery: nil, strain: nil,
                              exerciseCount: nil, spo2Pct: nil, skinTempDevC: nil, respRateBpm: 14.2)
        let selectedClock = Date(timeIntervalSince1970: TimeInterval(timestamp("2026-09-03 12:00")))
        let respiratory = SleepModel.respiratorySeries(days: [day], now: selectedClock)
        XCTAssertEqual(respiratory.latest ?? -1, 14.2, accuracy: 1e-9)
        XCTAssertNil(respiratory.latestDay)
        let sufficiency = SleepModel.hoursVsNeededSeries(days: [day], importedSleep: [:], now: selectedClock)
        XCTAssertEqual(sufficiency.latest ?? -1, 420 / 450 * 100, accuracy: 1e-9)
        XCTAssertNil(sufficiency.latestDay)
    }

    func testMissingSelectedDayDoesNotInheritPreviousDebt() {
        XCTAssertNil(SleepView.selectedDebtMin(imported: nil, asleep: nil, latest: 90))
        XCTAssertNil(SleepView.selectedDebtMin(imported: nil, asleep: 0, latest: 90))
        XCTAssertEqual(SleepView.selectedDebtMin(imported: 0, asleep: nil, latest: 90), 0)
        XCTAssertEqual(SleepView.selectedDebtMin(imported: nil, asleep: 420, latest: 90), 90)
    }

    func testExactStageBoundariesAndMissingIntervals() {
        let intervals = [SleepInterval(stage: .light, start: 0, end: 60),
                         SleepInterval(stage: .deep, start: 60, end: 120),
                         SleepInterval(stage: .rem, start: 180, end: 240)]
        XCTAssertEqual(SleepStageInspection.resolve(fraction: 0.25, span: 240, intervals: intervals)?.stage, .deep)
        let gap = SleepStageInspection.resolve(fraction: 0.625, span: 240, intervals: intervals)
        XCTAssertEqual(gap?.seconds, 150)
        XCTAssertNil(gap?.stage)
        XCTAssertEqual(SleepStageInspection.resolve(fraction: -1, span: 240, intervals: intervals)?.seconds, 0)
        XCTAssertEqual(SleepStageInspection.resolve(fraction: 2, span: 240, intervals: intervals)?.seconds, 240)
        XCTAssertNil(SleepStageInspection.resolve(fraction: .nan, span: 240, intervals: intervals))
        XCTAssertNil(SleepStageInspection.resolve(fraction: 0.5, span: 0, intervals: intervals))
    }
}
