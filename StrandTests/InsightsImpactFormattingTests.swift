import XCTest
@testable import Strand

final class InsightsImpactFormattingTests: XCTestCase {
    func testSmallChangesKeepDirectionAndRoundingMatchesOracle() {
        let cases: [Double] = [0, -0.0, 0.00001, -0.00001, 0.49, -0.49, 0.5, -0.5, 0.99, -0.99, 1, -1, 1.4999999999999998, -1.4999999999999998, 1.5, -1.5, 15.5, -15.5, 100, -100, .nan, .infinity, -.infinity]
        XCTAssertEqual(cases.map(InsightsImpactFormatting.percentage).joined(separator: "\n"), """
        0%
        0%
        +<1%
        −<1%
        +<1%
        −<1%
        +<1%
        −<1%
        +<1%
        −<1%
        +1%
        −1%
        +1%
        −1%
        +2%
        −2%
        +16%
        −16%
        +100%
        −100%
        —
        —
        —
        """)
    }
}
