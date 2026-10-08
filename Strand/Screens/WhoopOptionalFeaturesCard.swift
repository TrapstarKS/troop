import SwiftUI
import StrandDesign
import WhoopProtocol
import WhoopStore

struct WhoopOptionalFeaturesCard: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var live: LiveState
    @AppStorage(PuffinExperiment.defaultsKey) private var probes = false
    @AppStorage(PuffinExperiment.broadcastHrKey) private var broadcast = false
    @AppStorage(PuffinExperiment.deepDataKey) private var deepData = false
    @AppStorage(PuffinExperiment.ecgRawDataKey) private var ecgRawData = false
    @AppStorage(PuffinFrameRecorder.enabledKey) private var capture = false
    @State private var pending: StrapChange?
    @State private var ecgTarget: PairedDevice?
    @State private var wristTarget: PairedDevice?

    private enum StrapChange {
        case probes, broadcast(Bool), r22Enable, r22Disable, ecgGate(Bool)
    }

    private var canWrite: Bool {
        model.activeWhoopLinkIsBonded
    }

    var body: some View {
        NoopCard {
            VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                Text("Optional strap features").font(StrandFont.headline)
                    .foregroundStyle(StrandPalette.textPrimary)
                Text("Local capture sends no commands. Optional strap changes require confirmation.")
                    .font(StrandFont.caption).foregroundStyle(StrandPalette.textTertiary)
                Toggle("Passive history/protocol trace", isOn: Binding(
                    get: { capture }, set: { WhoopFamilyDefaultsStore.setCapture($0); capture = $0 }))
                    .toggleStyle(.switch).tint(StrandPalette.accent)
                Text("Records only frames that already arrive, with bounded local storage. It sends no commands to the strap.")
                    .font(StrandFont.caption).foregroundStyle(StrandPalette.textTertiary)
                Divider().overlay(StrandPalette.hairline)
                Toggle("Broadcast heart rate from the strap", isOn: Binding(
                    get: { broadcast }, set: { pending = .broadcast($0) }))
                    .toggleStyle(.switch).tint(StrandPalette.accent)
                Text("Broadcast heart rate is reapplied when this strap reconnects.")
                    .font(StrandFont.caption).foregroundStyle(StrandPalette.textTertiary)
                Toggle("Protocol probes", isOn: Binding(get: { probes }, set: {
                    if $0 { pending = .probes } else { probes = false }
                }))
                    .toggleStyle(.switch).tint(StrandPalette.accent)
                Text("Sends experimental protocol queries and records replies in the strap log. Also enables the experimental 5/MG strap alarm.")
                    .font(StrandFont.caption).foregroundStyle(StrandPalette.textTertiary)
                Divider().overlay(StrandPalette.hairline)
                NoopButton("Send legacy R22 enable sequence", systemImage: "bolt.badge.automatic", kind: .secondary) {
                    pending = .r22Enable
                }.disabled(!canWrite || !live.worn)
                NoopButton("Clear legacy R22 flags on strap", systemImage: "bolt.slash", kind: .secondary) {
                    pending = .r22Disable
                }.disabled(!canWrite || live.r22DisableReport == BLEManager.deviceConfigProbeWaiting)
                Text("The strap accepts these writes, but NOOP has not observed them enabling a separate live stream. This is not the Raw Data Collector.")
                    .font(StrandFont.caption).foregroundStyle(StrandPalette.textTertiary)
                if let result = live.r22DisableReport {
                    Text(result).font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                }
                Divider().overlay(StrandPalette.hairline)
                NoopButton("WHOOP MG ECG capture (experimental)", systemImage: "waveform.path.ecg", kind: .secondary) {
                    ecgTarget = model.activeWhoopDevice
                }.disabled(!canWrite || model.activeWhoopVariant != .mg)
                Text("WHOOP MG ECG raw-data gate").font(StrandFont.subhead)
                    .foregroundStyle(StrandPalette.textPrimary)
                HStack(spacing: NoopMetrics.space3) {
                    NoopButton("Gate on", systemImage: "waveform.path.ecg", kind: .secondary) { pending = .ecgGate(true) }
                    NoopButton("Gate off", systemImage: "arrow.uturn.backward", kind: .secondary) { pending = .ecgGate(false) }
                }.disabled(!canWrite || model.activeWhoopVariant != .mg)
                Text("MG-only protocol instrumentation, not a medical ECG feature.")
                    .font(StrandFont.caption).foregroundStyle(StrandPalette.textTertiary)
                if let result = live.ecgRawDataGate {
                    Text(result.summary).font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                }
                Divider().overlay(StrandPalette.hairline)
                NavigationLink { RawDataCollectorView() } label: {
                    Label("Open raw-data collector", systemImage: "waveform.path.ecg")
                }.buttonStyle(NoopButtonStyle(.secondary, fullWidth: true))
            }
        }
        .confirmationDialog("Apply this strap change?", isPresented: Binding(
            get: { pending != nil }, set: { if !$0 { pending = nil } }), titleVisibility: .visible) {
            Button("Apply") { applyPendingChange() }
            Button("Cancel", role: .cancel) { pending = nil }
        } message: { Text(confirmationMessage) }
        .modifier(EcgProbeSheets(target: $ecgTarget, wristTarget: $wristTarget))
        .onChangeCompat(of: model.activeWhoopDevice?.id) { _ in
            pending = nil; ecgTarget = nil; wristTarget = nil
        }
    }

    private var confirmationMessage: String {
        switch pending {
        case .probes:
            return String(localized: "Protocol probes can send experimental queries and arm the configured 5/MG alarm after bonding. They stay enabled until you turn them off.")
        case .broadcast:
            return String(localized: "Writes the reversible 5/MG advertising flag for Garmin, Zwift, and compatible gym equipment.")
        case .r22Enable:
            return String(localized: "The strap accepts these writes, but NOOP has not observed them enabling a separate live stream. This is not the Raw Data Collector.")
        case .r22Disable:
            return String(localized: "Turning this switch off only stops NOOP sending the unlock. The flags it already wrote stay on the strap until something clears them. NOOP can write the off value to all 16 now and read each one back so you can see what the strap actually stores. Needs the strap connected and bonded.")
        case .ecgGate:
            return String(localized: "This writes the reversible MG ECG raw-data flag and reads it back. A successful write does not establish that ECG data is available.")
        case nil: return ""
        }
    }

    private func applyPendingChange() {
        guard model.activeWhoopFamily == .whoop5 else { pending = nil; return }
        defer { pending = nil }
        switch pending {
        case .probes: probes = true
        case let .broadcast(on):
            broadcast = on
            if canWrite { model.ble.setBroadcastHr(on) }
        case .r22Enable:
            guard canWrite, live.worn else { return }
            deepData = true; model.ble.enableWhoop5DeepData()
        case .r22Disable:
            guard canWrite else { return }
            model.ble.disableWhoop5DeepData(); deepData = false
        case let .ecgGate(on):
            guard canWrite, model.activeWhoopVariant == .mg else { return }
            ecgRawData = true; model.ble.setEcgRawDataGate(on)
        case nil: break
        }
    }
}
