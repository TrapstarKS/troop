import XCTest
import WhoopProtocol
import WhoopStore
import StrandAnalytics
@testable import Strand

@MainActor
final class StressMonitorSurfaceTests: XCTestCase {
    func testTodaySnapshotRescoresWhenPPGRRAndMotionArriveIndependently() async throws {
        let store = try await WhoopStore.inMemory()
        try await store.upsertDevice(id: "whoop-test", mac: nil, name: nil)
        let repo = Repository(deviceId: "whoop-test")
        repo.setStoreForTesting(store)
        StressDayCurve.resetForTest()
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let start = Int(calendar.startOfDay(for: Date()).timeIntervalSince1970) + 9 * 3600
        let now = Date(timeIntervalSince1970: Double(start + 600))
        _ = try await store.insert(Streams(hr: (0..<299).map { HRSample(ts: start + $0, bpm: 70) }),
                                   deviceId: "whoop-test")
        let sparseSnapshot = await StressDayCurve.today(repo: repo, now: now, calendar: calendar)
        let sparse = try XCTUnwrap(sparseSnapshot)
        XCTAssertEqual(sparse.result.monitorReading(latestSampleTs: sparse.latestSampleTs,
                                                    now: start + 600, isToday: true).state, .insufficientSamples)

        _ = try await store.insert(Streams(ppgHr: [PpgHrSample(ts: start + 299, bpm: 70, conf: 0.9)]),
                                   deviceId: "whoop-test")
        let partialSnapshot = await StressDayCurve.today(repo: repo, now: now, calendar: calendar)
        let partial = try XCTUnwrap(partialSnapshot)
        XCTAssertEqual(partial.result.monitorReading(latestSampleTs: partial.latestSampleTs,
                                                     now: start + 600, isToday: true).window?.level, 1.5)

        let rr = (0..<60).map { RRInterval(ts: start + $0, rrMs: $0 % 2 == 0 ? 820 : 780) }
        _ = try await store.insert(Streams(rr: rr), deviceId: "whoop-test")
        let withRRSnapshot = await StressDayCurve.today(repo: repo, now: now, calendar: calendar)
        let withRR = try XCTUnwrap(withRRSnapshot)
        XCTAssertNotNil(withRR.result.hours.first?.rmssd)
        XCTAssertNil(partial.result.hours.first?.rmssd)

        let gravity = (0..<120).map { GravitySample(ts: start + $0, x: $0 % 2 == 0 ? 0.5 : 0, y: 0, z: 1) }
        _ = try await store.insert(Streams(gravity: gravity), deviceId: "whoop-test")
        let movingSnapshot = await StressDayCurve.today(repo: repo, now: now, calendar: calendar)
        let moving = try XCTUnwrap(movingSnapshot)
        XCTAssertEqual(moving.result.monitorReading(latestSampleTs: moving.latestSampleTs,
                                                    now: start + 600, isToday: true).state, .activityExcluded)
    }

    func testMonitorAndTodayUseTheSingleScorerAndReadingResolver() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        for path in ["Strand/Screens/StressMonitorView.swift", "Strand/Screens/StressTodayCurveCard.swift"] {
            let source = try String(contentsOf: root.appendingPathComponent(path), encoding: .utf8)
            XCTAssertTrue(source.contains("StressDayCurve.today("), path)
            XCTAssertTrue(source.contains(".monitorReading("), path)
            XCTAssertFalse(source.contains("windowEnd <= 900 ? latest : nil"), path)
        }
        for path in ["Strand/Screens/TodayView.swift", "Strand/Liquid/LiquidTodayView.swift"] {
            let source = try String(contentsOf: root.appendingPathComponent(path), encoding: .utf8)
            XCTAssertTrue(source.contains("case .stressToday: StressTodayCurveCard()"))
        }
    }
}
