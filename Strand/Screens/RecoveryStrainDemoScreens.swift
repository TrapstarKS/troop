#if DEBUG
import SwiftUI
import StrandDesign
import WhoopStore

enum RecoveryStrainDemoScreens {
    static func screen(named name: String) -> AnyView? {
        switch name {
        case "recovery": return AnyView(NavigationStack { RecoveryDetailView() })
        case "strain": return AnyView(NavigationStack { StrainDetailView() })
        case "activity": return AnyView(NavigationStack { ActivityDemoHost() })
        default: return nil
        }
    }
}

private struct ActivityDemoHost: View {
    @EnvironmentObject private var repo: Repository
    @State private var row: WorkoutRow?
    var body: some View {
        Group {
            if let row { ActivityDetailView(row: row) }
            else { ProgressView().tint(StrandPalette.strainPrimary) }
        }
        .task(id: repo.refreshSeq) {
            let now = Int(Date().timeIntervalSince1970)
            let rows = await repo.workoutRows()
            guard !Task.isCancelled else { return }
            row = rows.first {
                $0.startTs <= now && $0.endTs <= now && $0.source == "manual" && $0.sport == "Running"
                    && $0.durationS == 2700 && $0.energyKcal == 310 && $0.strain == 52
            } ?? rows.first { $0.startTs <= now && $0.endTs <= now }
        }
    }
}
#endif
