import SwiftUI
import Charts
import StrandAnalytics
import StrandDesign

enum HealthspanDriver: String, CaseIterable, Identifiable {
    case sleepDuration, sleepConsistency, lowZones, highZones, strength, steps, restingHR, vo2, leanMass
    var id: String { rawValue }
    var pillar: Int {
        switch self {
        case .sleepDuration, .sleepConsistency: return 0
        case .lowZones, .highZones, .strength, .steps: return 1
        case .restingHR, .vo2, .leanMass: return 2
        }
    }
    var title: String {
        switch self {
        case .sleepDuration: return String(localized: "Sleep duration")
        case .sleepConsistency: return String(localized: "Sleep consistency")
        case .lowZones: return String(localized: "HR zones 1–3")
        case .highZones: return String(localized: "HR zones 4–5")
        case .strength: return String(localized: "Strength activity time")
        case .steps: return String(localized: "Steps")
        case .restingHR: return String(localized: "Resting heart rate")
        case .vo2: return String(localized: "VO₂max")
        case .leanMass: return String(localized: "Lean mass")
        }
    }
    var weekly: Bool { self == .lowZones || self == .highZones || self == .strength }
    var unit: String {
        switch self {
        case .sleepDuration, .lowZones, .highZones, .strength: return String(localized: "min")
        case .sleepConsistency: return "%"
        case .steps: return ""
        case .restingHR: return "bpm"
        case .vo2: return "ml/kg/min"
        case .leanMass: return "kg"
        }
    }
}

struct HealthspanPillarsView: View {
    let reference: Date
    var body: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.gap) {
            TrackedSectionHeader(title: String(localized: "Contributors"))
            ForEach(0..<3, id: \.self) { pillar in
                NavigationLink {
                    HealthspanPillarView(pillar: pillar, reference: reference)
                } label: {
                    NoopCard {
                        HStack {
                            Text(healthspanPillarTitle(pillar)).font(StrandFont.headline)
                            Spacer()
                            Image(systemName: "chevron.right")
                        }.foregroundStyle(StrandPalette.textPrimary)
                    }
                }.buttonStyle(.plain)
            }
            Text("Recorded context; Age Impact is unavailable for this local estimate.")
                .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
        }
    }
}

private struct HealthspanPillarView: View {
    let pillar: Int
    let reference: Date
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: NoopMetrics.gap) {
                Text(reference, style: .date).font(StrandFont.caption)
                ForEach(HealthspanDriver.allCases.filter { $0.pillar == pillar }) { driver in
                    NavigationLink {
                        HealthspanContributorDetailView(driver: driver, reference: reference)
                    } label: {
                        NoopCard {
                            HStack {
                                Text(driver.title).font(StrandFont.headline)
                                Spacer()
                                Image(systemName: "chevron.right")
                            }.foregroundStyle(StrandPalette.textPrimary)
                        }
                    }.buttonStyle(.plain)
                }
                Text("Compare recorded 30-day and six-month averages. Missing days remain gaps.")
                    .font(StrandFont.body).foregroundStyle(StrandPalette.textSecondary)
                Text("Targets and Age Impact are unavailable for this local estimate.")
                    .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
            }.padding(NoopMetrics.screenPadding).padding(.bottom, NoopMetrics.tabBarClearance)
        }.background(StrandPalette.surfaceBase).navigationTitle(healthspanPillarTitle(pillar))
    }
}

private struct HealthspanContributorDetailView: View {
    let driver: HealthspanDriver
    let reference: Date
    @EnvironmentObject private var repo: Repository
    @EnvironmentObject private var model: AppModel
    @State private var observedSource: String?
    @State private var loadedSource: String?
    @State private var samples: [HealthspanHistory.Sample] = []
    @State private var windowDays = 180
    @State private var selectedOffset: Int?
    @GestureState private var horizontalDrag: Bool?
    @AppStorage("noop.coachEnabled") private var coachEnabled = true
    @AppStorage(UnitPrefs.systemKey) private var unitSystemRaw = UnitSystem.metric.rawValue
    private var unitSystem: UnitSystem { UnitSystem(rawValue: unitSystemRaw) ?? .metric }
    private var sourceID: String { healthspanSourceID(observedSource ?? model.deviceRegistry?.activeDeviceId, repo: repo) }
    private var points: [HealthspanHistory.Sample] { loadedSource == sourceID ? HealthspanHistory.points(samples: samples, windowDays: windowDays) : [] }
    var body: some View {
        let comparison = HealthspanHistory.comparison(samples: loadedSource == sourceID ? samples : [], weekly: driver.weekly)
        let reading = HealthspanHistory.selected(samples: points, daysAgo: selectedOffset ?? 0, windowDays: windowDays)
        ScrollView {
            VStack(alignment: .leading, spacing: NoopMetrics.sectionSpacing) {
                Text(reference, style: .date).font(StrandFont.caption)
                NoopCard {
                    VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                        comparisonRow(String(localized: "30-day average"), comparison.recentTenths, comparison.recentCount)
                        comparisonRow(String(localized: "Six-month average"), comparison.longTermTenths, comparison.longTermCount)
                        Text(driver.weekly ? String(localized: "Mean logged weekly time; unrecorded days remain gaps.") : String(localized: "Mean of recorded days; missing readings are excluded."))
                            .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                    }
                }
                HStack(spacing: NoopMetrics.gap) {
                    Button("30 days") { windowDays = 30; selectedOffset = nil }.disabled(windowDays == 30)
                    Button("Six months") { windowDays = 180; selectedOffset = nil }.disabled(windowDays == 180)
                }.font(StrandFont.headline).frame(minHeight: NoopMetrics.touchTarget)
                NoopCard {
                    VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                        if points.isEmpty {
                            Text("No recorded contributor data in this range.").font(StrandFont.body)
                        } else {
                            Chart {
                                ForEach(points, id: \.daysAgo) { point in
                                    PointMark(x: .value("Date", day(point.daysAgo)), y: .value("Value", chartValue(point.value)))
                                        .foregroundStyle(StrandPalette.positive)
                                }
                                if let reading {
                                    RuleMark(x: .value("Date", day(reading.daysAgo))).foregroundStyle(StrandPalette.textSecondary)
                                }
                            }.frame(height: NoopMetrics.chartHeight)
                                .chartYScale(domain: .automatic(includesZero: false))
                                .chartYAxisLabel(driver == .leanMass ? UnitFormatter.massUnit(unitSystem) : driver.unit)
                                .chartXScale(domain: day(windowDays - 1)...reference)
                                .chartOverlay { proxy in
                                    GeometryReader { geometry in
                                        let select: (CGPoint) -> Void = { location in
                                            guard let date: Date = proxy.value(atX: location.x - geometry[proxy.plotAreaFrame].origin.x) else { return }
                                            selectedOffset = Calendar.current.dateComponents([.day], from: Calendar.current.startOfDay(for: date), to: reference).day
                                        }
                                        Rectangle().fill(.clear).contentShape(Rectangle())
                                            .simultaneousGesture(SpatialTapGesture().onEnded { select($0.location) })
                                            .simultaneousGesture(DragGesture().updating($horizontalDrag) { event, state, _ in
                                                if state == nil { state = abs(event.translation.width) > abs(event.translation.height) }
                                            }.onChanged { event in
                                                if horizontalDrag ?? (abs(event.translation.width) > abs(event.translation.height)) { select(event.location) }
                                            })
                                    }
                                }
                            if let reading {
                                HStack {
                                    Text(day(reading.daysAgo), style: .date)
                                    Spacer()
                                    Text(formattedValue(reading.value))
                                }.font(StrandFont.caption)
                            }
                        }
                    }
                }
                NoopCard {
                    VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                        Text("Age Impact").font(StrandFont.headline)
                        Text("Unavailable: this local estimate does not assign years to individual drivers.")
                        Text("Targets and Age Impact are unavailable for this local estimate.")
                        Text("Review coverage and changes in your recorded habits before interpreting this comparison.")
                    }.font(StrandFont.body).foregroundStyle(StrandPalette.textSecondary)
                }
                if coachEnabled {
                    NavigationLink { CoachView() } label: { Text("Open Coach for guidance").font(StrandFont.headline).frame(minHeight: NoopMetrics.touchTarget) }
                    Text("Coach uses its existing setup and consent. No contributor data is sent automatically.")
                        .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                }
            }.padding(NoopMetrics.screenPadding).padding(.bottom, NoopMetrics.tabBarClearance)
        }.background(StrandPalette.surfaceBase).navigationTitle(driver.title)
            .onReceive(healthspanSourcePublisher(model: model, repo: repo)) { observedSource = $0 }
            .task(id: "\(sourceID)|\(repo.refreshSeq)|\(driver.rawValue)|\(reference.timeIntervalSince1970)") {
                let source = sourceID
                samples = []; selectedOffset = nil; loadedSource = nil
                guard await healthspanAwaitSource(source, repo: repo) else { return }
                let revision = repo.refreshSeq
                let resolved = await healthspanContributorSamples(repo: repo, reference: reference)
                guard healthspanSourceIsCurrent(source, model: model, repo: repo), revision == repo.refreshSeq else { return }
                samples = resolved[driver] ?? []; loadedSource = source
            }
    }
    private func formattedValue(_ value: Double) -> String {
        driver == .leanMass ? UnitFormatter.massFromKilograms(value, system: unitSystem)
            : String(format: "%.1f", locale: .current, value) + " " + driver.unit
    }
    private func chartValue(_ value: Double) -> Double {
        driver == .leanMass && unitSystem == .imperial ? UnitFormatter.kgToPounds(value) : value
    }
    private func day(_ offset: Int) -> Date { Calendar.current.date(byAdding: .day, value: -offset, to: reference)! }
    private func comparisonRow(_ title: String, _ tenths: Int?, _ count: Int) -> some View {
        VStack(alignment: .leading, spacing: NoopMetrics.space2) {
            Text(title).strandOverline()
            Text(tenths.map { formattedValue(Double($0) / 10) } ?? String(localized: "Unavailable")).font(StrandFont.title2)
            Text(String.localizedStringWithFormat(String(localized: "%lld recorded observations"), count)).font(StrandFont.caption)
        }
    }
}

@MainActor
private func healthspanContributorSamples(repo: Repository, reference: Date) async -> [HealthspanDriver: [HealthspanHistory.Sample]] {
    let today = Calendar.current.startOfDay(for: Date())
    let first = max(Calendar.current.date(byAdding: .day, value: -179, to: reference)!, Calendar.current.date(byAdding: .day, value: -3999, to: today)!)
    let from = Repository.dayString(first), to = Repository.dayString(reference)
    var result: [HealthspanDriver: [HealthspanHistory.Sample]] = [:]
    for (driver, key, source) in [(HealthspanDriver.sleepDuration, "sleep_total_min", "my-whoop"), (.sleepConsistency, "sleep_consistency", "my-whoop"), (.restingHR, "rhr", "my-whoop"), (.leanMass, "lean_mass", "apple-health")] {
        let values = await repo.resolvedSeries(key: key, source: source, from: from, to: to)
        result[driver] = values.points.compactMap { point in healthspanDaysAgo(point.day, reference: reference).map { .init(daysAgo: $0, value: point.value) } }
    }
    let estimated = await repo.resolvedSeries(key: "vo2max_est", source: "my-whoop", from: from, to: to)
    let imported = await repo.resolvedSeries(key: "vo2max", source: "apple-health", from: from, to: to)
    var vo2: [String: Double] = [:]
    for point in imported.points where point.value.isFinite && point.value > 0 { vo2[point.day] = point.value }
    for point in estimated.points where point.value.isFinite && point.value > 0 { vo2[point.day] = point.value }
    result[.vo2] = vo2.keys.sorted().compactMap { day in healthspanDaysAgo(day, reference: reference).map { .init(daysAgo: $0, value: vo2[day]!) } }
    let measured = await repo.resolvedSeries(key: "steps", source: "my-whoop", from: from, to: to)
    let measuredSteps = measured.points.map { HealthspanPresentation.StepSample(day: $0.day, count: $0.value, source: $0.source) }
    var importedSteps: [HealthspanPresentation.StepSample] = []
    if let store = await repo.storeHandle() {
        for source in ["apple-health", "health-connect"] {
            for row in (try? await store.appleDaily(deviceId: source, from: from, to: to)) ?? [] {
                if let count = row.steps { importedSteps.append(.init(day: row.day, count: Double(count), source: source)) }
            }
        }
    }
    result[.steps] = Set((measuredSteps + importedSteps).map(\.day)).sorted().compactMap { day in
        guard let sample = HealthspanPresentation.latestSteps(measured: measuredSteps, imported: importedSteps, fromDay: day, throughDay: day), let offset = healthspanDaysAgo(day, reference: reference) else { return nil }
        return .init(daysAgo: offset, value: sample.count)
    }
    let workouts = await repo.workoutRows(days: 4000)
    let activities = workouts.compactMap { row -> HealthspanHistory.Activity? in
        let day = Repository.dayString(Date(timeIntervalSince1970: Double(row.startTs)))
        guard day >= from, day <= to, row.endTs > row.startTs, let offset = healthspanDaysAgo(day, reference: reference) else { return nil }
        return .init(daysAgo: offset, durationSeconds: row.durationS ?? Double(row.endTs - row.startTs), zones: WorkoutZones.percents(row.zonesJSON), strength: HealthspanHistory.isStrength(sport: row.sport, source: row.source))
    }
    let activity = HealthspanHistory.activitySeries(activities)
    result[.lowZones] = activity[0]; result[.highZones] = activity[1]; result[.strength] = activity[2]
    return result
}

private func healthspanPillarTitle(_ pillar: Int) -> String {
    pillar == 0 ? String(localized: "Sleep") : pillar == 1 ? String(localized: "Strain") : String(localized: "Fitness")
}

func healthspanEligibilityLabel(_ eligibility: HealthspanHistory.Eligibility) -> String {
    switch eligibility.state {
    case .adultOnly: return String(localized: "Healthspan estimates require an adult profile.")
    case .initialCalibration: return String(localized: "Initial calibration")
    case .recentCoverage: return String(localized: "Insufficient recent recoveries")
    case .unavailable: return String(localized: "Unavailable")
    case .ready: return String(localized: "Local wellness estimate")
    }
}

func healthspanEligibilityDetail(_ eligibility: HealthspanHistory.Eligibility) -> String {
    eligibility.state == .initialCalibration
        ? String.localizedStringWithFormat(String(localized: "Initial calibration: %lld of 90 days of local history."), min(90, eligibility.initialDays))
        : String.localizedStringWithFormat(String(localized: "Calibrating (%lld of %lld)"), eligibility.recoveryDays, 21)
}
