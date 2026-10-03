import XCTest
@testable import FirmwareSimulation

final class FirmwareSimulationTests: XCTestCase {
    private let activePhases: [FirmwareSimulationState.Phase] = [.check, .download, .verify, .transfer, .reboot]

    private func state(at phase: FirmwareSimulationState.Phase) -> FirmwareSimulationState {
        var state = FirmwareSimulationState()
        state.send(.start)
        while state.phase != phase {
            state.send(.advance(runID: state.runID, phase: state.phase))
        }
        return state
    }

    func testSuccessAndRestart() {
        var state = FirmwareSimulationState()
        XCTAssertEqual(state.phase, .idle)
        state.send(.start)
        XCTAssertEqual(state.runID, 1)
        for phase in activePhases {
            XCTAssertEqual(state.phase, phase)
            state.send(.advance(runID: state.runID, phase: phase))
        }
        XCTAssertEqual(state.phase, .done)
        XCTAssertEqual(state.progress, 100)
        XCTAssertNil(state.checkpoint)
        state.send(.start)
        XCTAssertEqual(state.phase, .check)
        XCTAssertEqual(state.runID, 2)
        XCTAssertEqual(state.progress, 0)
    }

    func testEveryPhaseCanPauseAndResumeWithoutAdvancing() {
        for phase in activePhases {
            var state = state(at: phase)
            let original = state
            state.send(.pause(runID: state.runID))
            XCTAssertTrue(state.paused)
            let paused = state
            state.send(.advance(runID: state.runID, phase: phase))
            state.send(.progress(runID: state.runID, phase: phase, percent: 100))
            state.send(.start)
            XCTAssertEqual(state, paused)
            state.send(.resume(runID: state.runID))
            XCTAssertEqual(state, original)
        }
    }

    func testEveryFailureAndPhasePreservesAResumableCheckpoint() {
        for phase in activePhases {
            for reason in FirmwareSimulationState.Failure.allCases {
                var state = state(at: phase)
                state.send(.progress(runID: state.runID, phase: phase, percent: 60))
                let original = state
                state.send(.pause(runID: state.runID))
                state.send(.fail(runID: state.runID, phase: state.phase, reason: reason))
                XCTAssertEqual(state.phase, .failed)
                XCTAssertEqual(state.failure, reason)
                XCTAssertEqual(state.checkpoint, phase)
                XCTAssertFalse(state.paused)
                XCTAssertEqual(state.progress, original.progress)
                let failed = state
                state.send(.advance(runID: state.runID, phase: phase))
                state.send(.progress(runID: state.runID, phase: phase, percent: 100))
                state.send(.pause(runID: state.runID))
                state.send(.fail(runID: state.runID, phase: state.phase, reason: .mockFailure))
                XCTAssertEqual(state, failed)
                state.send(.resume(runID: state.runID))
                XCTAssertEqual(state, original)
            }
        }
    }

    func testEveryPhaseCanCancelAndExplicitlyResume() {
        for phase in activePhases {
            for pause in [false, true] {
                var state = state(at: phase)
                state.send(.progress(runID: state.runID, phase: phase, percent: 45))
                let original = state
                if pause { state.send(.pause(runID: state.runID)) }
                state.send(.cancel(runID: state.runID))
                XCTAssertEqual(state.phase, .cancelled)
                XCTAssertEqual(state.checkpoint, phase)
                XCTAssertFalse(state.paused)
                let cancelled = state
                state.send(.advance(runID: state.runID, phase: phase))
                state.send(.progress(runID: state.runID, phase: phase, percent: 100))
                state.send(.cancel(runID: state.runID))
                XCTAssertEqual(state, cancelled)
                state.send(.resume(runID: state.runID))
                XCTAssertEqual(state, original)
            }
        }
    }

    func testCancelAfterFailureKeepsCheckpointButClearsFailure() {
        var state = state(at: .transfer)
        state.send(.progress(runID: state.runID, phase: state.phase, percent: 75))
        state.send(.fail(runID: state.runID, phase: state.phase, reason: .disconnected))
        state.send(.cancel(runID: state.runID))
        XCTAssertEqual(state.phase, .cancelled)
        XCTAssertEqual(state.checkpoint, .transfer)
        XCTAssertNil(state.failure)
        state.send(.resume(runID: state.runID))
        XCTAssertEqual(state.phase, .transfer)
        XCTAssertEqual(state.progress, 75)
    }

    func testProgressIsBoundedAndMonotonicInMockDataPhasesOnly() {
        for phase in activePhases {
            var state = state(at: phase)
            let acceptsProgress = phase == .download || phase == .transfer
            for (input, expected) in [(-1, 0), (35, 35), (20, 35), (101, 100), (0, 100)] {
                state.send(.progress(runID: state.runID, phase: phase, percent: input))
                XCTAssertEqual(state.progress, acceptsProgress ? expected : 0)
            }
        }
    }

    func testWrongPhaseEventsCannotAdvanceOrChangeProgress() {
        for phase in activePhases {
            var state = state(at: phase)
            let original = state
            for other in FirmwareSimulationState.Phase.allCases where other != phase {
                state.send(.advance(runID: state.runID, phase: other))
                state.send(.progress(runID: state.runID, phase: other, percent: 100))
                state.send(.fail(runID: state.runID, phase: other, reason: .mockFailure))
                XCTAssertEqual(state, original)
            }
        }
    }

    func testStaleRunEventsCannotTouchANewRun() {
        var state = state(at: .transfer)
        let staleID = state.runID
        state.send(.reset)
        state.send(.start)
        XCTAssertEqual(state.runID, 3)
        let original = state
        for event in [
            FirmwareSimulationState.Event.advance(runID: staleID, phase: state.phase),
            .progress(runID: staleID, phase: state.phase, percent: 100),
            .pause(runID: staleID), .resume(runID: staleID),
            .fail(runID: staleID, phase: state.phase, reason: .timeout), .cancel(runID: staleID),
        ] {
            state.send(event)
            XCTAssertEqual(state, original)
        }
    }

    func testDoneAndIdleIgnoreRunEvents() {
        for phase in [FirmwareSimulationState.Phase.idle, .done] {
            var state = phase == .idle ? FirmwareSimulationState() : state(at: .done)
            let original = state
            for event in [
                FirmwareSimulationState.Event.advance(runID: state.runID, phase: phase),
                .progress(runID: state.runID, phase: phase, percent: 100),
                .pause(runID: state.runID), .resume(runID: state.runID),
                .fail(runID: state.runID, phase: state.phase, reason: .mockFailure), .cancel(runID: state.runID),
            ] {
                state.send(event)
                XCTAssertEqual(state, original)
            }
        }
    }

    func testResetClearsEveryReachableState() {
        for phase in FirmwareSimulationState.Phase.allCases {
            var state: FirmwareSimulationState
            switch phase {
            case .idle: state = FirmwareSimulationState()
            case .failed:
                state = self.state(at: .download)
                state.send(.fail(runID: state.runID, phase: state.phase, reason: .verification))
            case .cancelled:
                state = self.state(at: .download)
                state.send(.cancel(runID: state.runID))
            default: state = self.state(at: phase)
            }
            let originalID = state.runID
            state.send(.reset)
            XCTAssertEqual(state.phase, .idle)
            XCTAssertEqual(state.runID, originalID + 1)
            XCTAssertEqual(state.progress, 0)
            XCTAssertFalse(state.paused)
            XCTAssertNil(state.checkpoint)
            XCTAssertNil(state.failure)
        }
    }

    func testNewRunFromFailureOrCancellationDropsCheckpoint() {
        for cancel in [false, true] {
            var state = state(at: .transfer)
            state.send(.progress(runID: state.runID, phase: state.phase, percent: 60))
            state.send(cancel ? .cancel(runID: state.runID) : .fail(runID: state.runID, phase: state.phase, reason: .timeout))
            state.send(.start)
            XCTAssertEqual(state.phase, .check)
            XCTAssertEqual(state.runID, 2)
            XCTAssertEqual(state.progress, 0)
            XCTAssertNil(state.checkpoint)
            XCTAssertNil(state.failure)
        }
    }
}
