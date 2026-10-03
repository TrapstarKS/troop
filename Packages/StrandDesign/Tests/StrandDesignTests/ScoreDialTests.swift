import XCTest
@testable import StrandDesign

final class ScoreDialTests: XCTestCase {
    func testMissingAndNonfiniteProgressRemainsUnavailable() {
        for value in [nil, Double.nan, Double.infinity, -Double.infinity] {
            XCTAssertNil(ScoreDial.bounded(value))
        }
    }

    func testProgressCannotDrawBeyondTheCircle() {
        XCTAssertEqual(ScoreDial.bounded(-0.2), 0)
        XCTAssertEqual(ScoreDial.bounded(1.2), 1)
        XCTAssertEqual(ScoreDial.bounded(0.85), 0.85)
    }
}
