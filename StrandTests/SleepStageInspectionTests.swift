import XCTest
import StrandDesign
@testable import Strand

final class SleepStageInspectionTests: XCTestCase {
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
