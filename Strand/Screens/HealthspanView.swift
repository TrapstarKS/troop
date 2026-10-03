import SwiftUI
import Charts
import Combine
import StrandAnalytics
import StrandDesign
import WhoopStore

struct HealthspanView: View {
    @EnvironmentObject private var repo: Repository
    @EnvironmentObject private var model: AppModel
    @State private var observedSource: String?
    @State private var loadedSource: String?
    @EnvironmentObject private var profile: ProfileStore
    @State private var series: [(day: String, value: Double)] = []
    @State private var days: [DailyMetric] = []
    @State private var reference = Calendar.current.startOfDay(for: Date())
    @State private var showMethod = false
    @State private var selectedAgeDay: String?
    @GestureState private var ageDragIsHorizontal: Bool?

    private var sourceID: String { healthspanSourceID(observedSource ?? model.deviceRegistry?.activeDeviceId, repo: repo) }
    private var sourceLoaded: Bool { loadedSource == sourceID }
    private var earliestReference: Date {
        let today = Calendar.current.startOfDay(for: Date())
        let offsets = days.compactMap { healthspanDaysAgo($0.day, reference: today) }
        return Calendar.current.date(byAdding: .day, value: -HealthspanHistory.oldestReferenceOffset(dayOffsets: offsets), to: today)!
    }
    private var chronologicalAge: Int { Calendar.current.dateComponents([.year], from: Calendar.current.startOfDay(for: profile.dateOfBirth), to: reference).year ?? profile.age }
    private var snapshot: HealthspanPresentation.Snapshot {
        healthspanSnapshot(series: sourceLoaded ? series : [], days: sourceLoaded ? days : [], age: chronologicalAge, reference: reference)
    }
    private var isCalibrating: Bool { snapshot.eligibility.state == .initialCalibration || snapshot.eligibility.state == .recentCoverage }
    private var trendPoints: [HealthspanTrendPoint] {
        var points: [HealthspanTrendPoint] = []
        var previousOffset: Int?
        var segment = 0
        for sample in sourceLoaded ? series : [] {
            guard sample.value.isFinite, (20...90).contains(sample.value),
                  let offset = healthspanDaysAgo(sample.day, reference: reference), (0..<180).contains(offset),
                  let date = healthspanDate(sample.day) else { continue }
            if let previousOffset, previousOffset - offset > 14 { segment += 1 }
            points.append(HealthspanTrendPoint(day: sample.day, value: sample.value, date: date, segment: segment))
            previousOffset = offset
        }
        return points
    }

    var body: some View {
        ScrollView {
            VStack(spacing: NoopMetrics.sectionSpacing) {
                healthspanDateSelector(reference: reference, days: 7, previous: { reference = max(earliestReference, Calendar.current.date(byAdding: .day, value: -7, to: reference)!) }, next: { reference = min(Calendar.current.startOfDay(for: Date()), Calendar.current.date(byAdding: .day, value: 7, to: reference)!) }, canAdvance: reference < Calendar.current.startOfDay(for: Date()), canGoBack: reference > earliestReference)
                HealthspanOrb(age: snapshot.age, chronologicalAge: chronologicalAge)
                VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                    Text("Pace of Aging").strandOverline()
                    Text(snapshot.pace.map { String(format: "%.1f×", locale: .current, $0) } ?? "—")
                        .font(StrandFont.title1).frame(maxWidth: .infinity)
                    HealthspanPaceRuler(pace: snapshot.pace)
                    HStack { Text("Slower"); Spacer(); Text("Faster") }
                        .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                }
                NoopCard {
                    VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                        Text(healthspanEligibilityLabel(snapshot.eligibility)).font(StrandFont.headline)
                        if isCalibrating {
                            Text(healthspanEligibilityDetail(snapshot.eligibility))
                        }
                        Text("A wellness estimate from your habits, not a clinical biological age.")
                            .font(StrandFont.body).foregroundStyle(StrandPalette.textSecondary)
                        if snapshot.age != nil && snapshot.pace == nil { Text("Pace needs 90 days of local history.").font(StrandFont.caption) }
                        Button("How this estimate works") { showMethod = true }.font(StrandFont.headline).frame(minHeight: NoopMetrics.touchTarget)
                    }
                }
                if snapshot.eligibility.state == .ready && !trendPoints.isEmpty { ageTrend }
                HealthspanPillarsView(reference: reference)
                HealthSupportingMetricCards()
            }
            .padding(NoopMetrics.screenPadding)
            .padding(.bottom, NoopMetrics.tabBarClearance)
            .frame(maxWidth: NoopMetrics.detailSheetMinWidth)
            .frame(maxWidth: .infinity)
        }
        .background(StrandPalette.surfaceBase)
        .navigationTitle(String(localized: "Healthspan"))
        .onChange(of: sourceID) { _ in selectedAgeDay = nil }
        .onChange(of: reference) { _ in selectedAgeDay = nil }
        .onChange(of: trendPoints) { _ in selectedAgeDay = nil }
        .onReceive(healthspanSourcePublisher(model: model, repo: repo)) { observedSource = $0 }
        .task(id: "\(sourceID)|\(repo.refreshSeq)") {
            let source = sourceID
            guard await healthspanAwaitSource(source, repo: repo) else { return }
            if loadedSource != source { await repo.refresh() }
            let revision = repo.refreshSeq
            let resolvedDays = repo.days.filter { healthspanDaysAgo($0.day, reference: Calendar.current.startOfDay(for: Date())).map { (0..<4000).contains($0) } ?? false }
            let resolvedAge = await repo.exploreSeries(key: "body_age", source: "my-whoop", days: 4000)
            guard healthspanSourceIsCurrent(source, model: model, repo: repo), revision == repo.refreshSeq else { return }
            days = resolvedDays; series = resolvedAge; loadedSource = source
            reference = max(reference, earliestReference)
        }
        .sheet(isPresented: $showMethod) {
            ScrollView {
                VStack(alignment: .leading, spacing: NoopMetrics.space5) {
                    Text("How this estimate works").font(StrandFont.title2)
                    Text("Age uses the birthdate saved in Profile. Check it before interpreting this estimate.")
                    Text("NOOP Age uses the existing weekly Body Age estimate with an uncertainty of ±5 years.")
                    Text("Initial calibration uses a 90-day span from the earliest valid dated recovery. It does not prove continuous wear. Continued use needs 21 recoveries in 31 days.")
                    Text("Pace compares the last 30 days with up to 180 days of Body Age history. It is an unvalidated local trend, not a medical prediction.")
                    Text(verbatim: "1 + 2 × (μ₃₀ − μ₁₈₀)").font(StrandFont.mono)
                    Text("Sleep, resting heart rate, HRV and steps feed Body Age. Fitness Age is shown separately; lean mass is unavailable unless supplied.")
                    Button("Done") { showMethod = false }
                }.font(StrandFont.body).padding(NoopMetrics.screenPadding)
            }.background(StrandPalette.surfaceBase)
        }
    }

    private var ageTrend: some View {
        let points = trendPoints
        let reading = points.first { $0.day == selectedAgeDay } ?? points.last
        return NoopCard {
            VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                TrackedSectionHeader(title: String(localized: "NOOP Age trend"))
                Chart {
                    ForEach(points) { point in
                        LineMark(x: .value("Date", point.date), y: .value("Age", point.value), series: .value("Segment", point.segment))
                            .foregroundStyle(StrandPalette.positive)
                        PointMark(x: .value("Date", point.date), y: .value("Age", point.value))
                            .foregroundStyle(StrandPalette.positive)
                    }
                    if let reading {
                        PointMark(x: .value("Date", reading.date), y: .value("Age", reading.value))
                            .foregroundStyle(StrandPalette.textPrimary)
                        if selectedAgeDay != nil {
                            RuleMark(x: .value("Date", reading.date)).foregroundStyle(StrandPalette.textSecondary)
                        }
                    }
                }.frame(height: NoopMetrics.chartHeight)
                .chartXScale(domain: Calendar.current.date(byAdding: .day, value: -179, to: reference)!...reference)
                .chartYScale(domain: .automatic(includesZero: false))
                .chartYAxisLabel(String(localized: "Age"))
                .accessibilityLabel(String(localized: "NOOP Age trend"))
                .chartOverlay { proxy in
                    GeometryReader { geometry in
                        let select: (CGPoint) -> Void = { location in
                            let x = location.x - geometry[proxy.plotAreaFrame].origin.x
                            guard let date: Date = proxy.value(atX: x) else { return }
                            selectedAgeDay = points.min { abs($0.date.timeIntervalSince(date)) < abs($1.date.timeIntervalSince(date)) }?.day
                        }
                        Rectangle().fill(.clear).contentShape(Rectangle())
                            .simultaneousGesture(SpatialTapGesture().onEnded { select($0.location) })
                            .simultaneousGesture(DragGesture()
                                .updating($ageDragIsHorizontal) { event, horizontal, _ in
                                    if horizontal == nil { horizontal = abs(event.translation.width) > abs(event.translation.height) }
                                }
                                .onChanged { event in
                                    guard ageDragIsHorizontal ?? (abs(event.translation.width) > abs(event.translation.height)) else { return }
                                    select(event.location)
                                })
                    }
                }
                if let reading {
                    HStack {
                        Text(reading.date, style: .date)
                        Spacer()
                        Text("NOOP Age")
                        Text(String(format: "%.1f", locale: .current, reading.value))
                    }.font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                }
            }
        }
    }


}

struct HealthspanPreviewCard: View {
    @EnvironmentObject private var repo: Repository
    @EnvironmentObject private var model: AppModel
    @State private var observedSource: String?
    @State private var loadedSource: String?
    @EnvironmentObject private var profile: ProfileStore
    @State private var series: [(day: String, value: Double)] = []
    @State private var days: [DailyMetric] = []
    private var sourceID: String { healthspanSourceID(observedSource ?? model.deviceRegistry?.activeDeviceId, repo: repo) }
    var body: some View {
        let snapshot = healthspanSnapshot(series: loadedSource == sourceID ? series : [], days: loadedSource == sourceID ? days : [], age: profile.age, reference: Date())
        NavigationLink(value: TabRoute.healthspan) {
            NoopCard(tint: StrandPalette.positive) {
                HStack(spacing: NoopMetrics.space4) {
                    HealthspanOrb(age: snapshot.age, chronologicalAge: profile.age, compact: true)
                    VStack(alignment: .leading, spacing: NoopMetrics.space2) {
                        Text("Healthspan").font(StrandFont.headline)
                        Text(healthspanEligibilityLabel(snapshot.eligibility)).font(StrandFont.caption)
                        if let pace = snapshot.pace { Text(String(format: "%.1f×", locale: .current, pace)).font(StrandFont.bodyNumber) }
                    }
                    Spacer()
                    Image(systemName: "chevron.right")
                }.foregroundStyle(StrandPalette.textPrimary)
            }
        }.buttonStyle(.plain)
        .onReceive(healthspanSourcePublisher(model: model, repo: repo)) { observedSource = $0 }
        .task(id: "\(sourceID)|\(repo.refreshSeq)") {
            let source = sourceID
            guard await healthspanAwaitSource(source, repo: repo) else { return }
            if loadedSource != source { await repo.refresh() }
            let revision = repo.refreshSeq
            let resolvedDays = repo.days.filter { healthspanDaysAgo($0.day, reference: Calendar.current.startOfDay(for: Date())).map { (0..<4000).contains($0) } ?? false }
            let resolvedAge = await repo.exploreSeries(key: "body_age", source: "my-whoop", days: 4000)
            guard healthspanSourceIsCurrent(source, model: model, repo: repo), revision == repo.refreshSeq else { return }
            days = resolvedDays; series = resolvedAge; loadedSource = source
        }
    }
}

struct HealthSupportingMetricCards: View {
    @EnvironmentObject private var repo: Repository
    @EnvironmentObject private var model: AppModel
    @State private var observedSource: String?
    @State private var loadedSource: String?
    @State private var vo2: MetricSeriesResolution?
    @State private var steps: HealthspanPresentation.StepSample?
    private var sourceID: String { healthspanSourceID(observedSource ?? model.deviceRegistry?.activeDeviceId, repo: repo) }
    var body: some View {
        VStack(spacing: NoopMetrics.gap) {
            metric("VO₂max estimate", point: loadedSource == sourceID ? vo2?.points.last { $0.value.isFinite && $0.value > 0 } : nil, unit: "ml/kg/min")
            metric("Steps", point: loadedSource == sourceID ? steps.map { ResolvedMetricPoint(day: $0.day, value: $0.count, source: $0.source, sourceKey: "steps") } : nil, unit: "")
        }
        .onReceive(healthspanSourcePublisher(model: model, repo: repo)) { observedSource = $0 }
        .task(id: "\(sourceID)|\(repo.refreshSeq)") {
            let source = sourceID
            guard await healthspanAwaitSource(source, repo: repo) else { return }
            let revision = repo.refreshSeq
            let clock = Calendar.current.startOfDay(for: Date())
            let fromDay = Repository.dayString(Calendar.current.date(byAdding: .day, value: -30, to: clock)!)
            let throughDay = Repository.dayString(clock)
            var resolvedVO2 = await repo.resolvedSeries(key: "vo2max_est", source: "my-whoop", days: 180)
            if resolvedVO2.points.contains(where: { $0.value.isFinite && $0.value > 0 }) != true { resolvedVO2 = await repo.resolvedSeries(key: "vo2max", source: "apple-health", days: 180) }
            let measuredSteps = await repo.resolvedSeries(key: "steps", source: "my-whoop", days: 31)
            var importedSteps: [HealthspanPresentation.StepSample] = []
            if let store = await repo.storeHandle() {
                for importedSource in ["apple-health", "health-connect"] {
                    let rows = (try? await store.appleDaily(deviceId: importedSource, from: fromDay, to: throughDay)) ?? []
                    importedSteps += rows.compactMap { row in row.steps.map { .init(day: row.day, count: Double($0), source: importedSource) } }
                }
            }
            let resolvedSteps = HealthspanPresentation.latestSteps(
                measured: measuredSteps.points.map { .init(day: $0.day, count: $0.value, source: $0.source) },
                imported: importedSteps, fromDay: fromDay, throughDay: throughDay)
            guard healthspanSourceIsCurrent(source, model: model, repo: repo), revision == repo.refreshSeq else { return }
            vo2 = resolvedVO2; steps = resolvedSteps; loadedSource = source
        }
    }
    private func metric(_ title: LocalizedStringKey, point latest: ResolvedMetricPoint?, unit: String) -> some View {
        return NoopCard {
            HStack {
                VStack(alignment: .leading, spacing: NoopMetrics.space2) {
                    Text(title).font(StrandFont.headline)
                    if let latest {
                        HStack(spacing: NoopMetrics.space2) {
                            Text(latest.day)
                            if ["apple-health", "health-connect"].contains(latest.source) { Text("Imported") }
                        }.font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                    }
                }
                Spacer()
                if let latest {
                    Text(String(format: unit.isEmpty ? "%.0f" : "%.1f", locale: .current, latest.value)).font(StrandFont.title2)
                    Text(unit).font(StrandFont.caption)
                } else { Text("Unavailable").font(StrandFont.caption) }
            }
        }
    }
}

private struct HealthspanTrendPoint: Identifiable, Equatable {
    let day: String
    let value: Double
    let date: Date
    let segment: Int
    var id: String { day }
}

private struct HealthspanOrb: View {
    let age: Double?
    let chronologicalAge: Int
    var compact = false
    var body: some View {
        ZStack {
            Canvas { context, size in
                let diameter = min(size.width, size.height)
                let center = CGPoint(x: size.width / 2, y: size.height / 2)
                let rect = CGRect(x: center.x - diameter / 2, y: center.y - diameter / 2, width: diameter, height: diameter)
                context.fill(Path(ellipseIn: rect), with: .radialGradient(Gradient(colors: [StrandPalette.surfaceBase, StrandPalette.positive.opacity(0.03), StrandPalette.positive.opacity(0.32)]), center: center, startRadius: 0, endRadius: diameter / 2))
                context.stroke(Path(ellipseIn: rect.insetBy(dx: NoopMetrics.hairlineWidth, dy: NoopMetrics.hairlineWidth)), with: .color(StrandPalette.positive.opacity(0.5)), lineWidth: NoopMetrics.hairlineWidth)
                for i in 0..<160 {
                    let angle = Double(i) * 2.3999632297
                    let radius = diameter * (0.27 + 0.22 * Double((i * 37) % 101) / 100)
                    let point = CGPoint(x: center.x + cos(angle) * radius, y: center.y + sin(angle) * radius)
                    let dot = diameter * (0.003 + 0.006 * Double(i % 5) / 4)
                    context.fill(Path(ellipseIn: CGRect(x: point.x, y: point.y, width: dot, height: dot)), with: .color(StrandPalette.positive.opacity(0.25 + Double(i % 4) * 0.15)))
                }
            }
            VStack(spacing: NoopMetrics.space2) {
                Text(age.map { String(format: "%.1f", locale: .current, $0) } ?? "—").font(compact ? StrandFont.title2 : StrandFont.display())
                if !compact {
                    Text("NOOP Age").strandOverline()
                    Text("Profile age").font(StrandFont.caption)
                    Text(String(chronologicalAge)).font(StrandFont.bodyNumber)
                    if let age { Text(String(format: "%+.1f", locale: .current, age - Double(chronologicalAge))).font(StrandFont.headline).foregroundStyle(StrandPalette.textSecondary) }
                    Text("±5 years").font(StrandFont.caption)
                }
            }.foregroundStyle(StrandPalette.textPrimary)
        }
        .frame(width: compact ? NoopMetrics.tileHeight : nil, height: compact ? NoopMetrics.tileHeight : NoopMetrics.scoreDialDiameter)
        .accessibilityElement(children: .combine)
    }
}

private struct HealthspanPaceRuler: View {
    let pace: Double?
    var body: some View {
        Canvas { context, size in
            for i in 0...60 {
                let x = size.width * Double(i) / 60
                var tick = Path(); tick.move(to: CGPoint(x: x, y: size.height * 0.2)); tick.addLine(to: CGPoint(x: x, y: size.height * 0.8))
                context.stroke(tick, with: .color(StrandPalette.hairlineStrong), lineWidth: NoopMetrics.hairlineWidth)
            }
            if let pace {
                let x = size.width * (pace + 1) / 4
                var marker = Path(); marker.move(to: CGPoint(x: x, y: 0)); marker.addLine(to: CGPoint(x: x, y: size.height))
                context.stroke(marker, with: .color(StrandPalette.textPrimary), lineWidth: NoopMetrics.spaceHalf)
            }
        }.frame(height: NoopMetrics.controlHeight)
        .overlay(alignment: .bottom) { HStack { Text("−1×"); Spacer(); Text("1×"); Spacer(); Text("3×") }.font(StrandFont.captionNumber).offset(y: NoopMetrics.space4) }
        .padding(.bottom, NoopMetrics.space4)
        .accessibilityLabel(String(localized: "Pace of Aging"))
        .accessibilityValue(pace.map { String(format: "%.1f×", locale: .current, $0) } ?? String(localized: "Unavailable"))
    }
}

func healthspanDate(_ day: String) -> Date? {
    let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.dateFormat = "yyyy-MM-dd"; formatter.timeZone = .current
    return formatter.date(from: day)
}

func healthspanDaysAgo(_ day: String, reference: Date) -> Int? {
    guard let date = healthspanDate(day) else { return nil }
    return Calendar.current.dateComponents([.day], from: Calendar.current.startOfDay(for: date), to: Calendar.current.startOfDay(for: reference)).day
}

func healthspanSnapshot(series: [(day: String, value: Double)], days: [DailyMetric], age: Int, reference: Date) -> HealthspanPresentation.Snapshot {
    let samples = series.compactMap { point -> HealthspanPresentation.AgeSample? in
        guard let offset = healthspanDaysAgo(point.day, reference: reference) else { return nil }
        return .init(daysAgo: offset, age: point.value)
    }
    let offsets = days.compactMap { row -> Int? in
        guard let recovery = row.recovery, recovery.isFinite, (0...100).contains(recovery) else { return nil }
        return healthspanDaysAgo(row.day, reference: reference)
    }
    return HealthspanPresentation.snapshot(samples: samples, recoveryOffsets: offsets, chronologicalAge: Double(age))
}

func healthspanDateSelector(reference: Date, days: Int = 1, previous: @escaping () -> Void, next: @escaping () -> Void, canAdvance: Bool, canGoBack: Bool = true) -> some View {
    HStack {
        Button(action: previous) { Image(systemName: "chevron.left").frame(width: NoopMetrics.touchTarget, height: NoopMetrics.touchTarget) }.disabled(!canGoBack).accessibilityLabel(String(localized: "Previous"))
        Spacer()
        Text(healthspanDateLabel(reference: reference, days: days)).font(StrandFont.headline)
        Spacer()
        Button(action: next) { Image(systemName: "chevron.right").frame(width: NoopMetrics.touchTarget, height: NoopMetrics.touchTarget) }.disabled(!canAdvance).accessibilityLabel(String(localized: "Next"))
    }.foregroundStyle(StrandPalette.textPrimary)
}

func healthspanDuration(_ minutes: Int) -> String {
    let m = max(0, minutes)
    if m < 60 { return String(localized: "\(m)m") }
    return String(localized: "\(m / 60)h \(m % 60)m")
}

private func healthspanDateLabel(reference: Date, days: Int) -> String {
    guard days > 1, let start = Calendar.current.date(byAdding: .day, value: -(days - 1), to: reference) else { return reference.formatted(date: .abbreviated, time: .omitted) }
    let formatter = DateIntervalFormatter()
    formatter.dateStyle = .medium
    formatter.timeStyle = .none
    return formatter.string(from: start, to: reference)
}

@MainActor func healthspanSourceID(_ registryID: String?, repo: Repository) -> String {
    let id = registryID?.trimmingCharacters(in: .whitespaces) ?? ""
    return id.isEmpty ? repo.deviceId : id
}

@MainActor func healthspanSourcePublisher(model: AppModel, repo: Repository) -> AnyPublisher<String, Never> {
    model.deviceRegistry?.$activeDeviceId.eraseToAnyPublisher() ?? Just(repo.deviceId).eraseToAnyPublisher()
}

@MainActor func healthspanAwaitSource(_ source: String, repo: Repository) async -> Bool {
    while repo.deviceId != source {
        guard !Task.isCancelled else { return false }
        do { try await Task.sleep(nanoseconds: 10_000_000) } catch { return false }
    }
    return !Task.isCancelled
}

@MainActor func healthspanSourceIsCurrent(_ source: String, model: AppModel, repo: Repository) -> Bool {
    !Task.isCancelled && repo.deviceId == source && healthspanSourceID(model.deviceRegistry?.activeDeviceId, repo: repo) == source
}
