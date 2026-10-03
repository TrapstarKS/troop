import SwiftUI
import StrandDesign
import StrandAnalytics
import WhoopStore
import Foundation

// MARK: - Trends

struct TrendsView: View {
    @EnvironmentObject var repo: Repository
    // NOTE: deliberately does NOT observe LiveState — Trends shows historical data only, and
    // observing it forced a full re-render of this subtree on every ~1 Hz live-HR tick.

    // The hosted-card API retains extended history ranges; this screen presents W / M / 6M.
    enum Range: Int, CaseIterable, Identifiable {
        case week = 7, month = 30, quarter = 90, half = 180, year = 365, all = 0
        var id: Int { rawValue }
        var label: String {
            switch self {
            case .week:    return String(localized: "W")
            case .month:   return String(localized: "M")
            case .quarter: return String(localized: "3M")
            case .half:    return String(localized: "6M")
            case .year:    return String(localized: "1Y")
            case .all:     return String(localized: "ALL")
            }
        }
        /// Trailing-day window, or nil for "all history".
        var days: Int? { self == .all ? nil : rawValue }

        /// This range plus every LARGER range, ascending — the auto-expand search
        /// order when the selected window holds zero points.
        var widening: [Range] {
            let order: [Range] = [.week, .month, .quarter, .half, .year, .all]
            guard let i = order.firstIndex(of: self) else { return [.all] }
            return Array(order[i...])
        }
    }

    @State private var range: Range = .week
    @State private var rangeOffset = 0
    @State private var monthOffset = -1
    @State private var selectedMetric: CoreMetric = .recovery
    @State private var anchorDate = Date()

    private enum CoreMetric: String, CaseIterable, Identifiable {
        case recovery, strain, sleepPerformance, hrv, restingHr
        var id: String { rawValue }
        var label: String {
            switch self {
            case .recovery: return String(localized: "Recovery")
            case .strain: return String(localized: "Strain")
            case .sleepPerformance: return String(localized: "Sleep Performance")
            case .hrv: return String(localized: "Heart rate variability")
            case .restingHr: return String(localized: "Resting heart rate")
            }
        }
    }

    private var today: String { Repository.localDayKey(anchorDate) }
    private var selectedWindow: TrendsWindow {
        TrendsWindow.period(days: range.rawValue, offset: rangeOffset, today: today)!
    }
    private var minimumRangeOffset: Int {
        TrendsWindow.minimumOffset(days: range.rawValue, earliest: repo.days.first?.day, today: today)
    }
    private var minimumMonthOffset: Int {
        TrendsWindow.minimumOffset(days: 30, earliest: repo.days.first?.day, today: today)
    }
    private func windowLabel(_ window: TrendsWindow) -> String {
        guard let start = TrendsWindow.parse(window.start), let end = TrendsWindow.parse(window.end) else { return "—" }
        let formatter = DateIntervalFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        return formatter.string(from: start, to: end)
    }

    // #436 — shareable offline trends report (PDF over a date range). The sheet owns its
    // own range picker; this just presents it with the loaded history.
    @State private var showingReport = false
    /// Current appearance, passed into the off-screen recap render so the shared PNG matches the app.
    @Environment(\.colorScheme) private var colorScheme

    /// Rest's per-day series, keyed by "yyyy-MM-dd". Rest is the sleep_performance COMPOSITE (the same
    /// number the Today Rest score + the Sleep Rest-detail plot, #614 follow-up) — NOT raw efficiency,
    /// which read differently under the same "Rest" label and made the Trends Rest graph disagree with
    /// the Today Rest score (#732). sleep_performance is a metricSeries, not a DailyMetric field, so load
    /// it once (mirroring TodayView's restScore source) and key by day for `resolve` below.
    @State private var sleepPerfByDay: [String: Double] = [:]

    // #710 — browse previous weeks in the Week-in-review digest. 0 = the week containing today; each step
    // back is one Mon–Sun week earlier. Clamped so it never runs past the earliest day we hold (see
    // `weekAnchorDay` / `stepWeek`). The Trends RANGE control below is independent of this — it scopes the
    // long-form charts; this only moves the weekly digest at the top.
    @State private var weekOffset = 0

    // Effort display scale (#268) — routes the Effort small-multiple's numbers + unit. Display-only.
    @AppStorage(UnitPrefs.effortScaleKey) private var effortScaleRaw = EffortScale.hundred.rawValue
    // Trend chart style (line vs bar) — display-only; flips every trend card between the gradient line
    // and value-ramp bars. Read here at the screen root so a Settings change re-renders on return.
    @AppStorage(UnitPrefs.trendChartStyleKey) private var trendChartStyleRaw = TrendChartStyle.line.rawValue
    private var effortScale: EffortScale { UnitPrefs.resolveEffortScale(effortScaleRaw) }

    // yyyy-MM-dd → Date (en_US_POSIX, UTC), per task spec.
    private static let dayParser: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()
    private func date(_ day: String) -> Date? { Self.dayParser.date(from: day) }

    private struct ResolvedMetric {
        var points: [TrendPoint]
    }

    private func resolve(_ value: (DailyMetric) -> Double?) -> ResolvedMetric {
        let window = selectedWindow
        let points = repo.days.compactMap { day -> TrendPoint? in
            guard window.contains(day.day), let number = value(day), number.isFinite,
                  let date = date(day.day) else { return nil }
            return TrendPoint(date: date, value: number)
        }
        return ResolvedMetric(points: points)
    }

    /// A padded value range for a series so the line isn't flat against the axis.
    private func valueRange(_ pts: [TrendPoint], fallback: ClosedRange<Double>, pad: Double = 0.12) -> ClosedRange<Double> {
        HostedTrendData.valueRange(pts, fallback: fallback, pad: pad)
    }

    private func mean(_ pts: [TrendPoint]) -> Double? {
        guard !pts.isEmpty else { return nil }
        return pts.map(\.value).reduce(0, +) / Double(pts.count)
    }

    /// The window's trend as a signed mean-of-recent-half minus mean-of-earlier-half. Drives a
    /// TrendChip so the card reads its direction at a glance, like Today's deltas. nil for a window
    /// too short to split. `higherIsBetter == nil` (e.g. Effort) keeps the chip neutral.
    private func periodChange(_ pts: [TrendPoint]) -> Double? {
        guard pts.count >= 4 else { return nil }
        let mid = pts.count / 2
        let earlier = pts.prefix(mid).map(\.value)
        let recent = pts.suffix(pts.count - mid).map(\.value)
        guard !earlier.isEmpty, !recent.isEmpty else { return nil }
        let e = earlier.reduce(0, +) / Double(earlier.count)
        let r = recent.reduce(0, +) / Double(recent.count)
        return r - e
    }

    /// A TrendChip for a window's period change, coloured green/rose by whether the move is good for
    /// THIS metric (`higherIsBetter`); neutral when direction has no valence or the change is flat.
    @ViewBuilder
    private func changeChip(_ pts: [TrendPoint], higherIsBetter: Bool?, fmt: @escaping (Double) -> String) -> some View {
        if let d = periodChange(pts), abs(d) > 0.0001 {
            let sign = d >= 0 ? "+" : "−"
            let deltaText = "\(sign)\(fmt(abs(d)))"
            let color: Color = {
                guard let better = higherIsBetter else { return StrandPalette.textTertiary }
                return (d > 0) == better ? StrandPalette.statusPositive : StrandPalette.metricRose
            }()
            VStack(alignment: .leading, spacing: NoopMetrics.spaceHalf) {
                // Match the neighbouring ChartFooter columns so the delta is self-describing instead
                // of appearing as an unlabeled pill at the edge of the statistics row.
                Text("Trend")
                    .textCase(.uppercase)
                    .font(StrandFont.footnote)
                    .foregroundStyle(StrandPalette.textTertiary)
                TrendChip(text: deltaText, color: color)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(verbatim: "\(String(localized: "Trend")): \(deltaText)"))
        }
    }

    var body: some View {
        // The liquid metric cards now tap through to their MetricDetailView (matching Today's card
        // taps + Explore's rows). On iOS each tab already supplies a NavigationStack, so those pushes
        // land in the ambient stack. On macOS the .trends detail pane has NO enclosing NavigationStack
        // (RootView), so — exactly like MetricExplorerView (#753) — wrap the scaffold in one here so the
        // pushes get Back chrome instead of hanging. The SAME shared scaffold renders on both.
        #if os(macOS)
        // Register the value routes at THIS stack's root; on iOS the tab shell's stack registers
        // them instead (once per stack — a double registration double-pushes, #38).
        NavigationStack { scaffold.tabRouteDestinations() }
        #else
        scaffold
        #endif
    }

    private var scaffold: some View {
        ScreenScaffold(title: "Trends", subtitle: "The thread of you over time.",
                       // PERF (scroll): lazy column — byte-identical layout (LazyVStack == eager VStack
                       // alignment/spacing/header). The content is one inner eager VStack, so the staggered
                       // section reveal is unchanged; this only defers building that stack until it scrolls in.
                       onRefresh: { anchorDate = Date(); await repo.refresh() },
                       lazy: true,
                       topBackground: liquidScaffoldSky()) {
            if repo.days.isEmpty {
                ComingSoon(what: repo.loaded
                    ? "Trends need history to draw. Import your WHOOP export in Data Sources to see weeks, months and years instantly."
                    : "Loading your history…")
            } else {
                // Resolve each metric's window ONCE per body and pass the results
                // down — rangeBar/heroRecovery/smallMultiples all reuse these
                // instead of re-filtering repo.days through caption/widened/
                // windowPoints on every render (hover, animation, 1 Hz HR tick).
                let recovery = resolve { $0.recovery }
                let hrv = resolve { $0.avgHrv }
                let rhr = resolve { $0.restingHr.map(Double.init) }
                let strain = resolve { $0.strain }
                // Rest = the sleep_performance composite — the same number the Today Rest score shows
                // (#732); see sleepPerfByDay. resolve() still does the windowing/widening.
                let rest = resolve { sleepPerfByDay[$0.day] }
                VStack(alignment: .leading, spacing: NoopMetrics.sectionSpacing) {
                    // The main card list ripples in once on appear (Reduce-Motion safe).
                    Group {
                        rangeBar
                        selectedMetricChart(recovery: recovery, hrv: hrv, rhr: rhr, strain: strain, rest: rest)
                        monthlyPerformance
                        weeklyDigestNav
                        // Long-horizon training load (CTL/ATL/TSB). Uses the FULL history, not the
                        // range window — chronic load is inherently a 42-day horizon. Self-hides its
                        // chart behind an honest "needs N more days" state until enough history exists.
                        TrainingLoadCard(days: repo.days)
                            .staggeredAppear(index: 5)
                        yearStrip
                            .staggeredAppear(index: 6)
                        exportReportRow
                            .staggeredAppear(index: 7)
                    }
                }
            }
        }
        // #436 — present the offline trends-report exporter (range picker + PDF export).
        .onAppear { anchorDate = Date() }
        .sheet(isPresented: $showingReport) {
            TrendsReportSheet(days: repo.days)
        }
        // #732 — load the resolved sleep_performance series so Rest plots the SAME composite the Today
        // Rest score uses (not raw efficiency). Mirrors TodayView's restScore read. Keyed on the day
        // values so a newly-banked or revised night refreshes Rest reactively, like the other metrics that
        // read `repo.days` directly (and like the Android LaunchedEffect(days) twin).
        .onChange(of: repo.days) { _ in
            rangeOffset = max(minimumRangeOffset, min(0, rangeOffset))
            monthOffset = max(minimumMonthOffset, min(0, monthOffset))
        }
        .task(id: repo.days) {
            let s = await repo.exploreSeries(key: "sleep_performance", source: "my-whoop")
            sleepPerfByDay = Dictionary(s.map { ($0.day, $0.value) }, uniquingKeysWith: { _, last in last })
        }
    }

    // MARK: Week-in-review digest with prev/next week browsing (#710)

    /// The earliest "yyyy-MM-dd" we hold (history is oldest → newest), used to clamp how far back the
    /// week stepper can go.
    private var earliestDay: String? { repo.days.first?.day }

    /// The most negative `weekOffset` allowed: the number of whole weeks between the earliest day's week
    /// and this week. Beyond that there's no data to digest, so the back chevron disables. 0 when history
    /// is empty or unparseable (so we stay on this week).
    private var minWeekOffset: Int {
        guard
            let earliest = earliestDay,
            let earliestMon = WeeklyDigestEngine.mondayOfWeek(containing: earliest),
            let thisMon = WeeklyDigestEngine.mondayOfWeek(containing: Repository.localDayKey(Date()))
        else { return 0 }
        // Walk weeks back from this Monday until we pass the earliest week. Bounded by history length.
        var off = 0
        var mon = thisMon
        while mon > earliestMon && off > -520 {           // hard cap ~10 years so a bad date can't spin
            mon = WeeklyDigestEngine.addDays(mon, -7)
            off -= 1
        }
        return off
    }

    /// The anchor day (any day in the target week) for the current `weekOffset`: today shifted back by
    /// `weekOffset` whole weeks. The engine snaps it to that week's Monday.
    private var weekAnchorDay: String {
        WeeklyDigestEngine.addDays(Repository.localDayKey(Date()), weekOffset * 7)
    }

    /// Move the digest one week earlier (-1) or later (+1), clamped to [minWeekOffset, 0] — never into a
    /// future week, never past the earliest week we hold.
    private func stepWeek(_ delta: Int) {
        let next = weekOffset + delta
        weekOffset = max(minWeekOffset, min(0, next))
    }

    /// The week-in-review digest for the selected week, with prev/next chevrons in its header. The digest
    /// for `weekAnchorDay` is built straight from the shared `WeeklyDigestSource` (the same builder the
    /// standalone WeeklyDigestCard uses) so past weeks render in the identical format. The whole block
    /// self-hides only when there's no data in ANY week (an all-empty history), matching the old card.
    @ViewBuilder
    private var weeklyDigestNav: some View {
        let digest = WeeklyDigestSource.digest(from: repo.days, anchorDay: weekAnchorDay)
        // Only hide the navigation entirely when the WHOLE history is empty — an empty PAST week still
        // shows the header + chevrons so the user can step to a week that does hold data.
        if repo.days.isEmpty {
            EmptyView()
        } else {
            VStack(alignment: .leading, spacing: NoopMetrics.cardInnerSpacing) {
                weekNavBar(digest: digest)
                if digest.isEmpty {
                    // This particular week had no readings — keep the chevrons above so the user can move on.
                    DataPendingNote(
                        title: "No readings this week",
                        message: "Step to another week with the arrows above to see its review.")
                } else {
                    WeeklyDigestContent(digest: digest, compact: true, showsHeader: false)
                        .padding(.top, NoopMetrics.space1)
                    // Share this week's recap as an image. Renders the digest card (with its header) to a
                    // PNG off-screen and hands it to the share sheet / Save panel — reuses TrendsReport's
                    // ImageRenderer path. Only offered when the week actually holds data.
                    NoopButton("Share recap", systemImage: "square.and.arrow.up", kind: .secondary) {
                        let page = WeeklyDigestContent(digest: digest, compact: true, showsHeader: true)
                            .frame(width: 380)
                            .padding(NoopMetrics.space6)
                            .background(StrandPalette.surfaceBase)
                            .environment(\.colorScheme, colorScheme)
                        TrendsReportRenderer.exportPNG(page: page, suggestedName: "noop-recap-\(weekAnchorDay).png")
                    }
                }
            }
        }
    }

    /// Prev/next week stepper. Back is clamped at the earliest week we hold; forward is clamped at this
    /// week (no future weeks). Mirrors the FullDayChartView day stepper's flat accent chevrons (#597).
    private func weekNavBar(digest: WeeklyDigest) -> some View {
        let atOldest = weekOffset <= minWeekOffset
        let atNewest = weekOffset >= 0
        let daysSummary = String(localized: "\(digest.daysWithData)/7 days")
        let daysAccessibility = String(localized: "\(digest.daysWithData) of 7 days had data")
        return HStack(spacing: NoopMetrics.cardInnerSpacing) {
            Button { stepWeek(-1) } label: {
                Image(systemName: "chevron.left").font(StrandFont.headline.weight(.semibold))
            }
            .buttonStyle(.plain)
            .foregroundStyle(atOldest ? StrandPalette.textTertiary : StrandPalette.accent)
            .disabled(atOldest)
            .accessibilityLabel("Previous week")

            Spacer()
            VStack(spacing: NoopMetrics.spaceHalf) {
                Text(weekOffset == 0 ? String(localized: "This week") : weekOffsetLabel)
                    .font(StrandFont.headline)
                    .foregroundStyle(StrandPalette.textPrimary)
                Text("\(weeklyDigestRangeLabel(digest)) · \(daysSummary)")
                    .font(StrandFont.footnote)
                    .foregroundStyle(StrandPalette.textSecondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
                    .accessibilityLabel("\(weeklyDigestRangeLabel(digest)), \(daysAccessibility)")
            }
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            Spacer()

            Button { stepWeek(1) } label: {
                Image(systemName: "chevron.right").font(StrandFont.headline.weight(.semibold))
            }
            .buttonStyle(.plain)
            .foregroundStyle(atNewest ? StrandPalette.textTertiary : StrandPalette.accent)
            .disabled(atNewest)
            .accessibilityLabel("Next week")
        }
        .padding(.horizontal, NoopMetrics.space1)
        .accessibilityElement(children: .contain)
    }

    /// "Last week" for -1, else the count of weeks back ("3 weeks ago") for the stepper's centre label.
    private var weekOffsetLabel: String {
        let n = -weekOffset
        if n == 1 { return String(localized: "Last week") }
        return String(localized: "\(n) weeks ago")
    }

    // MARK: Export trends report (#436)

    /// A footer entry that opens the shareable-report sheet. Flat WHOOP card with a blue accent
    /// action — the icon, label and "Export" CTA all read in the accent (blue) world, no gold.
    private var exportReportRow: some View {
        NoopCard(tint: StrandPalette.accent) {
            HStack(spacing: NoopMetrics.space3) {
                Image(systemName: "doc.richtext")
                    .font(StrandFont.title2)
                    .foregroundStyle(StrandPalette.accent)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: NoopMetrics.space1) {
                    Text("Export trends report").strandOverline()
                    Text("A shareable one-page PDF of recovery, sleep, HRV, resting HR and strain over a range, saved on your \(Platform.deviceNoun).")
                        .font(StrandFont.footnote)
                        .foregroundStyle(StrandPalette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: NoopMetrics.space2)
                // The card's call-to-action — routed through the unified button system (secondary kind:
                // a quiet raised capsule that reads as the card action, not the one primary on the page).
                NoopButton("Export", systemImage: "square.and.arrow.up", kind: .secondary) {
                    showingReport = true
                }
                .fixedSize()
            }
        }
        .accessibilityElement(children: .contain)
    }

    // MARK: Range control

    private var rangeBar: some View {
        NoopCard {
            VStack(alignment: .leading, spacing: NoopMetrics.cardInnerSpacing) {
                Menu {
                    ForEach(CoreMetric.allCases) { metric in
                        Button(metric.label) { selectedMetric = metric }
                    }
                } label: {
                    HStack {
                        Text(selectedMetric.label).font(StrandFont.headline)
                        Spacer()
                        Image(systemName: "chevron.down").font(StrandFont.footnote)
                    }
                    .foregroundStyle(StrandPalette.textPrimary)
                }
                .accessibilityLabel(Text("Metric"))
                .accessibilityValue(Text(selectedMetric.label))
                SegmentedPillControl([Range.week, .month, .half],
                                     selection: Binding(get: { range }, set: { range = $0; rangeOffset = 0 }),
                                     adaptsToAvailableWidth: true) { $0.label }
                periodNavigation(window: selectedWindow, offset: rangeOffset,
                                 minimum: minimumRangeOffset,
                                 onStep: { rangeOffset = max(minimumRangeOffset, min(0, rangeOffset + $0)) })
            }
        }
    }

    private func periodNavigation(window: TrendsWindow, offset: Int, minimum: Int,
                                  onStep: @escaping (Int) -> Void) -> some View {
        HStack(spacing: NoopMetrics.space2) {
            Button { onStep(-1) } label: { Image(systemName: "chevron.left") }
                .disabled(offset <= minimum)
                .foregroundStyle(offset <= minimum ? StrandPalette.textTertiary : StrandPalette.textPrimary)
                .accessibilityLabel("Previous period")
            Text(windowLabel(window))
                .font(StrandFont.subhead)
                .foregroundStyle(StrandPalette.textSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
            Button { onStep(1) } label: { Image(systemName: "chevron.right") }
                .disabled(offset >= 0)
                .foregroundStyle(offset >= 0 ? StrandPalette.textTertiary : StrandPalette.textPrimary)
                .accessibilityLabel("Next period")
        }
        .buttonStyle(.plain)
        .font(StrandFont.headline)
        .foregroundStyle(StrandPalette.textPrimary)
    }

    @ViewBuilder
    private func selectedMetricChart(recovery: ResolvedMetric, hrv: ResolvedMetric, rhr: ResolvedMetric,
                                     strain: ResolvedMetric, rest: ResolvedMetric) -> some View {
        switch selectedMetric {
        case .recovery:
            metricChart(title: "Recovery", unit: "%", accessibilityTitle: selectedMetric.label,
                        metricKey: "recovery", points: recovery.points,
                        gradient: StrandPalette.recoveryGradient, tip: StrandPalette.chargeBright,
                        tint: nil, higherIsBetter: true, range: 0...100,
                        fmt: { "\(Int($0.rounded()))" })
        case .strain:
            metricChart(title: "Strain", unit: "/ \(UnitFormatter.effortScaleMax(effortScale))",
                        accessibilityTitle: selectedMetric.label, metricKey: "strain", points: strain.points,
                        gradient: gradient(StrandPalette.effortColor), tip: StrandPalette.effortColor,
                        tint: nil, higherIsBetter: nil, range: 0...100,
                        fmt: { UnitFormatter.effortDisplay($0, scale: effortScale) })
        case .sleepPerformance:
            metricChart(title: "Sleep Performance", unit: "%", accessibilityTitle: selectedMetric.label,
                        metricKey: "sleep_performance", points: rest.points,
                        gradient: gradient(StrandPalette.restColor), tip: StrandPalette.restColor,
                        tint: nil, higherIsBetter: true, range: 0...100,
                        fmt: { "\(Int($0.rounded()))" })
        case .hrv:
            metricChart(title: "Heart rate variability", unit: "ms", accessibilityTitle: selectedMetric.label,
                        metricKey: "hrv", points: hrv.points,
                        gradient: gradient(StrandPalette.metricPurple), tip: StrandPalette.metricPurple,
                        tint: nil, higherIsBetter: true, range: valueRange(hrv.points, fallback: 0...100),
                        fmt: { "\(Int($0.rounded()))" })
        case .restingHr:
            metricChart(title: "Resting heart rate", unit: "bpm", accessibilityTitle: selectedMetric.label,
                        metricKey: "rhr", points: rhr.points,
                        gradient: gradient(StrandPalette.metricRose), tip: StrandPalette.metricRose,
                        tint: nil, higherIsBetter: false, range: valueRange(rhr.points, fallback: 40...80),
                        fmt: { "\(Int($0.rounded()))" })
        }
    }

    private var monthlyPerformance: some View {
        let offset = max(minimumMonthOffset, min(0, monthOffset))
        let window = TrendsWindow.period(days: 30, offset: offset, today: today)!
        let report = RangeReportEngine.build(metrics: TrendsReportData.metricMaps(from: repo.days),
                                            start: window.start, end: window.end)
        let units = ReportDisplayUnits(fahrenheit: false, effortFactor: effortScale == .whoop ? 21.0 / 100.0 : 1)
        let page = TrendsReportPage(report: report, range: .days30, series: [:], generatedOn: "", units: units)
        let metrics: [ReportMetric] = [.recovery, .strain, .sleepHours, .hrv, .restingHr]
        return NoopCard {
            VStack(alignment: .leading, spacing: NoopMetrics.cardInnerSpacing) {
                SectionHeader("Monthly Performance", overline: "Local report")
                periodNavigation(window: window, offset: offset, minimum: minimumMonthOffset,
                                 onStep: { monthOffset = max(minimumMonthOffset, min(0, offset + $0)) })
                ForEach(metrics, id: \.rawValue) { metric in
                    Divider().overlay(StrandPalette.hairline)
                    HStack(spacing: NoopMetrics.space3) {
                        VStack(alignment: .leading, spacing: NoopMetrics.space1) {
                            Text(LocalizedStringKey(metric.label)).font(StrandFont.subhead)
                            if let stat = report.stat(metric) {
                                Text("\(stat.n) of \(report.totalDays) days recorded")
                                    .font(StrandFont.footnote)
                                    .foregroundStyle(StrandPalette.textTertiary)
                            } else {
                                Text("No readings in this month")
                                    .font(StrandFont.footnote)
                                    .foregroundStyle(StrandPalette.textTertiary)
                            }
                        }
                        Spacer(minLength: NoopMetrics.space2)
                        Text(report.stat(metric).map { page.valueText($0.mean, metric) } ?? "—")
                            .font(StrandFont.bodyNumber)
                            .foregroundStyle(StrandPalette.textPrimary)
                    }
                }
                Text("Averages use recorded days only. Missing readings stay unavailable.")
                    .font(StrandFont.footnote)
                    .foregroundStyle(StrandPalette.textTertiary)
            }
        }
    }

    @ViewBuilder
    private func metricChart(
        title: LocalizedStringKey, unit: String,
        // Plain-string series name for VoiceOver (the `title` is a LocalizedStringKey and can't be
        // re-read as a String); supplied by callers so the line announces e.g. "HRV trend".
        accessibilityTitle: String,
        // MetricCatalog key this small-multiple taps through to (its full MetricDetailView).
        metricKey: String,
        points pts: [TrendPoint],
        subtitle: String? = nil,
        gradient: Gradient,
        tip: Color,
        tint: Color?,
        higherIsBetter: Bool?,
        range: ClosedRange<Double>,
        fmt: @escaping (Double) -> String
    ) -> some View {
        let avg = mean(pts)
        let card = ChartCard(
            title: title,
            subtitle: subtitle,
            trailing: avg.map(fmt),
            height: NoopMetrics.chartHeight,
            tint: tint,
            chart: {
                if pts.count >= 2 {
                    glowChart(points: pts, gradient: gradient, valueRange: range,
                              tip: tip, valueFormat: { "\(fmt($0)) \(unit)" },
                              accessibilityLabel: String(localized: "\(accessibilityTitle) trend"))
                } else {
                    sparsePlaceholder
                }
            },
            footer: {
                HStack {
                    ChartFooter([
                        // Plain "MEAN" to match the bare MIN/MAX columns; the unit moves into
                        // the value (e.g. "58 ms") so uppercasing can't render a shouty "MEAN MS".
                        ("Mean", avg.map { "\(fmt($0)) \(unit)" } ?? "—"),
                        ("Min", pts.map(\.value).min().map(fmt) ?? "—"),
                        ("Max", pts.map(\.value).max().map(fmt) ?? "—"),
                    ])
                    changeChip(pts, higherIsBetter: higherIsBetter, fmt: fmt)
                }
            }
        )
        // Each small-multiple taps through to its own metric detail (like Today's cards / Explore's rows),
        // with the liquid press settle. The chart itself is left uncluttered — no vessel over it (task).
        NavigationLink(value: TabRoute.metric(metricKey)) { card }
            .buttonStyle(LiquidPressStyle())
            .accessibilityHint(Text(String(localized: "Opens the full \(accessibilityTitle) metric.")))
    }

    // MARK: Year heat-strip

    private var yearStrip: some View {
        // Always show at least a full year for context; expand to all history on ALL.
        let stripDays = max(range.days ?? repo.days.count, 365)
        let recent = repo.days.suffix(stripDays)
        let recoveryDays: [RecoveryDay] = recent.compactMap { d in
            guard let dt = date(d.day) else { return nil }
            return RecoveryDay(date: dt, score: d.recovery)
        }
        let title = (range == .all && repo.days.count > 365) ? String(localized: "Charge (all history)") : String(localized: "Charge (past year)")
        return NoopCard {
            VStack(alignment: .leading, spacing: NoopMetrics.cardInnerSpacing) {
                SectionHeader("\(title)", overline: "Calendar", trailing: String(localized: "\(recoveryDays.filter { $0.score != nil }.count) days"))
                if recoveryDays.isEmpty {
                    sparsePlaceholder.frame(height: 120)
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        YearHeatStrip(days: recoveryDays).padding(.vertical, NoopMetrics.space1 / 2)
                    }
                    Divider().overlay(StrandPalette.hairline)
                    legend
                }
            }
        }
    }

    private var legend: some View {
        HStack(spacing: NoopMetrics.space2) {
            Text("Depleted")
                .font(StrandFont.footnote)
                .foregroundStyle(StrandPalette.textTertiary)
                .fixedSize()
            LinearGradient(gradient: StrandPalette.recoveryGradient, startPoint: .leading, endPoint: .trailing)
                .frame(maxWidth: .infinity)
                .frame(height: NoopMetrics.indicatorTrackHeight)
                .clipShape(Capsule())
                .accessibilityHidden(true)
            Text("Peaked")
                .font(StrandFont.footnote)
                .foregroundStyle(StrandPalette.textTertiary)
                .fixedSize()
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Charge scale, depleted to peaked")
    }

    // MARK: Shared bits

    /// Single-color gradient (for metric lines that aren't a value ramp).
    private func gradient(_ color: Color) -> Gradient {
        Gradient(stops: [
            .init(color: color.opacity(0.55), location: 0.0),
            .init(color: color, location: 1.0),
        ])
    }

    /// A domain-tinted `TrendChart` with a crisp flat line and a bright end-cap dot at the latest
    /// point. WHOOP-flat: no underglow blur layer — the single crisp line carries the data and the
    /// fill contrast does the rest. The "now" end-cap is a small dot pinned to the final sample.
    /// Pure presentation: it forwards every value to the locked `TrendChart` unchanged.
    @ViewBuilder
    private func glowChart(points pts: [TrendPoint], gradient: Gradient, valueRange: ClosedRange<Double>,
                           tip: Color, valueFormat: @escaping (Double) -> String,
                           accessibilityLabel: String) -> some View {
        // One crisp, interactive line + area — flat, no blurred glow copy underneath (WHOOP language).
        // The "now" end-cap is drawn INSIDE this chart (nowCapColor) so it's mapped by the chart's own
        // scales and lands on the line — the previous sibling overlay guessed the plot insets and
        // floated the dot left/below the curve (#458).
        TrendChart(points: pts, gradient: gradient, valueRange: valueRange,
                   showsArea: true,
                   showsBars: TrendChartStyle(rawValue: trendChartStyleRaw) == .bar,
                   height: NoopMetrics.chartHeight, valueFormat: valueFormat,
                   accessibilityLabel: accessibilityLabel, nowCapColor: tip)
    }

    private var sparsePlaceholder: some View {
        Text("Not enough data for this window.")
            .font(StrandFont.subhead)
            .foregroundStyle(StrandPalette.textTertiary)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
            .background(NoopPanelSurface(cornerRadius: 12))
    }
}

#if DEBUG
@MainActor
private func previewRepo() -> Repository {
    let repo = Repository(deviceId: "preview")
    let cal = Calendar(identifier: .gregorian)
    let fmt = DateFormatter()
    fmt.locale = Locale(identifier: "en_US_POSIX")
    fmt.timeZone = TimeZone(identifier: "UTC")
    fmt.dateFormat = "yyyy-MM-dd"
    let today = Date()
    var seeded: [DailyMetric] = []
    let span = 365 * 3
    for i in stride(from: span - 1, through: 0, by: -1) {
        guard let d = cal.date(byAdding: .day, value: -i, to: today) else { continue }
        let phase = Double(span - 1 - i)
        let rec = 55 + 28 * sin(phase / 11.0) + Double((Int(phase) * 31) % 17) - 8
        let hrv = 58 + 16 * sin(phase / 9.0) + Double((Int(phase) * 13) % 11) - 5
        let rhr = 52 + 4 * sin(phase / 7.0) + Double((Int(phase) * 7) % 5) - 2
        let strain = 9 + 6 * sin(phase / 5.0 + 1.2) + Double((Int(phase) * 5) % 4) - 2
        let gap = Int(phase) % 23 == 0
        seeded.append(DailyMetric(
            day: fmt.string(from: d),
            totalSleepMin: 420, efficiency: 0.9, deepMin: 90, remMin: 110, lightMin: 200,
            disturbances: 6, restingHr: gap ? nil : Int(rhr.rounded()),
            avgHrv: gap ? nil : max(15, hrv), recovery: gap ? nil : max(2, min(99, rec)),
            strain: gap ? nil : max(0, min(21, strain)), exerciseCount: 1
        ))
    }
    repo.days = seeded
    repo.loaded = true
    return repo
}

#Preview("Trends") {
    TrendsView()
        .environmentObject(previewRepo())
        .environmentObject(LiveState())
        .frame(width: 960, height: 960)
        .preferredColorScheme(.dark)
}
#endif
