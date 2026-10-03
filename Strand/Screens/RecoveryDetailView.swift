import SwiftUI
import StrandDesign
import StrandAnalytics
import WhoopStore
import Charts

struct RecoveryDetailView: View {
    var dayKey: String? = nil
    @EnvironmentObject private var repo: Repository
    @State private var viewportWidth: CGFloat? = nil
    @State private var trendDays = 7
    @State private var showInsights = false
    @State private var showGuide = false
    @AppStorage(UnitPrefs.systemKey) private var unitSystem = UnitSystem.metric.rawValue
    @AppStorage(UnitPrefs.temperatureKey) private var temperature = ""
    @AppStorage(UnitPrefs.skinTempDisplayKey) private var skinTempPreference = SkinTempDisplay.Kind.absolute.rawValue

    private var key: String { dayKey ?? repo.today?.day ?? Repository.logicalDayKey(Date()) }
    private var row: DailyMetric? { repo.days.first { $0.day == key } ?? (repo.today?.day == key ? repo.today : nil) }
    private var history: [DailyMetric] { repo.days.filter { $0.day <= key }.sorted { $0.day < $1.day } }
    private var score: Double? { row?.recovery.flatMap { RecoveryStrainDetailLogic.recoveryPercent($0) != nil ? $0 : nil } }
    private var tint: Color { score.map { StrandPalette.recoveryColor($0) } ?? StrandPalette.textTertiary }
    private var calibration: Int? {
        guard key == (repo.today?.day ?? Repository.logicalDayKey(Date())) else { return nil }
        return RecoveryScorer.calibrationNights(nightlyHrv: history.map(\.avgHrv), dayKeys: history.map(\.day),
                                        hasRecovery: score != nil, baselineEpoch: Baselines.hrvBaselineEpoch())
    }
    private var sleepPerformance: Double? {
        guard let row else { return nil }
        return repo.importedSleep[key]?.performancePct ?? AnalyticsEngine.Rest.composite(daily: row)
    }

    var body: some View {
        ScreenScaffold(title: nil, lazy: true, topBackground: recoveryStrainBackdrop()) {
            Text(RecoveryStrainDetailLogic.dateLabel(key, locale: AppLanguage.activeLocale)).strandOverline()
                .frame(maxWidth: .infinity)
            ScoreDial(label: String(localized: "Recovery"), value: RecoveryStrainDetailLogic.recoveryPercent(score).map(String.init) ?? "—",
                      unit: score == nil ? "" : "%", progress: score.map { $0 / 100 }, color: tint,
                      accessibilityLabel: score.map { value in
                          let band = value >= 67 ? String(localized: "High recovery") : value >= 34 ? String(localized: "Moderate recovery") : String(localized: "Low recovery")
                          return [String(localized: "Recovery"), (RecoveryStrainDetailLogic.recoveryPercent(value).map(String.init) ?? "—") + "%", band].joined(separator: ", ")
                      }, viewportWidth: viewportWidth)
                .frame(maxWidth: .infinity)
            if score == nil { availability }
            metrics
            InsightCallout(text: String(localized: "Overnight heart-rate variability, resting heart rate and sleep provide context for recovery. Comparisons show your previous 30 days; the local scoring model uses its own weighted baseline."),
                           actionLabel: String(localized: "Behavior insights"), onAction: { showInsights = true })
            trend
            Button { showGuide = true } label: {
                Label(String(localized: "How recovery is calculated"), systemImage: "info.circle")
                    .font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
            }
            .buttonStyle(.plain)
        }
        .background(GeometryReader { geometry in
            Color.clear.onAppear { viewportWidth = geometry.size.width }
                .onChange(of: geometry.size.width) { viewportWidth = $0 }
        })
        .navigationTitle(String(localized: "Recovery"))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .sheet(isPresented: $showInsights) { NavigationStack { InsightsView() } }
        .sheet(isPresented: $showGuide) {
            ScoringGuideView(initialSection: .charge, onClose: { showGuide = false })
        }
    }

    @ViewBuilder private var availability: some View {
        if let calibration {
            NoopCard {
                VStack(alignment: .leading, spacing: NoopMetrics.space2) {
                    StatusPill(label: String(localized: "Calibrating"), systemImage: "moon", color: StrandPalette.textTertiary)
                    Text(String(localized: "\(calibration) of \(Baselines.minNightsSeed) nights collected"))
                        .font(StrandFont.headline).foregroundStyle(StrandPalette.textPrimary)
                    Text("Wear your strap overnight to establish a personal baseline. A recovery score appears when enough valid nights are available.")
                        .font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
                }
            }
        } else {
            NoopCard {
                Text("Recovery is unavailable for this day. Sync the overnight recording or import a scored night to see it here.")
                    .font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
            }
        }
    }

    private var metrics: some View {
        NoopCard {
            VStack(spacing: NoopMetrics.spaceHalf) {
                metric(String(localized: "Heart rate variability"), value: row?.avgHrv, unit: "ms", icon: "waveform.path.ecg", higherIsBetter: true, values: history.map(\.avgHrv))
                Divider().overlay(StrandPalette.hairline)
                metric(String(localized: "Resting heart rate"), value: row?.restingHr.map(Double.init), unit: "bpm", icon: "heart", higherIsBetter: false, values: history.map { $0.restingHr.map(Double.init) }, decimals: 0)
                Divider().overlay(StrandPalette.hairline)
                metric(String(localized: "Respiratory rate"), value: row?.respRateBpm, unit: String(localized: "rpm"), icon: "lungs", higherIsBetter: nil, values: history.map(\.respRateBpm))
                Divider().overlay(StrandPalette.hairline)
                metric(String(localized: "Sleep performance"), value: sleepPerformance, unit: "%", icon: "moon.zzz",
                       higherIsBetter: nil, values: history.map { repo.importedSleep[$0.day]?.performancePct ?? AnalyticsEngine.Rest.composite(daily: $0) }, decimals: 0)
                if let spo2 = row?.spo2Pct, spo2.isFinite {
                    Divider().overlay(StrandPalette.hairline)
                    metric(String(localized: "Blood oxygen"), value: spo2, unit: "%", icon: "drop", higherIsBetter: nil, values: history.map(\.spo2Pct))
                }
                if let reading = SkinTempDisplay.leadReading(absC: row?.skinTempC, devC: row?.skinTempDevC,
                                                            prefer: SkinTempDisplay.Kind(rawValue: skinTempPreference) ?? .absolute) {
                    let kind = reading.kind == .deviation ? SkinTempDisplay.kind(of: reading.value) : reading.kind
                    let unit = UnitPrefs.resolveTemperature(system: UnitSystem(rawValue: unitSystem) ?? .metric, override: temperature)
                    Divider().overlay(StrandPalette.hairline)
                    VStack(alignment: .trailing, spacing: NoopMetrics.space2) {
                        ContributorRow(label: String(localized: "Skin temperature"),
                                       value: kind == .absolute ? UnitFormatter.temperatureFromCelsius(reading.value, unit: unit) : UnitFormatter.temperatureDeltaFromCelsius(reading.value, unit: unit),
                                       systemImage: "thermometer")
                        Text(kind == .absolute ? String(localized: "Wrist temperature") : String(localized: "From your baseline"))
                            .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                            .multilineTextAlignment(.trailing)
                    }
                }
                DetailComparisonLegend()
            }
        }
    }

    private func metric(_ label: String, value: Double?, unit: String, icon: String, higherIsBetter: Bool?,
                        values: [Double?], decimals: Int = 1) -> some View {
        let baseline = RecoveryStrainDetailLogic.priorMean(dayKeys: history.map(\.day), values: values,
            fromDay: RecoveryStrainDetailLogic.startKey(selectedDay: key, days: 30), selectedDay: key)
        return DetailComparisonRow(label: label, value: value, baseline: baseline, unit: unit,
            systemImage: icon, decimals: decimals, higherIsBetter: higherIsBetter)
    }

    private var trend: some View {
        let start = RecoveryStrainDetailLogic.startKey(selectedDay: key, days: trendDays - 1)
        let points = history.filter { $0.day >= start }.compactMap { day -> TrendPoint? in
            guard let score = day.recovery, RecoveryStrainDetailLogic.recoveryPercent(score) != nil, let date = RecoveryStrainDetailLogic.date(day.day) else { return nil }
            return TrendPoint(date: date, value: score)
        }
        return VStack(alignment: .leading, spacing: NoopMetrics.space3) {
            TrackedSectionHeader(title: String(localized: "Recovery trend"))
            SegmentedPillControl([7, 30], selection: $trendDays, fillsAvailableWidth: true) {
                $0 == 7 ? String(localized: "7 days") : String(localized: "30 days")
            }
            ChartCard(title: "Recovery", tint: tint) {
                if points.isEmpty {
                    Text("No recovery scores in this period.").font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
                } else {
                    RecoveryHistoryPlot(points: points)
                }
            }
        }
    }

}

private struct RecoveryHistoryPlot: View {
    let points: [TrendPoint]
    @State private var selectedDate: Date?
    private var selected: TrendPoint? { points.first { $0.date == selectedDate } }

    var body: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.space2) {
            Chart {
                ForEach(points.indices, id: \.self) { index in
                    let point = points[index]
                    BarMark(x: .value("Date", point.date, unit: .day), y: .value("Recovery", point.value))
                        .foregroundStyle(StrandPalette.recoveryColor(point.value))
                        .cornerRadius(NoopMetrics.space1)
                        .accessibilityLabel(point.date.formatted(date: .abbreviated, time: .omitted))
                        .accessibilityValue("\(RecoveryStrainDetailLogic.recoveryPercent(point.value).map(String.init) ?? "—")%")
                }
                if let selected {
                    RuleMark(x: .value("Date", selected.date, unit: .day))
                        .foregroundStyle(StrandPalette.textSecondary)
                }
            }
            .chartYScale(domain: 0...100)
            .foregroundStyle(StrandPalette.textSecondary)
            .chartOverlay { proxy in
                GeometryReader { geometry in
                    Rectangle().fill(.clear).contentShape(Rectangle())
                        .gesture(DragGesture(minimumDistance: 0).onChanged { gesture in
                            let x = gesture.location.x - geometry[proxy.plotAreaFrame].origin.x
                            guard let date: Date = proxy.value(atX: x) else { return }
                            selectedDate = points.min { abs($0.date.timeIntervalSince(date)) < abs($1.date.timeIntervalSince(date)) }?.date
                        })
                }
            }
            .frame(height: NoopMetrics.chartHeight)
            if let point = selected, let percent = RecoveryStrainDetailLogic.recoveryPercent(point.value) {
                Text("\(point.date.formatted(date: .abbreviated, time: .omitted)) · \(percent)%")
                    .font(StrandFont.captionNumber).foregroundStyle(StrandPalette.recoveryColor(point.value))
            }
        }
        .accessibilityLabel(String(localized: "Recovery history"))
    }
}
