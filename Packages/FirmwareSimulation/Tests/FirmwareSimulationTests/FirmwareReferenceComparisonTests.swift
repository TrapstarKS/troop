import XCTest
import FirmwareSimulation

final class FirmwareReferenceComparisonTests: XCTestCase {
    func testOnlyDifferentLabelsOfTheSameNumericShapeCanBeOrdered() {
        XCTAssertEqual(FirmwareReferenceComparison.compare(observed: "41.9.0.0", reference: "41.10.0.0"), .older)
        XCTAssertEqual(FirmwareReferenceComparison.compare(observed: "50.40.1.0", reference: "50.39.9.9"), .newer)
        XCTAssertEqual(FirmwareReferenceComparison.compare(observed: "41.01.0002.0", reference: "41.1.3.0"), .older)
        XCTAssertEqual(FirmwareReferenceComparison.compare(observed: "41.1.2.0", reference: "41.1.2.0"), .equal)
        XCTAssertEqual(FirmwareReferenceComparison.compare(observed: "41.01.2.0", reference: "41.1.2.0"), .equal)
        XCTAssertEqual(FirmwareReferenceComparison.compare(observed: "41.1.2.0", reference: "50.1.2.0"), .unknown)
        XCTAssertEqual(FirmwareReferenceComparison.compare(observed: "41.1.2", reference: "41.1.2.0"), .unknown)
    }

    func testMissingMalformedAndOverflowingLabelsStayUnknown() {
        let invalid: [String?] = [nil, "", "41", "41..2", ".41.2", "41.2.", " 41.2", "41.2 ",
                                  "41.+2", "41.-2", "41.２", "41.2b", "41.2 / 17.2", "41.9223372036854775808"]
        for label in invalid {
            XCTAssertEqual(FirmwareReferenceComparison.compare(observed: label, reference: "41.3"), .unknown)
            if let label {
                XCTAssertEqual(FirmwareReferenceComparison.compare(observed: "41.3", reference: label), .unknown)
            }
        }
        XCTAssertEqual(FirmwareReferenceComparison.compare(observed: "41.9223372036854775806", reference: "41.9223372036854775807"), .older)
    }
}
