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
        .task(id: repo.refreshSeq) { row = await repo.workoutRows().first }
    }
}
#endif
