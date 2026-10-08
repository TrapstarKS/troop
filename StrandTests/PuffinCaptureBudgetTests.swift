import XCTest
@testable import Strand

final class PuffinCaptureBudgetTests: XCTestCase {
    func testBulkCaptureStopsAtTenMiBAndStaysPaused() {
        var budget = PuffinCaptureBudget()
        XCTAssertTrue(budget.reserve(frameBytes: (10 * 1024 * 1024 - 512) / 2))
        XCTAssertEqual(budget.estimatedBytes, 10 * 1024 * 1024)
        XCTAssertFalse(budget.reserve(frameBytes: 1))
        XCTAssertTrue(budget.isFull)
        XCTAssertFalse(budget.reserve(frameBytes: 0))
        XCTAssertEqual(budget.records, 1)
    }

    func testManySmallFramesRemainWithinBothBounds() {
        var budget = PuffinCaptureBudget()
        for _ in 0..<40_001 { _ = budget.reserve(frameBytes: 1) }
        XCTAssertTrue(budget.isFull)
        XCTAssertLessThanOrEqual(budget.estimatedBytes, 10 * 1024 * 1024)
        XCTAssertLessThanOrEqual(budget.records, 40_000)
    }

    func testOversizedFrameCannotAllocateCaptureRecord() {
        var budget = PuffinCaptureBudget()
        XCTAssertFalse(budget.reserve(frameBytes: Int.max))
        XCTAssertEqual(budget.estimatedBytes, 0)
        XCTAssertEqual(budget.records, 0)
    }
}
