import SwiftUI
import Charts
import Combine
import StrandAnalytics
import StrandDesign
import WhoopStore

struct StressMonitorView: View {
    @EnvironmentObject private var repo: Repository
    @State private var selectedDate = Calendar.current.startOfDay(for: Date())
    @State private var result: DaytimeStress.Result = .empty
    @State private var latestSampleTs: Int?
    @State private var firstSampleTs: Int?
    @State private var clock = Date()
    @State private var workouts: [WorkoutRow] = []
    @State private var stored: [(day: String, value: Double)] = []
    @State private var loading = true
    @State private var showBreathing = false
    @State private var selectedTs: Int?
    @State private var observedEnd = 0

    private var date: Date { selectedDate }
    private var startTs: Int { Int(Calendar.current.startOfDay(for: date).timeIntervalSince1970) }
    private var endTs: Int { Int(Calendar.current.startOfDay(for: Calendar.current.date(byAdding: .day, value: 1, to: date)!).timeIntervalSince1970) }
    private var selected: DaytimeStress.HourPoint? { selectedTs.flatMap { ts in result.timeline.first { $0.startTs == ts } } }
    private var latest: DaytimeStress.HourPoint? { result.timeline.last { $0.level != nil } }
    private var current: DaytimeStress.HourPoint? {
        if let selected { return selected }
        guard let latest, let latestSampleTs else { return nil }
        let windowEnd = min(latest.startTs + DaytimeStress.bucketSeconds, latestSampleTs)
        return date < Calendar.current.startOfDay(for: clock) || Int(clock.timeIntervalSince1970) - windowEnd <= 900 ? latest : nil
    }
    private var daily: (day: String, value: Double)? { stored.first { $0.value.isFinite && (0...3).contains($0.value) && healthspanDaysAgo($0.day, reference: date) == 0 } }
    private var minutes: [Int] {
        HealthspanPresentation.zoneMinutes(hours: result.hours.map { point in
            (level: point.level, minutes: max(0, min(point.startTs + DaytimeStress.bucketSeconds, observedEnd) - max(point.startTs, firstSampleTs ?? point.startTs)) / 60)
        })
    }

    var body: some View {
        ScrollView {
            VStack(spacing: NoopMetrics.sectionSpacing) {
                healthspanDateSelector(reference: date, previous: { selectedDate = Calendar.current.date(byAdding: .day, value: -1, to: selectedDate)!; selectedTs = nil }, next: { selectedDate = min(Calendar.current.startOfDay(for: clock), Calendar.current.date(byAdding: .day, value: 1, to: selectedDate)!); selectedTs = nil }, canAdvance: selectedDate < Calendar.current.startOfDay(for: clock))
                StressMonitorGauge(value: current?.level)
                VStack(spacing: NoopMetrics.space2) {
                    Text(current == nil ? (loading ? String(localized: "Loading…") : String(localized: "No current reading")) : (selected == nil ? String(localized: "Latest recorded window") : String(localized: "Selected window")))
                        .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                    if let point = current {
                        Text(Date(timeIntervalSince1970: Double(point.startTs)), style: .time).font(StrandFont.captionNumber)
                    }
                }
                if !result.timeline.isEmpty { timeline }
                NoopCard {
                    VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                        Text("Physiological stress estimate").font(StrandFont.headline)
                        if let daily {
                            HStack { Text("Daily estimate"); Spacer(); Text(String(format: "%.1f / 3", locale: .current, daily.value)).font(StrandFont.bodyNumber) }
                        }
                        InsightCallout(text: String(localized: "Hourly heart-rate context, not a mental-health diagnosis. Gaps are unscored."), actionLabel: String(localized: "Start breathwork"), onAction: { showBreathing = true })
                    }
                }
                NoopCard {
                    VStack(alignment: .leading, spacing: NoopMetrics.space4) {
                        Text("Time in stress zones").font(StrandFont.headline)
                        if minutes.reduce(0, +) == 0 {
                            Text("Unavailable").font(StrandFont.body).foregroundStyle(StrandPalette.textSecondary)
                        } else {
                            ForEach(0..<3) { index in
                                HStack {
                                    Text(zoneTitle(index)).font(StrandFont.body)
                                    GeometryReader { geometry in
                                        Capsule().fill(StrandPalette.hairline)
                                            .overlay(alignment: .leading) {
                                                Capsule().fill(StressMonitorRamp.color(Double(index) + 0.5)).frame(width: geometry.size.width * Double(minutes[index]) / Double(max(1, minutes.reduce(0, +))))
                                            }
                                    }.frame(height: NoopMetrics.indicatorTrackHeight)
                                    Text(healthspanDuration(minutes[index])).font(StrandFont.captionNumber)
                                }
                            }
                            Text("Estimated from non-overlapping hourly windows.").font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                        }
                    }
                }
                NavigationLink { StressView() } label: { Text("Stress history and advanced HRV").font(StrandFont.headline) }
            }.padding(NoopMetrics.screenPadding).padding(.bottom, NoopMetrics.tabBarClearance)
                .frame(maxWidth: NoopMetrics.detailSheetMinWidth).frame(maxWidth: .infinity)
        }
        .background(StrandPalette.surfaceBase)
        .navigationTitle(String(localized: "Stress Monitor"))
        .onReceive(Timer.publish(every: 60, on: .main, in: .common).autoconnect()) { clock = $0 }
        .task(id: "\(repo.refreshSeq)|\(startTs)") { await load() }
        .sheet(isPresented: $showBreathing) {
            NavigationStack { BreathingView().toolbar { Button("Done") { showBreathing = false } } }
        }
    }

    private var timeline: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.space3) {
            Text("Stress timeline").font(StrandFont.headline)
            Chart {
                ForEach(repo.sleeps.filter { $0.endTs > startTs && $0.effectiveStartTs < endTs }, id: \.startTs) { sleep in
                    RectangleMark(xStart: .value("Start", Date(timeIntervalSince1970: Double(max(startTs, sleep.effectiveStartTs)))), xEnd: .value("End", Date(timeIntervalSince1970: Double(min(endTs, sleep.endTs)))), yStart: .value("Low", 0), yEnd: .value("High", 3))
                        .foregroundStyle(StrandPalette.sleepPrimary.opacity(0.13))
                }
                ForEach(workouts.filter { $0.endTs > startTs && $0.startTs < endTs }, id: \.startTs) { workout in
                    RectangleMark(xStart: .value("Start", Date(timeIntervalSince1970: Double(max(startTs, workout.startTs)))), xEnd: .value("End", Date(timeIntervalSince1970: Double(min(endTs, workout.endTs)))), yStart: .value("Low", 0), yEnd: .value("High", 3))
                        .foregroundStyle(StrandPalette.strainPrimary.opacity(0.14))
                }
                ForEach(trace) { point in
                    LineMark(x: .value("Time", Date(timeIntervalSince1970: Double(point.ts))), y: .value("Stress", point.level), series: .value("Segment", point.segment))
                        .foregroundStyle(LinearGradient(colors: [StressMonitorRamp.tense, StressMonitorRamp.steady, StressMonitorRamp.calm], startPoint: .top, endPoint: .bottom))
                        .lineStyle(StrokeStyle(lineWidth: ChartTokens.lineWidth))
                    if point.ts == current?.startTs {
                        PointMark(x: .value("Time", Date(timeIntervalSince1970: Double(point.ts))), y: .value("Stress", point.level)).foregroundStyle(StressMonitorRamp.color(point.level))
                    }
                }
                if let selectedTs { RuleMark(x: .value("Time", Date(timeIntervalSince1970: Double(selectedTs)))).foregroundStyle(StrandPalette.textSecondary) }
            }
            .chartYScale(domain: 0...3)
            .chartXScale(domain: Date(timeIntervalSince1970: Double(startTs))...Date(timeIntervalSince1970: Double(endTs)))
            .chartYAxis { AxisMarks(values: [0, 1, 2, 3]) }
            .frame(height: NoopMetrics.chartHeight)
            .chartOverlay { proxy in
                GeometryReader { geometry in
                    Rectangle().fill(.clear).contentShape(Rectangle())
                        .gesture(DragGesture(minimumDistance: 0).onChanged { event in
                            let x = event.location.x - geometry[proxy.plotAreaFrame].origin.x
                            guard let timestamp: Date = proxy.value(atX: x) else { return }
                            selectedTs = result.timeline.filter { $0.level != nil }.min { abs(Double($0.startTs) - timestamp.timeIntervalSince1970) < abs(Double($1.startTs) - timestamp.timeIntervalSince1970) }?.startTs
                        })
                }
            }
            HStack(spacing: NoopMetrics.space4) {
                Label("Sleep", systemImage: "moon.fill").foregroundStyle(StrandPalette.sleepPrimary)
                Label("Activity", systemImage: "figure.run").foregroundStyle(StrandPalette.strainPrimary)
                Spacer()
                if selectedTs != nil { Button("Reset") { selectedTs = nil }.frame(minHeight: NoopMetrics.touchTarget) }
            }.font(StrandFont.caption)
        }
    }

    private var trace: [StressMonitorTracePoint] {
        var segment = 0
        var previous: Int?
        var points: [StressMonitorTracePoint] = []
        for point in result.timeline {
            guard let value = point.level else { segment += 1; previous = nil; continue }
            if let previous, point.startTs - previous > DaytimeStress.timelineStepSeconds { segment += 1 }
            points.append(.init(ts: point.startTs, level: value, segment: segment))
            previous = point.startTs
        }
        return points
    }

    @MainActor private func load() async {
        loading = true; result = .empty; latestSampleTs = nil; selectedTs = nil
        let selectedDate = date
        let from = startTs
        let to = min(Int(Date().timeIntervalSince1970), endTs - 1)
        stored = await repo.series(key: "stress", source: "my-whoop")
        let hr = await repo.hrSamples(from: from, to: to, limit: 200_000)
        guard !Task.isCancelled else { return }
        guard hr.count >= DaytimeStress.minHourHRSamples else { loading = false; return }
        let rr = await repo.rrIntervals(from: from, to: to, limit: 200_000)
        let gravity = await repo.gravitySamplesUnion(from: from, to: to, limit: 200_000)
        let mode = await DaytimeStressMode.selected(repo: repo, startOfToday: selectedDate, personalBaseline: PuffinExperiment.stressPersonalBaselineEnabled)
        let noon = Calendar.current.date(bySettingHour: 12, minute: 0, second: 0, of: selectedDate) ?? selectedDate
        let offset = TimeZone.current.secondsFromGMT(for: noon)
        let resolved = await runUnescalated(priority: .userInitiated) { DaytimeStress.analyze(hr: hr, rr: rr, gravity: gravity, tzOffsetSeconds: offset, mode: mode, includeTimeline: true) }
        let events = await repo.workoutRows(days: 400)
        guard !Task.isCancelled else { return }
        firstSampleTs = hr.map(\.ts).min()
        latestSampleTs = hr.map(\.ts).max()
        observedEnd = min(to + 1, (latestSampleTs ?? from) + 1)
        result = resolved; workouts = events; loading = false
    }
}

private struct StressMonitorTracePoint: Identifiable {
    let ts: Int
    let level: Double
    let segment: Int
    var id: Int { ts }
}

private func zoneTitle(_ index: Int) -> String { [String(localized: "Low"), String(localized: "Medium"), String(localized: "High")][index] }

private struct StressMonitorGauge: View {
    let value: Double?
    var body: some View {
        ZStack {
            Canvas { context, size in
                let diameter = min(size.width, size.height)
                let radius = diameter * 0.43
                let center = CGPoint(x: size.width / 2, y: size.height / 2)
                for i in 0..<120 {
                    let start = 145 + Double(i) * 250 / 120
                    var arc = Path(); arc.addArc(center: center, radius: radius, startAngle: .degrees(start), endAngle: .degrees(start + 250 / 120), clockwise: false)
                    context.stroke(arc, with: .color(value == nil ? StrandPalette.hairlineStrong : StressMonitorRamp.color(Double(i) / 119 * 3)), style: StrokeStyle(lineWidth: NoopMetrics.space2, lineCap: .round))
                }
                if let value {
                    let angle = (145 + value / 3 * 250) * Double.pi / 180
                    let p0 = CGPoint(x: center.x + cos(angle) * radius * 0.91, y: center.y + sin(angle) * radius * 0.91)
                    let p1 = CGPoint(x: center.x + cos(angle) * radius * 1.08, y: center.y + sin(angle) * radius * 1.08)
                    var pointer = Path(); pointer.move(to: p0); pointer.addLine(to: p1)
                    context.stroke(pointer, with: .color(StrandPalette.textPrimary), style: StrokeStyle(lineWidth: NoopMetrics.space1, lineCap: .round))
                }
            }
            VStack(spacing: NoopMetrics.space2) {
                Text(value.map { String(format: "%.1f", locale: .current, $0) } ?? "—").font(StrandFont.display())
                Text(value.map { StressBand(score: $0).title } ?? String(localized: "Unavailable"))
                    .font(StrandFont.headline).foregroundStyle(value.map(StressMonitorRamp.color) ?? StrandPalette.textSecondary)
            }
            VStack { Spacer(); HStack { Text("0.0"); Spacer(); Text("3.0") }.font(StrandFont.captionNumber).padding(.horizontal, NoopMetrics.space10) }
        }.frame(height: NoopMetrics.scoreDialDiameter)
            .accessibilityElement(children: .combine)
    }
}

struct StressMonitorPreviewCard: View {
    @EnvironmentObject private var repo: Repository
    @State private var daily: (day: String, value: Double)?
    var body: some View {
        NavigationLink { StressMonitorView() } label: {
            NoopCard {
                HStack {
                    VStack(alignment: .leading, spacing: NoopMetrics.space2) {
                        Text("Stress Monitor").font(StrandFont.headline)
                        Text("Daily estimate").font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                        if let daily { Text(daily.day).font(StrandFont.caption) }
                    }
                    Spacer()
                    Text(daily.map { String(format: "%.1f", locale: .current, $0.value) } ?? "—").font(StrandFont.title1)
                    Image(systemName: "chevron.right")
                }.foregroundStyle(StrandPalette.textPrimary)
            }
        }.buttonStyle(.plain)
        .task(id: repo.refreshSeq) { daily = (await repo.series(key: "stress", source: "my-whoop")).last { $0.value.isFinite && (0...3).contains($0.value) && healthspanDate($0.day) != nil } }
    }
}

private enum StressMonitorRamp {
    static let calm = StrandPalette.stressLow
    static let steady = StrandPalette.stressMedium
    static let tense = StrandPalette.stressHigh
    static func color(_ value: Double) -> Color {
        StrandPalette.sample(stops: [.init(color: calm, location: 0), .init(color: steady, location: 0.5), .init(color: tense, location: 1)], at: min(1, max(0, value / 3)))
    }
}
