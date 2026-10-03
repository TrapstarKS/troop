import SwiftUI
import Charts
import StrandAnalytics
import StrandDesign
import WhoopStore

struct HealthspanView: View {
    @EnvironmentObject private var repo: Repository
    @EnvironmentObject private var profile: ProfileStore
    @State private var series: [(day: String, value: Double)] = []
    @State private var fitness: [(day: String, value: Double)] = []
    @State private var reference = Calendar.current.startOfDay(for: Date())
    @State private var showMethod = false

    private var earliestReference: Date {
        let today = Calendar.current.startOfDay(for: Date())
        guard let first = repo.days.compactMap({ healthspanDate($0.day) }).min(),
              let completeWindow = Calendar.current.date(byAdding: .day, value: 30, to: first) else { return today }
        return min(today, completeWindow)
    }
    private var chronologicalAge: Int { Calendar.current.dateComponents([.year], from: Calendar.current.startOfDay(for: profile.dateOfBirth), to: reference).year ?? profile.age }
    private var snapshot: HealthspanPresentation.Snapshot {
        healthspanSnapshot(series: series, days: repo.days, age: chronologicalAge, reference: reference)
    }
    private var window: [DailyMetric] {
        repo.days.filter { healthspanDaysAgo($0.day, reference: reference).map { (0..<7).contains($0) } ?? false }
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
                        Text(snapshot.age == nil ? String(localized: "Calibrating") : String(localized: "Local wellness estimate")).font(StrandFont.headline)
                        if snapshot.age == nil {
                            Text(String.localizedStringWithFormat(String(localized: "Calibrating (%lld of %lld)"), snapshot.recoveryDays, 21))
                        }
                        Text("A wellness estimate from your habits, not a clinical biological age.")
                            .font(StrandFont.body).foregroundStyle(StrandPalette.textSecondary)
                        if snapshot.pace == nil { Text("Pace needs 90 days of local history.").font(StrandFont.caption) }
                        Button("How this estimate works") { showMethod = true }.font(StrandFont.headline).frame(minHeight: NoopMetrics.touchTarget)
                    }
                }
                if !series.isEmpty { ageTrend }
                VStack(alignment: .leading, spacing: NoopMetrics.gap) {
                    TrackedSectionHeader(title: String(localized: "Contributors"))
                    contributor(String(localized: "Sleep"), symbol: "moon.fill", value: average(window.compactMap(\.totalSleepMin).filter { $0.isFinite && $0 > 0 }).map { healthspanDuration(Int($0)) })
                    contributor(String(localized: "Strain"), symbol: "figure.run", value: average(window.compactMap(\.strain).filter { $0.isFinite && (0...100).contains($0) }).map { String(format: "%.0f / 100", locale: .current, $0) })
                    contributor(String(localized: "Fitness Age"), symbol: "heart.fill", value: fitness.last(where: { $0.value.isFinite && (20...90).contains($0.value) && (healthspanDaysAgo($0.day, reference: reference).map { (0...14).contains($0) } ?? false) }).map { String(format: "%.1f", locale: .current, $0.value) })
                    Text("Recent context; not a breakdown of age impact.").font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                }
                HealthSupportingMetricCards()
            }
            .padding(NoopMetrics.screenPadding)
            .padding(.bottom, NoopMetrics.tabBarClearance)
            .frame(maxWidth: NoopMetrics.detailSheetMinWidth)
            .frame(maxWidth: .infinity)
        }
        .background(StrandPalette.surfaceBase)
        .navigationTitle(String(localized: "Healthspan"))
        .task(id: repo.refreshSeq) {
            series = await repo.exploreSeries(key: "body_age", source: "my-whoop", days: 4000)
            fitness = await repo.exploreSeries(key: "fitness_age", source: "my-whoop", days: 4000)
            reference = max(reference, earliestReference)
        }
        .sheet(isPresented: $showMethod) {
            ScrollView {
                VStack(alignment: .leading, spacing: NoopMetrics.space5) {
                    Text("How this estimate works").font(StrandFont.title2)
                    Text("NOOP Age uses the existing weekly Body Age estimate with an uncertainty of ±5 years.")
                    Text("Pace compares the last 30 days with up to 180 days of Body Age history. It is an unvalidated local trend, not a medical prediction.")
                    Text(verbatim: "1 + 2 × (μ₃₀ − μ₁₈₀)").font(StrandFont.mono)
                    Text("Sleep, resting heart rate, HRV and steps feed Body Age. Fitness Age is shown separately; lean mass is unavailable unless supplied.")
                    Button("Done") { showMethod = false }
                }.font(StrandFont.body).padding(NoopMetrics.screenPadding)
            }.background(StrandPalette.surfaceBase)
        }
    }

    private var ageTrend: some View {
        NoopCard {
            VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                TrackedSectionHeader(title: String(localized: "NOOP Age trend"))
                Chart {
                    ForEach(series.filter { $0.value.isFinite && (20...90).contains($0.value) && (healthspanDaysAgo($0.day, reference: reference).map { (0..<180).contains($0) } ?? false) }.map { HealthspanTrendPoint(day: $0.day, value: $0.value) }) { point in
                        if let date = healthspanDate(point.day) {
                            LineMark(x: .value("Date", date), y: .value("Age", point.value))
                                .foregroundStyle(StrandPalette.positive)
                            PointMark(x: .value("Date", date), y: .value("Age", point.value))
                                .foregroundStyle(StrandPalette.positive)
                        }
                    }
                }.frame(height: NoopMetrics.chartHeight)
                .chartYAxisLabel(String(localized: "Age"))
                .accessibilityLabel(String(localized: "NOOP Age trend"))
            }
        }
    }

    private func contributor(_ title: String, symbol: String, value: String?) -> some View {
        NoopCard { ContributorRow(label: title, value: value ?? String(localized: "Unavailable"), systemImage: symbol) }
    }
}

struct HealthspanPreviewCard: View {
    @EnvironmentObject private var repo: Repository
    @EnvironmentObject private var profile: ProfileStore
    @State private var series: [(day: String, value: Double)] = []
    var body: some View {
        let snapshot = healthspanSnapshot(series: series, days: repo.days, age: profile.age, reference: Date())
        NavigationLink { HealthspanView() } label: {
            NoopCard(tint: StrandPalette.positive) {
                HStack(spacing: NoopMetrics.space4) {
                    HealthspanOrb(age: snapshot.age, chronologicalAge: profile.age, compact: true)
                    VStack(alignment: .leading, spacing: NoopMetrics.space2) {
                        Text("Healthspan").font(StrandFont.headline)
                        Text(snapshot.age == nil ? String(localized: "Calibrating") : String(localized: "Local wellness estimate")).font(StrandFont.caption)
                        if let pace = snapshot.pace { Text(String(format: "%.1f×", locale: .current, pace)).font(StrandFont.bodyNumber) }
                    }
                    Spacer()
                    Image(systemName: "chevron.right")
                }.foregroundStyle(StrandPalette.textPrimary)
            }
        }.buttonStyle(.plain)
        .task(id: repo.refreshSeq) { series = await repo.exploreSeries(key: "body_age", source: "my-whoop", days: 180) }
    }
}

struct HealthSupportingMetricCards: View {
    @EnvironmentObject private var repo: Repository
    @State private var vo2: MetricSeriesResolution?
    @State private var steps: MetricSeriesResolution?
    var body: some View {
        VStack(spacing: NoopMetrics.gap) {
            metric("VO₂max estimate", series: vo2, unit: "ml/kg/min")
            metric("Steps", series: steps, unit: "")
        }
        .task(id: repo.refreshSeq) {
            vo2 = await repo.resolvedSeries(key: "vo2max_est", source: "my-whoop", days: 180)
            if vo2?.points.isEmpty != false { vo2 = await repo.resolvedSeries(key: "vo2max", source: "apple-health", days: 180) }
            steps = await repo.resolvedSeries(key: "steps", source: "my-whoop", days: 31)
        }
    }
    private func metric(_ title: LocalizedStringKey, series: MetricSeriesResolution?, unit: String) -> some View {
        let latest = series?.points.last { $0.value.isFinite && (unit.isEmpty ? $0.value >= 0 : $0.value > 0) }
        return NoopCard {
            HStack {
                VStack(alignment: .leading, spacing: NoopMetrics.space2) {
                    Text(title).font(StrandFont.headline)
                    if let latest { Text(latest.day).font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary) }
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

private struct HealthspanTrendPoint: Identifiable {
    let day: String
    let value: Double
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
                context.stroke(Path(ellipseIn: rect.insetBy(dx: 1, dy: 1)), with: .color(StrandPalette.positive.opacity(0.5)), lineWidth: NoopMetrics.hairlineWidth)
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
                    Text("Chronological age").font(StrandFont.caption)
                    Text(String(chronologicalAge)).font(StrandFont.bodyNumber)
                    if let age { Text(String(format: "%+.1f", locale: .current, age - Double(chronologicalAge))).font(StrandFont.headline).foregroundStyle(StrandPalette.positive) }
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
    let recoveryDays = Set(days.filter { $0.recovery != nil && (healthspanDaysAgo($0.day, reference: reference).map { (0..<31).contains($0) } ?? false) }.map(\.day)).count
    return HealthspanPresentation.snapshot(samples: samples, recoveryDays: recoveryDays, chronologicalAge: Double(age))
}

private func average(_ values: [Double]) -> Double? { values.isEmpty ? nil : values.reduce(0, +) / Double(values.count) }

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
