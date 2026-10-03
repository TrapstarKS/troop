import SwiftUI
import StrandDesign
import StrandAnalytics
import WhoopStore

struct HealthMonitorView: View {
    @EnvironmentObject var repo: Repository
    @EnvironmentObject var intelligence: IntelligenceEngine
    @EnvironmentObject var behavior: BehaviorStore
    @EnvironmentObject var model: AppModel
    @AppStorage(UnitPrefs.systemKey) private var unitSystemRaw = UnitSystem.metric.rawValue
    @AppStorage(UnitPrefs.temperatureKey) private var temperatureRaw = ""
    @AppStorage(UnitPrefs.skinTempDisplayKey) private var skinTempDisplayRaw = ""
    @State private var hrvReliability: [String: HealthSignalReliability.Record]? = nil
    @State private var respReliability: [String: HealthSignalReliability.Record]? = nil
    @State private var evidenceIdentity: String? = nil
    @State private var now = Date()
    @State private var reportDays = 30
    @State private var reportFailed = false

    private var temperatureUnit: TemperatureUnit {
        UnitPrefs.resolveTemperature(system: UnitSystem(rawValue: unitSystemRaw) ?? .metric,
                                     override: temperatureRaw)
    }

    var body: some View {
        let day = HealthMonitorSnapshot.dayKey(days: repo.days, now: now)
        let identity = "\(repo.importedReadIds + repo.computedReadIds):\(repo.refreshSeq):\(intelligence.computing):\(day)"
        let evidence = evidenceIdentity == identity ? hrvReliability : nil
        let respEvidence = evidenceIdentity == identity ? respReliability : nil
        let rows = HealthMonitorSnapshot.rows(sourceRows: repo.vitalMetricRows, temperatureUnit: temperatureUnit,
                                              now: now, todayKey: day, hrvReliabilityByDay: evidence, respReliabilityByDay: respEvidence,
                                              skinTempPreferred: SkinTempDisplay.Kind(rawValue: skinTempDisplayRaw) ?? .absolute)
        ScreenScaffold(title: "Health Monitor", onRefresh: { await repo.refresh() }, lazy: true) {
            VStack(alignment: .leading, spacing: NoopMetrics.sectionGap) {
                NoopCard {
                    VStack(alignment: .leading, spacing: NoopMetrics.cardInnerSpacing) {
                        Text(BodyVitalReading.dayLabel(day))
                            .font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
                        HealthMonitorSummary(rows: rows)
                        Text("Your overnight measurements compared with your personal normal range.")
                            .font(StrandFont.footnote).foregroundStyle(StrandPalette.textSecondary)
                    }
                }
                VStack(spacing: NoopMetrics.gap) {
                    ForEach(rows) { row in HealthMonitorMetricRow(row: row) }
                }
                if rows.contains(where: { $0.assessment.status == .calibrating }) {
                    InsightCallout(text: String(localized: "Your personal range needs 14 trusted nights. Missing or unverified readings do not count as within range."))
                }
                Text("Ranges are local wellness estimates. A change can reflect training, sleep or other everyday factors; it is not a diagnosis.")
                    .font(StrandFont.footnote).foregroundStyle(StrandPalette.textSecondary)
                NoopCard {
                    VStack(alignment: .leading, spacing: NoopMetrics.cardInnerSpacing) {
                        Toggle("Local wellness alerts", isOn: $behavior.illnessWatch)
                            .font(StrandFont.subhead)
                            .onChangeCompat(of: behavior.illnessWatch) { _ in
                                model.reevaluateIllness()
                                if behavior.illnessWatch { IllnessNotifier.requestAuthorization() }
                            }
                        Text("Optional phone notifications when several overnight signals change together. Single outliers remain visible here.")
                            .font(StrandFont.footnote).foregroundStyle(StrandPalette.textSecondary)
                        if behavior.illnessWatch, model.healthAlert != nil, let signal = model.illnessSignal {
                            InsightCallout(text: signal.copy)
                        }
                    }
                }
                HeartRateSection()
                NoopCard {
                    VStack(alignment: .leading, spacing: NoopMetrics.cardInnerSpacing) {
                        Text("Health Report").font(StrandFont.headline).foregroundStyle(StrandPalette.textPrimary)
                        Text("Create a private PDF summary. The file is shared only when you choose a destination.")
                            .font(StrandFont.footnote).foregroundStyle(StrandPalette.textSecondary)
                        Picker("Report period", selection: $reportDays) {
                            Text("30 days").tag(30)
                            Text("180 days").tag(180)
                        }.pickerStyle(.segmented)
                        NoopButton("Export Health Report", systemImage: "square.and.arrow.up", kind: .secondary, fullWidth: true) {
                            exportReport(rows: rows, now: now, evidence: evidence, respEvidence: respEvidence)
                        }.disabled(Set(repo.days.filter { $0.day <= HealthMonitorSnapshot.dayKey(days: repo.days, now: now) && $0.recovery?.isFinite == true }.map(\.day)).count < 14)
                        if Set(repo.days.filter { $0.day <= HealthMonitorSnapshot.dayKey(days: repo.days, now: now) && $0.recovery?.isFinite == true }.map(\.day)).count < 14 {
                            Text("A Health Report needs 14 recorded recoveries.")
                                .font(StrandFont.footnote).foregroundStyle(StrandPalette.textSecondary)
                        }
                    }
                }
            }
        }
        .onReceive(Timer.publish(every: 60, on: .main, in: .common).autoconnect()) { now = $0 }
        .task(id: identity) {
            hrvReliability = nil
            respReliability = nil
            evidenceIdentity = nil
            guard !intelligence.computing else { return }
            let end = day
            let records = try? await repo.signalReliabilityByDay(from: Baselines.cutoffKey(todayKey: end, carryDays: 180), to: end)
            guard !Task.isCancelled else { return }
            hrvReliability = records?.hrv
            respReliability = records?.resp
            evidenceIdentity = identity
        }
        .alert("Could not create report", isPresented: $reportFailed) {
            Button("OK", role: .cancel) { }
        }
    }

    @MainActor
    private func exportReport(rows: [HealthMonitorRow], now: Date, evidence: [String: HealthSignalReliability.Record]?, respEvidence: [String: HealthSignalReliability.Record]?) {
        let end = HealthMonitorSnapshot.dayKey(days: repo.days, now: now)
        let start = Baselines.cutoffKey(todayKey: end, carryDays: reportDays - 1)
        let page = HealthReportPage(rows: rows, sourceRows: repo.vitalMetricRows, start: start, end: end,
                                    hrvReliability: evidence, respReliability: respEvidence)
        let name = FileExport.timestampedName("noop-health-report-\(reportDays)d", ext: "pdf")
        guard let url = TrendsReportRenderer.makePDF(page: page, fileName: name) else {
            reportFailed = true
            return
        }
        FileExport.exportFile(at: url, suggestedName: name)
    }
}

struct HealthMonitorSummary: View {
    let rows: [HealthMonitorRow]

    var body: some View {
        let count = rows.filter { $0.assessment.status == .withinRange }.count
        let available = rows.filter { [.withinRange, .outsideRange, .farOutsideRange].contains($0.assessment.status) }.count
        let changed = rows.contains { [.outsideRange, .farOutsideRange].contains($0.assessment.status) }
        let severe = rows.contains { $0.assessment.status == .farOutsideRange }
        VStack(alignment: .leading, spacing: NoopMetrics.space2) {
            StatusPill(label: changed ? String(localized: "Outside your range")
                       : (available == 5 ? String(localized: "Within range") : String(localized: "Baseline incomplete")),
                       systemImage: changed ? "exclamationmark.circle" : (available == 5 ? "checkmark.circle" : "circle.dashed"),
                       color: severe ? StrandPalette.recoveryLow : (changed ? StrandPalette.stressHigh : (available == 5 ? StrandPalette.positive : StrandPalette.textSecondary)))
            Text("\(count) of 5 within range").font(StrandFont.headline).foregroundStyle(StrandPalette.textPrimary)
            if available < 5 {
                Text("\(available) of 5 metrics ready for comparison")
                    .font(StrandFont.footnote).foregroundStyle(StrandPalette.textSecondary)
            }
        }
    }
}

private struct HealthMonitorMetricRow: View {
    let row: HealthMonitorRow

    var body: some View {
        NavigationLink(value: TabRoute.metric(row.reading.key == "skin" ? "skin_temp" : (row.reading.key == "resp" ? "resp_rate" : row.reading.key))) {
            NoopCard {
                VStack(alignment: .leading, spacing: NoopMetrics.cardInnerSpacing) {
                    HStack(alignment: .firstTextBaseline, spacing: NoopMetrics.space3) {
                        Text(row.reading.label).font(StrandFont.headline).foregroundStyle(StrandPalette.textPrimary)
                        Spacer(minLength: NoopMetrics.space2)
                        Text(row.formattedValue ?? "—")
                            .font(StrandFont.title2).monospacedDigit().foregroundStyle(StrandPalette.textPrimary)
                    }
                    HStack(alignment: .top, spacing: NoopMetrics.space3) {
                        VStack(alignment: .leading, spacing: NoopMetrics.space1) {
                            if let lower = row.assessment.lower, let upper = row.assessment.upper {
                                Text("Normal range: \(row.reading.format(lower))–\(row.reading.format(upper)) \(row.reading.unit)")
                            } else if row.reading.value == nil {
                                Text(row.reading.missingCaption)
                            } else if !row.isCurrent {
                                Text("No overnight reading for today")
                            } else {
                                Text("Personal range not available")
                            }
                            if let day = row.reading.day {
                                Text(BodyVitalReading.dayLabel(day))
                            }
                        }.font(StrandFont.footnote).foregroundStyle(StrandPalette.textSecondary)
                        Spacer(minLength: NoopMetrics.space2)
                        StatusPill(label: statusText, color: statusColor)
                    }
                    if let caveat = row.reading.caveat {
                        Text(caveat).font(StrandFont.footnote).foregroundStyle(StrandPalette.textSecondary)
                    }
                }
            }
        }.buttonStyle(.plain)
    }

    private var statusText: String {
        switch row.assessment.status {
        case .unavailable: return String(localized: "Unavailable")
        case .unverified: return String(localized: "Unverified")
        case .calibrating: return String(localized: "Building baseline")
        case .withinRange: return String(localized: "Within range")
        case .outsideRange: return String(localized: "Outside range")
        case .farOutsideRange: return String(localized: "Further outside range")
        }
    }

    private var statusColor: Color {
        switch row.assessment.status {
        case .withinRange: return StrandPalette.positive
        case .outsideRange: return StrandPalette.stressHigh
        case .farOutsideRange: return StrandPalette.recoveryLow
        default: return StrandPalette.textSecondary
        }
    }
}

private struct HealthReportPage: View {
    let rows: [HealthMonitorRow]
    let sourceRows: [SourcedDailyMetric]
    let start: String
    let end: String
    let hrvReliability: [String: HealthSignalReliability.Record]?
    let respReliability: [String: HealthSignalReliability.Record]?

    var body: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.sectionGap) {
            Text("Health Report").font(StrandFont.title1)
            Text("\(start) – \(end)").font(StrandFont.subhead)
            Text("Local wellness summary. Not a diagnosis.").font(StrandFont.footnote)
            ForEach(rows) { row in
                let absolute = row.reading.key == "skin" && (row.reading.value.map(VitalBands.isAbsoluteSkinTemp) ?? true)
                let cfg = HealthMonitorSnapshot.config(key: row.reading.key, absoluteSkin: absolute)
                let values = HealthMonitorSnapshot.resolvedValues(key: row.reading.key, sourceRows: sourceRows,
                                                                  absoluteSkin: absolute, hrvReliabilityByDay: hrvReliability, respReliabilityByDay: respReliability)
                    .filter { $0.key >= start && $0.key <= end && $0.value >= cfg.minVal && $0.value <= cfg.maxVal }
                    .map(\.value)
                VStack(alignment: .leading, spacing: NoopMetrics.space2) {
                    Text(row.reading.label).font(StrandFont.headline)
                    if let low = values.min(), let high = values.max() {
                        let average = values.reduce(0, +) / Double(values.count)
                        Text("Mean \(row.reading.format(average)) · Min \(row.reading.format(low)) · Max \(row.reading.format(high)) \(row.reading.unit)")
                        Text("\(values.count) recorded nights")
                    } else {
                        Text("Unavailable")
                    }
                }.font(StrandFont.subhead)
            }
            Text("Imported and locally computed readings are resolved by source precedence. Missing and unverified values are excluded; temperature scales are kept separate.")
                .font(StrandFont.footnote)
        }
        .foregroundStyle(StrandPalette.textPrimary)
        .padding(NoopMetrics.space8)
        .frame(width: TrendsReportPage.pageWidth, alignment: .leading)
        .background(StrandPalette.surfaceBase)
        .environment(\.colorScheme, .dark)
    }
}
