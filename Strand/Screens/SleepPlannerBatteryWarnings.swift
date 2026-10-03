import SwiftUI
import StrandDesign
#if os(iOS)
import UIKit
#endif

struct SleepPlannerBatteryWarnings: View {
    @ObservedObject var live: LiveState
    let alarmEnabled: Bool
    @State private var phoneLow = false

    var body: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.cardInnerSpacing) {
            if alarmEnabled, let battery = live.batteryPct, battery < 20 {
                warning("Charge your strap before sleep", help: "Low strap battery may interrupt your wake alarm. The last reported charge is below 20%.")
            }
            if alarmEnabled && phoneLow {
                warning("Charge your phone before sleep", help: "Low phone battery may interrupt the backup wake reminder. Keep a Clock alarm as a backup.")
            }
        }
        .onAppear { refreshPhoneBattery() }
        #if os(iOS)
        .onReceive(NotificationCenter.default.publisher(for: UIDevice.batteryLevelDidChangeNotification)) { _ in refreshPhoneBattery() }
        #endif
    }

    private func warning(_ title: LocalizedStringKey, help: LocalizedStringKey) -> some View {
        StrandCard {
            VStack(alignment: .leading, spacing: NoopMetrics.space2) {
                Label(title, systemImage: "battery.25percent")
                    .font(StrandFont.headline).foregroundStyle(StrandPalette.statusWarning)
                Text(help).font(StrandFont.footnote).foregroundStyle(StrandPalette.textSecondary)
            }
        }
    }

    private func refreshPhoneBattery() {
        #if os(iOS)
        UIDevice.current.isBatteryMonitoringEnabled = true
        let level = UIDevice.current.batteryLevel
        phoneLow = level >= 0 && level <= 0.2
        #endif
    }
}
