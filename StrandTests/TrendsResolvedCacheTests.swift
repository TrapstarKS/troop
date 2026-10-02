import XCTest
import StrandDesign
@testable import Strand

@MainActor
final class TrendsResolvedCacheTests: XCTestCase {
    func testUnrelatedPublicationsReusePointsButCalendarPagingAndRestChangesRebuild() {
        let repo = Repository(deviceId: "test-trends-cache")
        let cache = TrendsView.ResolvedCache()
        let current = TrendsWindow.period(days: 7, offset: 0, today: "2026-10-07")!
        let previous = TrendsWindow.period(days: 7, offset: -1, today: "2026-10-07")!
        var builds = 0
        func read(_ window: TrendsWindow, restRevision: Int = 0) -> Double? {
            cache.get(repo: repo, window: window, restRevision: restRevision, locale: "en") {
                builds += 1
                let metric = TrendsView.ResolvedMetric(points: [
                    TrendPoint(date: TrendsWindow.parse(window.start)!, value: Double(builds))
                ])
                return TrendsView.ResolvedMetrics(recovery: metric, hrv: metric, rhr: metric,
                                                 strain: metric, rest: metric)
            }.recovery.points.first?.value
        }
        XCTAssertEqual(read(current), 1)
        repo.objectWillChange.send()
        XCTAssertEqual(read(current), 1)
        XCTAssertEqual(read(previous), 2)
        XCTAssertEqual(read(current), 3)
        XCTAssertEqual(read(current, restRevision: 1), 4)
        XCTAssertEqual(read(current, restRevision: 1), 4)
        XCTAssertEqual(builds, 4)
    }
}
