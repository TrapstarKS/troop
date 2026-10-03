import SwiftUI
import StrandDesign
import FirmwareSimulation

enum FirmwareSimulationPreference {
    static let enabledKey = "noop.firmwareSimulationEnabled"
}

struct FirmwareView: View {
    let deviceName: String
    let observedFirmware: String?

    @Environment(\.dismiss) private var dismiss
    @AppStorage(FirmwareSimulationPreference.enabledKey) private var simulationEnabled = false
    @State private var reference = ""

    var body: some View {
        ScreenScaffold(title: "Firmware", trailing: {
            Button("Close") { dismiss() }
                .font(StrandFont.body)
                .tint(StrandPalette.accent)
        }) {
            VStack(alignment: .leading, spacing: NoopMetrics.sectionSpacing) {
                observedCard
                guidanceCard
                if simulationEnabled { FirmwareSimulationCard() }
            }
        }
        #if os(macOS)
        .frame(minWidth: NoopMetrics.detailSheetMinWidth, minHeight: NoopMetrics.detailSheetMinHeight)
        #endif
    }

    private var observedCard: some View {
        NoopCard {
            VStack(alignment: .leading, spacing: NoopMetrics.cardInnerSpacing) {
                Text(verbatim: deviceName)
                    .font(StrandFont.headline)
                    .foregroundStyle(StrandPalette.textPrimary)
                Text("Last reported firmware").strandOverline()
                Text(verbatim: observedFirmware ?? String(localized: "Not reported yet"))
                    .font(StrandFont.bodyNumber)
                    .foregroundStyle(StrandPalette.textSecondary)
                    .textSelection(.enabled)
                TextField("Your local reference version (optional)", text: $reference)
                    .font(StrandFont.body)
                    .textFieldStyle(.roundedBorder)
                    .accessibilityLabel("Your local reference version (optional)")
                if !reference.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Text(referenceReadout)
                        .font(StrandFont.caption)
                        .foregroundStyle(StrandPalette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Text("Your reference is only a local comparison. NOOP cannot determine the latest firmware or whether WHOOP offers this device an update.")
                    .font(StrandFont.caption)
                    .foregroundStyle(StrandPalette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var referenceReadout: String {
        switch FirmwareReferenceComparison.compare(observed: observedFirmware, reference: reference) {
        case .older: return String(localized: "Older than your reference")
        case .equal: return String(localized: "Matches your reference")
        case .newer: return String(localized: "Newer than your reference")
        case .unknown: return String(localized: "These labels cannot be safely compared. Use matching dot-separated numeric versions from the same device family.")
        }
    }

    private var guidanceCard: some View {
        NoopCard {
            VStack(alignment: .leading, spacing: NoopMetrics.cardInnerSpacing) {
                Text("Update with the official WHOOP app")
                    .font(StrandFont.headline)
                    .foregroundStyle(StrandPalette.textPrimary)
                Text("Export a local backup in Settings and let any sync finish. Tap Disconnect in Settings before switching to the official WHOOP app.")
                Text("In the WHOOP app, open Device Settings, then Advanced Settings and Firmware Check. Follow the official app's instructions if it offers an update. Account access and update eligibility are controlled by WHOOP.")
                Text("After the official app finishes, return to NOOP and reconnect normally to read the reported version again.")
                Link("Open WHOOP's update guide", destination: URL(string: "https://support.whoop.com/s/article/WHOOP-3-0-and-4-0-How-to-Update-Your-Product-s-Firmware")!)
                    .tint(StrandPalette.accent)
            }
            .font(StrandFont.body)
            .foregroundStyle(StrandPalette.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
        }
    }

}

private struct FirmwareSimulationCard: View {
    @State private var simulation = FirmwareSimulationState()

    var body: some View {
        NoopCard {
            VStack(alignment: .leading, spacing: NoopMetrics.cardInnerSpacing) {
                Text("Firmware update simulation")
                    .font(StrandFont.headline)
                    .foregroundStyle(StrandPalette.textPrimary)
                Text("SIMULATION — no strap is changed.")
                    .font(StrandFont.body)
                    .foregroundStyle(StrandPalette.statusWarning)
                Text(simulation.paused ? String(localized: "Simulation paused") : simulation.phase.label)
                    .font(StrandFont.subhead)
                    .foregroundStyle(StrandPalette.textPrimary)
                if let failure = simulation.failure {
                    Text(failure.label)
                        .font(StrandFont.caption)
                        .foregroundStyle(StrandPalette.statusWarning)
                }
                if simulation.phase == .download || simulation.phase == .transfer {
                    ProgressView(value: Double(simulation.progress), total: 100)
                        .tint(StrandPalette.accent)
                    Text(verbatim: "\(simulation.progress)%")
                        .font(StrandFont.captionNumber)
                        .foregroundStyle(StrandPalette.textSecondary)
                }
                simulationControls
                Text("Every step is an in-memory mock. Nothing is downloaded, sent over Bluetooth, verified on hardware or stored as a device update.")
                    .font(StrandFont.caption)
                    .foregroundStyle(StrandPalette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    @ViewBuilder private var simulationControls: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.rowSpacing) {
            if simulation.phase.isActive {
                if simulation.paused {
                    Button("Resume simulation") { simulation.send(.resume(runID: simulation.runID)) }
                } else {
                    Button("Advance mock step") {
                        simulation.send(.advance(runID: simulation.runID, phase: simulation.phase))
                    }
                    if simulation.phase == .download || simulation.phase == .transfer {
                        Button("Add mock progress") {
                            simulation.send(.progress(runID: simulation.runID, phase: simulation.phase,
                                                      percent: simulation.progress + 25))
                        }
                        .disabled(simulation.progress == 100)
                    }
                    Button("Pause simulation") { simulation.send(.pause(runID: simulation.runID)) }
                }
                Menu("Inject simulated failure") {
                    ForEach(FirmwareSimulationState.Failure.allCases, id: \.self) { reason in
                        Button(reason.label) {
                            simulation.send(.fail(runID: simulation.runID, phase: simulation.phase, reason: reason))
                        }
                    }
                }
                Button("Cancel simulation", role: .cancel) { simulation.send(.cancel(runID: simulation.runID)) }
            } else {
                if simulation.checkpoint != nil {
                    Button("Resume simulation") { simulation.send(.resume(runID: simulation.runID)) }
                }
                Button("Run simulation") { simulation.send(.start) }
            }
            if simulation.phase != .idle {
                Button("Reset simulation") { simulation.send(.reset) }
            }
        }
        .font(StrandFont.body)
        .buttonStyle(.bordered)
        .tint(StrandPalette.accent)
    }
}

private extension FirmwareSimulationState.Phase {
    var label: String {
        switch self {
        case .idle: return String(localized: "Ready")
        case .check: return String(localized: "Checking mock prerequisites")
        case .download: return String(localized: "Mock download")
        case .verify: return String(localized: "Mock verification")
        case .transfer: return String(localized: "Mock transfer")
        case .reboot: return String(localized: "Mock restart")
        case .done: return String(localized: "Simulation complete")
        case .failed: return String(localized: "Simulation failed")
        case .cancelled: return String(localized: "Simulation cancelled")
        }
    }
}

private extension FirmwareSimulationState.Failure {
    var label: String {
        switch self {
        case .prerequisites: return String(localized: "Fake battery too low")
        case .verification: return String(localized: "Mock verification failed")
        case .timeout: return String(localized: "Simulated timeout")
        case .disconnected: return String(localized: "Simulated disconnect")
        case .mockFailure: return String(localized: "Injected mock failure")
        }
    }
}
