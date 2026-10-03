import SwiftUI
import StrandDesign
import StrandAnalytics
import WhoopStore

struct StrainDetailView: View {
    var dayKey: String? = nil
    var effortOverride: Double? = nil
    @EnvironmentObject private var repo: Repository
    @StateObject private var profile = ProfileStore()
    @AppStorage(DayCycleMode.storageKey) private var cycleMode = DayCycleMode.sleepOnset.rawValue
    @State private var points: [TrendPoint] = []
    @State private var zoneMinutes: [Double]? = nil
    @State private var belowZoneMinutes: Double? = nil
    @State private var workouts: [WorkoutRow] = []
    @State private var loaded = false
    @State private var openedDeviceId: String?

    private var key: String { dayKey ?? repo.today?.day ?? Repository.logicalDayKey(Date()) }
    private var row: DailyMetric? { repo.days.first { $0.day == key } ?? (repo.today?.day == key ? repo.today : nil) }
    private var rawEffort: Double? {
        let displayedOverride = openedDeviceId == nil || openedDeviceId == repo.deviceId ? effortOverride : nil
        return (displayedOverride ?? row?.strain).flatMap { $0.isFinite && (0...100).contains($0) ? $0 : nil }
    }
    private var strain: Double? { rawEffort.map { UnitFormatter.effortValue($0, scale: .whoop) } }
    private var band: ClosedRange<Int>? { CoupledView.optimalStrainRange(recovery: row?.recovery.flatMap { RecoveryStrainDetailLogic.recoveryPercent($0) != nil ? $0 : nil }) }
    private var targetStatus: RecoveryStrainDetailLogic.TargetStatus {
        RecoveryStrainDetailLogic.targetStatus(strain21: strain, lower: band?.lowerBound, upper: band?.upperBound)
    }

    var body: some View {
        ScreenScaffold(title: nil, lazy: true, topBackground: recoveryStrainBackdrop()) {
            Text(RecoveryStrainDetailLogic.dateLabel(key, locale: AppLanguage.activeLocale)).strandOverline().frame(maxWidth: .infinity)
            ScoreDial(label: String(localized: "Day strain"), value: rawEffort.map { UnitFormatter.effortDisplay($0, scale: .whoop) } ?? "—",
                      progress: strain.map { $0 / 21 }, color: StrandPalette.strainPrimary,
                      target: band.map { Double($0.lowerBound) / 21 },
                      targetRange: band.map { Double($0.lowerBound) / 21...Double($0.upperBound) / 21 })
                .frame(maxWidth: .infinity)
            target
            InsightCallout(text: String(localized: "Strain here presents NOOP’s local Effort on a 0–21 axis. It is a change of display units, not the official WHOOP scoring model. Stored Effort and your history stay unchanged."))
            DetailHeartRateChart(points: points, loaded: loaded)
            DetailZoneBars(minutes: zoneMinutes, zoneSet: profile.hrZoneSet, belowZoneMinutes: belowZoneMinutes)
            TrackedSectionHeader(title: String(localized: "Activities"))
            if workouts.isEmpty {
                NoopCard {
                    Text(loaded ? String(localized: "No activities recorded for this day.") : String(localized: "Loading activities…"))
                        .font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
                }
            } else {
                ForEach(workouts.indices, id: \.self) { index in
                    let workout = workouts[index]
                    NavigationLink { ActivityDetailView(row: workout) } label: {
                        DetailActivityRow(row: workout)
                    }.buttonStyle(.plain)
                }
            }
        }
        .navigationTitle(String(localized: "Strain"))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .task(id: "\(key)|\(repo.deviceId)|\(repo.refreshSeq)|\(cycleMode)") { await load() }
    }

    private var target: some View {
        NoopCard {
            VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                TrackedSectionHeader(title: String(localized: "Suggested range"), microLabel: String(localized: "Based on this day’s recovery"))
                HStack(alignment: .firstTextBaseline, spacing: NoopMetrics.space3) {
                    Text(band.map { "\($0.lowerBound)–\($0.upperBound)" } ?? "—")
                        .font(StrandFont.title1).foregroundStyle(StrandPalette.textPrimary)
                    Text("of 21").font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                    Spacer(minLength: 0)
                }
                StatusPill(label: statusLabel, color: targetStatus == .over ? StrandPalette.statusWarning : StrandPalette.strainPrimary)
                Text("This local guidance range is not a training prescription. A missing recovery score leaves the target unavailable.")
                    .font(StrandFont.footnote).foregroundStyle(StrandPalette.textTertiary)
                if let rawEffort {
                    Text(String(localized: "Stored Effort: \(UnitFormatter.effortDisplay(rawEffort, scale: .hundred)) of 100"))
                        .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                }
            }
        }
    }

    private var statusLabel: String {
        switch targetStatus {
        case .unavailable: return String(localized: "Target unavailable")
        case .under: return String(localized: "Below suggested range")
        case .optimal: return String(localized: "Within suggested range")
        case .over: return String(localized: "Above suggested range")
        }
    }

    private func load() async {
        if openedDeviceId == nil { openedDeviceId = repo.deviceId }
        loaded = false
        points = []
        zoneMinutes = nil
        belowZoneMinutes = nil
        workouts = []
        guard let day = RecoveryStrainDetailLogic.date(key), let next = Calendar.current.date(byAdding: .day, value: 1, to: day) else { return }
        let requestedKey = key
        let deviceId = repo.deviceId
        let requestedMode = cycleMode
        let calendarStart = Int(day.timeIntervalSince1970)
        let calendarEnd = min(Int(next.timeIntervalSince1970), Int(Date().timeIntervalSince1970) + 1)
        let markers = DayCycleMode.persisted(cycleMode) == .sleepOnset
            ? await repo.exploreSeries(key: DayCycleIntelligenceIntegration.onsetKey, source: "my-whoop") : []
        let start = markers.last { $0.day == requestedKey }.map { Int($0.value) } ?? calendarStart
        let nextKey = Repository.localDayKey(next)
        let end = min(markers.last { $0.day == nextKey }.map { Int($0.value) } ?? calendarEnd, calendarEnd) - 1
        let buckets = await repo.hrBuckets(from: start, to: max(start, end), bucketSeconds: 300)
        let samples = await repo.hrSamples(from: start, to: max(start, end), limit: 200_000)
        let rows = await repo.workoutRows()
        let segments = hrGapSegments(bucketTs: buckets.map(\.ts), bucketSeconds: 300)
        guard key == requestedKey, repo.deviceId == deviceId, cycleMode == requestedMode, !Task.isCancelled else { return }
        points = buckets.enumerated().map { index, bucket in
            TrendPoint(date: Date(timeIntervalSince1970: Double(bucket.ts)), value: bucket.bpm, segment: segments[index])
        }
        let split = samples.isEmpty ? nil : HRZones.timeInZone(samples, zoneSet: profile.hrZoneSet)
        zoneMinutes = split?.seconds.map { $0 / 60 }
        belowZoneMinutes = split.map { $0.belowZone1 / 60 }
        workouts = rows.filter { $0.startTs >= start && $0.startTs <= end }
        loaded = true
    }
}

struct DetailHeartRateChart: View {
    let points: [TrendPoint]
    var loaded = true

    var body: some View {
        ChartCard(title: "Heart rate", tint: StrandPalette.strainPrimary) {
            if points.isEmpty {
                Text(loaded ? String(localized: "No heart-rate samples recorded in this window.") : String(localized: "Loading heart rate…"))
                    .font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
            } else {
                TrendChart(points: points, gradient: StrandPalette.effortGradient,
                           valueRange: max(0, (points.map(\.value).min() ?? 60) - 10)...((points.map(\.value).max() ?? 180) + 10),
                           height: NoopMetrics.chartHeight,
                           valueFormat: { String(localized: "\(Int($0.rounded())) bpm") },
                           dateFormat: { AppClock.hourMinuteFormatter().string(from: $0) },
                           accessibilityLabel: String(localized: "Recorded heart rate"),
                           workoutTimeAxis: points.first.flatMap { first in points.last.map { first.date...$0.date } })
            }
        }
    }
}

struct DetailZoneBars: View {
    let minutes: [Double]?
    var zoneSet: HRZoneSet? = nil
    var imported = false
    var belowZoneMinutes: Double? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.space3) {
            TrackedSectionHeader(title: String(localized: "Heart rate zones"), microLabel: imported ? String(localized: "Imported distribution") : String(localized: "From recorded heart rate"))
            NoopCard {
                if let minutes, minutes.count == 5 {
                    VStack(spacing: NoopMetrics.space4) {
                        ForEach((0..<5).reversed(), id: \.self) { index in
                            VStack(alignment: .leading, spacing: NoopMetrics.space2) {
                                HStack(spacing: NoopMetrics.space2) {
                                    Text(String(localized: "Zone \(index + 1)"))
                                        .font(StrandFont.headline).foregroundStyle(StrandPalette.textPrimary)
                                    if let zoneSet, !imported {
                                        Text("\(Int(zoneSet.zones[index].lower.rounded()))–\(Int(zoneSet.zones[index].upper.rounded())) bpm")
                                            .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                                    }
                                    Spacer(minLength: 0)
                                    Text(String(localized: "\(Int(minutes[index].rounded())) min"))
                                        .font(StrandFont.bodyNumber).foregroundStyle(StrandPalette.textPrimary)
                                }
                                GeometryReader { geometry in
                                    ZStack(alignment: .leading) {
                                        Capsule().fill(StrandPalette.ringTrack)
                                        Capsule().fill(StrandPalette.hrZoneColor(index + 1))
                                            .frame(width: geometry.size.width * min(1, max(0, minutes[index] / max(minutes.reduce(0, +), 1))))
                                    }
                                }.frame(height: NoopMetrics.indicatorTrackHeight)
                            }.accessibilityElement(children: .combine)
                        }
                        if let belowZoneMinutes, belowZoneMinutes > 0 {
                            ContributorRow(label: String(localized: "Below Zone 1"), value: String(Int(belowZoneMinutes.rounded())), unit: String(localized: "min"))
                        }
                        Text(imported ? String(localized: "Zone times use the split saved with this activity.") : String(localized: "Zone times use your configured heart-rate zones and available recording coverage. Gaps are not filled."))
                            .font(StrandFont.footnote).foregroundStyle(StrandPalette.textTertiary)
                    }
                } else {
                    Text("Heart-rate zones are unavailable without a recording or imported split.")
                        .font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
                }
            }
        }
    }
}

struct DetailActivityRow: View {
    let row: WorkoutRow
    var body: some View {
        NoopCard {
            HStack(spacing: NoopMetrics.space3) {
                VStack(alignment: .leading, spacing: NoopMetrics.space1) {
                    Text(WorkoutSource.displaySport(row.sport)).font(StrandFont.headline).foregroundStyle(StrandPalette.textPrimary)
                    Text(AppClock.hourMinuteFormatter().string(from: Date(timeIntervalSince1970: Double(row.startTs))))
                        .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                }
                Spacer(minLength: 0)
                Text(row.strain.flatMap { $0.isFinite && (0...100).contains($0) ? UnitFormatter.effortDisplay($0, scale: .whoop) : nil } ?? "—")
                    .font(StrandFont.title2).foregroundStyle(StrandPalette.strainPrimary)
                Image(systemName: "chevron.right").font(StrandFont.caption).foregroundStyle(StrandPalette.textTertiary)
            }
        }
    }
}

func recoveryStrainBackdrop() -> AnyView {
    AnyView(LinearGradient(colors: [StrandPalette.canvasTop, StrandPalette.canvasBottom], startPoint: .top, endPoint: .bottom))
}
