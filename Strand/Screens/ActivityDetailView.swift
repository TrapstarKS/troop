import SwiftUI
import StrandDesign
import StrandAnalytics
import WhoopStore

struct ActivityDetailView: View {
    @EnvironmentObject private var repo: Repository
    @State private var viewportWidth: CGFloat? = nil
    @Environment(\.dismiss) private var dismiss
    @StateObject private var profile = ProfileStore()
    @State private var row: WorkoutRow
    @State private var points: [TrendPoint] = []
    @State private var minutes: [Double]? = nil
    @State private var importedZones = false
    @State private var loaded = false
    @State private var showEdit = false
    @State private var showMore = false

    init(row: WorkoutRow) { _row = State(initialValue: row) }

    private var canEdit: Bool {
        let source = WorkoutSource.classify(row.source)
        return source == .manual || source == .detected
    }
    private var editRow: WorkoutRow {
        guard !canEdit else { return row }
        return WorkoutRow(startTs: row.startTs, endTs: row.endTs, sport: WorkoutSource.displaySport(row.sport),
                          source: "manual", durationS: row.durationS, energyKcal: row.energyKcal,
                          avgHr: row.avgHr, maxHr: row.maxHr, strain: row.strain, distanceM: row.distanceM,
                          zonesJSON: row.zonesJSON, notes: row.notes, steps: row.steps)
    }
    private var energyLabel: String {
        String(localized: "Recorded energy")
    }
    private var strain: Double? { row.strain.flatMap { $0.isFinite && (0...100).contains($0) ? $0 : nil } }
    private var energy: Int64? { RecoveryStrainDetailLogic.wholeNumber(row.energyKcal) }
    private var durationMinutes: Int64? {
        RecoveryStrainDetailLogic.durationMinutes(seconds: row.durationS, fallbackSeconds: Double(row.endTs - row.startTs))
    }

    var body: some View {
        ScreenScaffold(title: nil, lazy: true, topBackground: recoveryStrainBackdrop()) {
            VStack(spacing: NoopMetrics.space2) {
                Text(WorkoutSource.displaySport(row.sport)).font(StrandFont.title1).foregroundStyle(StrandPalette.textPrimary)
                Text(RecoveryStrainDetailLogic.dateLabel(Repository.localDayKey(Date(timeIntervalSince1970: Double(row.startTs))), locale: AppLanguage.activeLocale))
                    .font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
                Text("\(time(row.startTs)) – \(time(row.endTs))")
                    .font(StrandFont.bodyNumber).foregroundStyle(StrandPalette.textSecondary)
                Text(durationMinutes.map { String(localized: "\($0) min") } ?? "—")
                    .font(StrandFont.caption).foregroundStyle(StrandPalette.textTertiary)
            }.frame(maxWidth: .infinity)
            ScoreDial(label: String(localized: "Activity strain"), value: strain.map { UnitFormatter.effortDisplay($0, scale: .whoop) } ?? "—",
                      progress: strain.map { UnitFormatter.effortValue($0, scale: .whoop) / 21 }, color: StrandPalette.strainPrimary, viewportWidth: viewportWidth)
                .frame(maxWidth: .infinity)
            NoopCard {
                VStack(spacing: NoopMetrics.space4) {
                    ContributorRow(label: String(localized: "Average heart rate"), value: row.avgHr.map(String.init) ?? "—", unit: "bpm", systemImage: "heart")
                    Divider().overlay(StrandPalette.hairline)
                    ContributorRow(label: String(localized: "Max heart rate"), value: row.maxHr.map(String.init) ?? "—", unit: "bpm", systemImage: "heart.fill")
                    Divider().overlay(StrandPalette.hairline)
                    ContributorRow(label: energyLabel, value: energy.map(String.init) ?? "—", unit: "kcal", systemImage: "flame")
                    Text("The recorded calorie value does not identify active versus total energy. It is shown as recorded, without adding resting energy.")
                        .font(StrandFont.footnote).foregroundStyle(StrandPalette.textTertiary)
                    if let distance = row.distanceM, distance.isFinite, distance >= 0 {
                        Divider().overlay(StrandPalette.hairline)
                        ContributorRow(label: String(localized: "Distance"), value: UnitFormatter.distanceFromMeters(distance, system: UnitPrefs.resolveDistance(system: UnitSystem(rawValue: UserDefaults.standard.string(forKey: UnitPrefs.systemKey) ?? "") ?? .metric, override: UserDefaults.standard.string(forKey: UnitPrefs.distanceSystemKey) ?? "")))
                    }
                }
            }
            if WorkoutSource.classify(row.source) != .detected {
                Text("Heart-rate charts for manual and imported activities use the currently selected strap and retained recording history for this time window. The original recording source may differ.")
                    .font(StrandFont.footnote).foregroundStyle(StrandPalette.textTertiary)
            }
            DetailHeartRateChart(points: points, loaded: loaded)
            if let average = row.avgHr, !points.isEmpty,
               abs(Double(average) - points.map(\.value).reduce(0, +) / Double(points.count)) > 3,
               row.strain != nil || row.zonesJSON != nil {
                Text("The displayed average differs from this trace. Heart rate comes from recorded samples; existing strain and zone values are preserved.")
                    .font(StrandFont.footnote).foregroundStyle(StrandPalette.textTertiary)
            }
            DetailZoneBars(minutes: minutes, zoneSet: profile.hrZoneSet, imported: importedZones)
            InsightCallout(text: String(localized: "Activity strain uses the same 0–21 presentation as Day Strain. Individual activity values do not add up to the day score."))
            Button { showMore = true } label: {
                Label(String(localized: "More activity details"), systemImage: "chart.xyaxis.line")
                    .font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
            }.buttonStyle(.plain)
        }
        .background(GeometryReader { geometry in
            Color.clear.onAppear { viewportWidth = geometry.size.width }
                .onChange(of: geometry.size.width) { viewportWidth = $0 }
        })
        .navigationTitle(WorkoutSource.displaySport(row.sport))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button(canEdit ? String(localized: "Edit") : String(localized: "Edit a copy")) { showEdit = true }
            }
        }
        .task(id: "\(row.startTs)|\(row.endTs)|\(row.source)|\(repo.deviceId)|\(repo.refreshSeq)") { await load() }
        .sheet(isPresented: $showEdit) {
            ManualWorkoutSheet(editing: editRow, isCopy: !canEdit) { saved, replacing in
                let replacingOriginal = canEdit
                let original = row
                Task {
                    await repo.saveManualWorkout(saved, replacing: replacingOriginal ? replacing : nil,
                                                 asCopy: !replacingOriginal, copying: original)
                    await repo.refresh()
                    if let stored = await repo.workoutRows().first(where: {
                        $0.startTs == saved.startTs && $0.sport == saved.sport && WorkoutSource.classify($0.source) == .manual
                    }) {
                        row = stored
                    }
                    dismiss()
                }
            }
        }
        .sheet(isPresented: $showMore) { NavigationStack { WorkoutDetailView(row: row, expandedDetails: true) } }
    }

    private func load() async {
        loaded = false
        points = []
        minutes = nil
        importedZones = false
        let selected = row
        let deviceId = repo.deviceId
        let buckets = await repo.workoutHrBuckets(from: selected.startTs, to: selected.endTs, source: selected.source)
        let duration = selected.durationS ?? Double(selected.endTs - selected.startTs)
        var distribution = RecoveryStrainDetailLogic.zoneDistribution(
            importedPercentages: WorkoutZones.percents(selected.zonesJSON), durationSeconds: duration)
        if distribution == nil {
            let recorded = await repo.workoutZoneMinutes(from: selected.startTs, to: selected.endTs,
                                                       zoneSet: profile.hrZoneSet, source: selected.source)
            distribution = RecoveryStrainDetailLogic.zoneDistribution(
                importedPercentages: nil, durationSeconds: duration, recordedMinutes: recorded)
        }
        guard row.startTs == selected.startTs, row.endTs == selected.endTs, row.source == selected.source,
              repo.deviceId == deviceId, !Task.isCancelled else { return }
        let bucketSeconds = max(15, min(300, (selected.endTs - selected.startTs) / 120))
        let segmentIds = hrGapSegments(bucketTs: buckets.map(\.ts), bucketSeconds: bucketSeconds)
        points = buckets.enumerated().map { index, bucket in
            TrendPoint(date: Date(timeIntervalSince1970: Double(bucket.ts)), value: bucket.bpm, segment: segmentIds[index])
        }
        minutes = distribution?.minutes
        importedZones = distribution?.imported ?? false
        loaded = true
    }

    private func time(_ timestamp: Int) -> String {
        AppClock.hourMinuteFormatter().string(from: Date(timeIntervalSince1970: Double(timestamp)))
    }
}
