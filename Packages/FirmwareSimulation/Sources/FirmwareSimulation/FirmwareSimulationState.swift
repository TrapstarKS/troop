/// In-memory mock steps only. No device, transport, image or side effect is available to this reducer.
public struct FirmwareSimulationState: Equatable, Sendable {
    public enum Phase: String, CaseIterable, Sendable {
        case idle, check, download, verify, transfer, reboot, done, failed, cancelled

        public var isActive: Bool {
            switch self {
            case .check, .download, .verify, .transfer, .reboot: return true
            default: return false
            }
        }
    }

    public enum Failure: String, CaseIterable, Sendable {
        case prerequisites, verification, timeout, disconnected, mockFailure
    }

    public enum Event: Equatable, Sendable {
        case start
        case advance(runID: Int, phase: Phase)
        case progress(runID: Int, phase: Phase, percent: Int)
        case pause(runID: Int)
        case resume(runID: Int)
        case fail(runID: Int, phase: Phase, reason: Failure)
        case cancel(runID: Int)
        case reset
    }

    public private(set) var phase: Phase = .idle
    public private(set) var runID: Int = 0
    public private(set) var progress: Int = 0
    public private(set) var paused: Bool = false
    public private(set) var checkpoint: Phase?
    public private(set) var failure: Failure?

    public init() {}

    public mutating func send(_ event: Event) {
        switch event {
        case .start:
            guard !phase.isActive else { return }
            newRun(phase: .check)
        case .reset:
            newRun(phase: .idle)
        case let .advance(id, expected):
            guard id == runID, phase == expected, phase.isActive, !paused else { return }
            switch phase {
            case .check: phase = .download
            case .download: phase = .verify
            case .verify: phase = .transfer
            case .transfer: phase = .reboot
            case .reboot: phase = .done
            default: return
            }
            progress = phase == .done ? 100 : 0
            checkpoint = nil
        case let .progress(id, expected, percent):
            guard id == runID, phase == expected, !paused,
                  phase == .download || phase == .transfer else { return }
            progress = max(progress, min(100, max(0, percent)))
        case let .pause(id):
            guard id == runID, phase.isActive else { return }
            paused = true
        case let .resume(id):
            guard id == runID else { return }
            if phase.isActive && paused {
                paused = false
            } else if phase == .failed || phase == .cancelled, let checkpoint {
                phase = checkpoint
                self.checkpoint = nil
                failure = nil
                paused = false
            }
        case let .fail(id, expected, reason):
            guard id == runID, phase == expected, phase.isActive else { return }
            checkpoint = phase
            failure = reason
            phase = .failed
            paused = false
        case let .cancel(id):
            guard id == runID, phase.isActive || phase == .failed else { return }
            if phase.isActive { checkpoint = phase }
            phase = .cancelled
            failure = nil
            paused = false
        }
    }

    private mutating func newRun(phase: Phase) {
        runID += 1
        self.phase = phase
        progress = 0
        paused = false
        checkpoint = nil
        failure = nil
    }
}
