import XCTest
@testable import Strand

final class SleepDisplayPolicyTests: XCTestCase {
    func testDisplayPolicyMatchesOptimizedSwiftOracle() {
        XCTAssertEqual(SleepDisplayOracleCases.render(), SleepDisplayOracleCases.expected)
    }
}
