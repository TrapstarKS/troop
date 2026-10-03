import XCTest
import FirmwareSimulation

// Expected literals are standalone swiftc -O stdout, shared verbatim with the Kotlin tests.
final class FirmwareOracleTests: XCTestCase {
    func testStateTraceMatchesStandaloneSwiftOracle() {
        var state = FirmwareSimulationState()
        var lines: [String] = []
        func trace() {
            lines.append("\(state.runID)|\(state.phase.rawValue)|\(state.progress)|\(state.paused ? 1 : 0)|\(state.checkpoint?.rawValue ?? "-")|\(state.failure?.rawValue ?? "-")")
        }
        func send(_ event: FirmwareSimulationState.Event) {
            state.send(event)
            trace()
        }
        trace()
        send(.start)
        send(.advance(runID: 1, phase: .check))
        send(.progress(runID: 1, phase: .download, percent: -1))
        send(.progress(runID: 1, phase: .download, percent: 55))
        send(.progress(runID: 1, phase: .download, percent: 20))
        send(.pause(runID: 1))
        send(.advance(runID: 1, phase: .download))
        send(.progress(runID: 1, phase: .download, percent: 100))
        send(.resume(runID: 1))
        send(.fail(runID: 1, phase: .download, reason: .prerequisites))
        send(.resume(runID: 1))
        send(.advance(runID: 1, phase: .download))
        send(.fail(runID: 1, phase: .verify, reason: .verification))
        send(.cancel(runID: 1))
        send(.resume(runID: 1))
        send(.advance(runID: 1, phase: .verify))
        send(.progress(runID: 1, phase: .transfer, percent: 120))
        send(.fail(runID: 1, phase: .transfer, reason: .disconnected))
        send(.resume(runID: 1))
        send(.cancel(runID: 1))
        send(.resume(runID: 1))
        send(.advance(runID: 1, phase: .transfer))
        send(.fail(runID: 1, phase: .reboot, reason: .timeout))
        send(.start)
        send(.advance(runID: 1, phase: .check))
        send(.fail(runID: 2, phase: .reboot, reason: .timeout))
        send(.advance(runID: 2, phase: .check))
        send(.fail(runID: 2, phase: .download, reason: .mockFailure))
        send(.reset)
        send(.start)
        send(.advance(runID: 4, phase: .check))
        send(.advance(runID: 4, phase: .download))
        send(.advance(runID: 4, phase: .verify))
        send(.advance(runID: 4, phase: .transfer))
        send(.advance(runID: 4, phase: .reboot))
        send(.resume(runID: 4))
        send(.cancel(runID: 4))
        send(.start)
        XCTAssertEqual(lines.joined(separator: "\n"), """
        0|idle|0|0|-|-
        1|check|0|0|-|-
        1|download|0|0|-|-
        1|download|0|0|-|-
        1|download|55|0|-|-
        1|download|55|0|-|-
        1|download|55|1|-|-
        1|download|55|1|-|-
        1|download|55|1|-|-
        1|download|55|0|-|-
        1|failed|55|0|download|prerequisites
        1|download|55|0|-|-
        1|verify|0|0|-|-
        1|failed|0|0|verify|verification
        1|cancelled|0|0|verify|-
        1|verify|0|0|-|-
        1|transfer|0|0|-|-
        1|transfer|100|0|-|-
        1|failed|100|0|transfer|disconnected
        1|transfer|100|0|-|-
        1|cancelled|100|0|transfer|-
        1|transfer|100|0|-|-
        1|reboot|0|0|-|-
        1|failed|0|0|reboot|timeout
        2|check|0|0|-|-
        2|check|0|0|-|-
        2|check|0|0|-|-
        2|download|0|0|-|-
        2|failed|0|0|download|mockFailure
        3|idle|0|0|-|-
        4|check|0|0|-|-
        4|download|0|0|-|-
        4|verify|0|0|-|-
        4|transfer|0|0|-|-
        4|reboot|0|0|-|-
        4|done|100|0|-|-
        4|done|100|0|-|-
        4|done|100|0|-|-
        5|check|0|0|-|-
        """)
    }

    func testReferenceLabelsMatchStandaloneSwiftOracle() {
        let pairs: [(String?, String)] = [
            (nil, "41.3"), ("", "41.3"), ("41.9.0.0", "41.10.0.0"),
            ("50.40.1.0", "50.39.9.9"), ("41.1.2.0", "41.1.2.0"),
            ("41.01.0002.0", "41.1.2.0"), ("41.01.0002.0", "41.1.3.0"),
            ("41.1.2.0", "50.1.2.0"), ("41.1.2", "41.1.2.0"),
            ("41", "41.3"), ("41..2", "41.3"), (".41.2", "41.3"),
            ("41.2.", "41.3"), (" 41.2", "41.3"), ("41.2 ", "41.3"),
            ("41.+2", "41.3"), ("41.-2", "41.3"), ("41.２", "41.3"),
            ("41.2b", "41.3"), ("41.2 / 17.2", "41.3"),
            ("41.9223372036854775808", "41.3"),
            ("41.9223372036854775806", "41.9223372036854775807"),
            ("41.3", "41.2b"), ("41.3", "41.9223372036854775808"),
        ]
        let lines = pairs.map { observed, reference in
            "\(observed ?? "<nil>")|\(reference)|\(FirmwareReferenceComparison.compare(observed: observed, reference: reference).rawValue)"
        }
        XCTAssertEqual(lines.joined(separator: "\n"), """
        <nil>|41.3|unknown
        |41.3|unknown
        41.9.0.0|41.10.0.0|older
        50.40.1.0|50.39.9.9|newer
        41.1.2.0|41.1.2.0|equal
        41.01.0002.0|41.1.2.0|equal
        41.01.0002.0|41.1.3.0|older
        41.1.2.0|50.1.2.0|unknown
        41.1.2|41.1.2.0|unknown
        41|41.3|unknown
        41..2|41.3|unknown
        .41.2|41.3|unknown
        41.2.|41.3|unknown
         41.2|41.3|unknown
        41.2 |41.3|unknown
        41.+2|41.3|unknown
        41.-2|41.3|unknown
        41.２|41.3|unknown
        41.2b|41.3|unknown
        41.2 / 17.2|41.3|unknown
        41.9223372036854775808|41.3|unknown
        41.9223372036854775806|41.9223372036854775807|older
        41.3|41.2b|unknown
        41.3|41.9223372036854775808|unknown
        """)
    }
}
