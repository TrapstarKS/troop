import SwiftUI
import StrandDesign
import StrandAnalytics
import WhoopStore

struct HomeDashboardContent<Dashboard: View, Extras: View>: View {
    let dayKey: String
    let dayOffset: Int
    var windowDayKey: String? = nil
    private var isToday: Bool { dayOffset == 0 }
    private var activityDayKey: String { windowDayKey ?? dayKey }
    let day: DailyMetric?
    let sleepScore: Double?
    let recovery: Double?
    let recoveryDayKey: String
    let recoveryCaption: String?
    let strain: Double?
    let stress: Double?
    let workouts: [WorkoutRow]
    let onEdit: () -> Void
    let onWorkout: (WorkoutRow) -> Void
    let onActivitySaved: () async -> Void
    let onGuidance: (() -> Void)?
    @ViewBuilder let dashboard: () -> Dashboard
    @ViewBuilder let extras: () -> Extras
    @EnvironmentObject private var router: NavRouter
    @EnvironmentObject private var repo: Repository
    @EnvironmentObject private var intelligence: IntelligenceEngine
    @State private var showManualActivity = false
    @State private var manualEndDate = Date()
    @State private var startWorkoutRequested = false
    @ScaledMetric private var columnWidth = NoopMetrics.compactScoreDialDiameter

    var body: some View {
        LazyVStack(alignment: .leading, spacing: NoopMetrics.sectionGap) {
            scoreRow
            InsightCallout(text: guidance, actionLabel: onGuidance == nil ? nil : String(localized: "Daily Outlook"), onAction: onGuidance)
            monitorRow
            myDay
            myPlan
            VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                HStack {
                    TrackedSectionHeader(title: String(localized: "My Dashboard"))
                    Button(action: onEdit) {
                        Image(systemName: "pencil")
                            .font(StrandFont.headline)
                            .frame(width: NoopMetrics.touchTarget, height: NoopMetrics.touchTarget)
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(StrandPalette.textPrimary)
                    .accessibilityLabel("Edit dashboard")
                }
                dashboard()
            }
            DisclosureGroup {
                extras()
            } label: {
                Text("Your Cards").font(StrandFont.headline).foregroundStyle(StrandPalette.textPrimary)
            }
        }
        .sheet(isPresented: $showManualActivity) {
            ManualWorkoutSheet(initialEndDate: manualEndDate) { row, _ in
                Task {
                    await repo.saveManualWorkout(row)
                    await intelligence.analyzeRecent()
                    await onActivitySaved()
                }
            }
        }
    }

    private var scoreRow: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .top, spacing: NoopMetrics.space3) {
                sleepDial
                recoveryDial
                strainDial
            }
            .frame(maxWidth: .infinity)
            VStack(spacing: NoopMetrics.space4) {
                sleepDial
                recoveryDial
                strainDial
            }
        }
        .padding(.vertical, NoopMetrics.space4)
    }

    private var sleepValue: Double? { HomeScoreValue.resolve(sleepScore) }
    private var recoveryValue: Double? { HomeScoreValue.resolve(recovery) }
    private var strainValue: Double? { HomeScoreValue.resolve(strain) }

    private var sleepDial: some View {
        NavigationLink(value: TabRoute.sleepDetailForDay(dayKey: dayKey)) {
