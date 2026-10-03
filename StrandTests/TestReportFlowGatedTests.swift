import XCTest
@testable import Strand

/// The Report flow must not share an uncleared bundle (spec section 12: review is not skippable).
final class TestReportFlowGatedTests: XCTestCase {

    private func entries() -> [FileExport.BundleEntry] {
        [FileExport.BundleEntry(name: "report.txt", data: Data("x".utf8))]
    }

    func testUnclearedGateBlocksProceed() {
        let gate = ReportReviewGate(entries: entries())
        XCTAssertFalse(TestReportFlow.shouldProceed(gate: gate))
    }

    func testClearedGateAllowsProceed() {
        var gate = ReportReviewGate(entries: entries())
        gate.confirm()
        XCTAssertTrue(TestReportFlow.shouldProceed(gate: gate))
    }

    @MainActor
    func testFailedOutputDoesNotToastOrCopy() async {
        var gate = ReportReviewGate(entries: entries())
        gate.confirm()
        var toasts = [String](), copies = [String]()
        await TestReportFlow.run(profile: .sleep, title: "Sleep", version: "1", platform: "ios", osVersion: "17",
            gate: gate, entries: entries(), showToast: { toasts.append($0) }, copyToPasteboard: { copies.append($0) },
            export: { _, _ in nil })
        XCTAssertTrue(toasts.isEmpty)
        XCTAssertTrue(copies.isEmpty)
    }

    @MainActor
    func testSuccessfulOutputUsesExactReviewedEntriesBeforeToastAndCopy() async {
        let reviewed = entries()
        var gate = ReportReviewGate(entries: reviewed)
        gate.confirm()
        var toasts = [String](), copies = [String](), exports = 0
        await TestReportFlow.run(profile: .sleep, title: "Sleep", version: "1", platform: "ios", osVersion: "17",
            gate: gate, entries: reviewed, showToast: { toasts.append($0) }, copyToPasteboard: { copies.append($0) },
            export: { entries, _ in
                XCTAssertEqual(entries, reviewed)
                XCTAssertTrue(toasts.isEmpty)
                XCTAssertTrue(copies.isEmpty)
                exports += 1
                return URL(fileURLWithPath: "/saved/report.zip")
            })
        XCTAssertEqual(exports, 1)
        XCTAssertEqual(toasts.count, 1)
        XCTAssertEqual(copies, ["x"])
    }
}
