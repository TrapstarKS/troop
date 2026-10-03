//  LiquidTodayView.swift
//  NOOP · Liquid design language — the Today screen, rebuilt in the liquid finish.
//
//  This is the FULL Today, re-created faithfully from the locked mockup
//  (scratchpad/liquid-metal-home.html): sky title + record/add/battery controls,
//  the three scores as liquid vessels with a card-level source badge, the live heart-rate
//  thread, the five "your cards" as liquid chips, a greeting + readiness pills,
//  Synthesis, Recovery Vitals, a Key Metrics grid (incl. steps), Last Workouts
//  and Data Sources. Every value binds to the SAME real data the classic
//  TodayView reads (accessors verified against TodayView.swift), and every tap
//  routes to the same public destination. The sky is a fixed, full-bleed
//  background (edge-to-edge under the status bar, does not scroll).

import SwiftUI
import StrandDesign
import WhoopStore
import StrandAnalytics
import WhoopProtocol

struct LiquidTodayView: View {
    @AppStorage(DayCycleMode.storageKey) private var dayCycleModeRaw = DayCycleMode.sleepOnset.rawValue
    private var dayCycleMode: DayCycleMode { DayCycleMode.persisted(dayCycleModeRaw) }
    @EnvironmentObject var repo: Repository
    @EnvironmentObject var router: NavRouter
    @EnvironmentObject var profile: ProfileStore
    // For the pull-to-sync gesture (#334): a pull kicks a manual strap history offload via ble.syncNow().
    // Observe BLEManager, NOT AppModel — AppModel @Publishes `bpm` on the ~1 Hz HR tick, so observing it
    // would re-render all of Today every second (the exact churn the LiveState leaves isolate). BLEManager
    // only publishes connect/discovery state, never HR. Injected at the app roots beside .environmentObject(model).
    @EnvironmentObject var ble: BLEManager
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Low Power Mode — and the in-app "Reduce motion in NOOP" toggle — pose the sky still too, the
    /// behaviour the comment on the sky branch below has always described. Neither has a SwiftUI
    /// environment key, hence the shared monitor.
    @ObservedObject private var motion = NoopMotionState.shared
    private var poseStill: Bool { motion.poseStill(reduceMotion) }

    /// Shared with the real Today's card-customise editor so the two stay in sync.
    @AppStorage(DashboardCardPrefs.selectionKey) private var dashboardCardsRaw = ""
    /// #today-hosted-cards: the ordered Trends/Sleep cards the user has hosted in Today. Empty by default
    /// (opt-in); rendered by the `.addedCards` section. Shared @AppStorage key with Android.
    @AppStorage(HostedCardPrefs.selectionKey) private var hostedCardsRaw = ""
    /// #989 parity with classic Today + Android: the hydration card is opt-in twice over — the feature
    /// toggle AND an explicit add in CUSTOMISE. Liquid filtered on neither, so a user who added the card
    /// and later switched the feature off kept a permanently-blank row.
    /// The Coach master switch (`noop.coachEnabled`, shared by name with Android). Default ON. Gates the
    /// Today launcher card here; the tab and the daily brief read the same key.
    @AppStorage("noop.coachEnabled") private var coachEnabled = true
    @AppStorage(HydrationStore.enabledKey) private var hydrationEnabled = false
    /// Today's hydration total + goal (ml), resolved in `load()`. nil → the card shows "—".
    @State private var hydrationTotalML: Double?
    @State private var hydrationGoalML: Int?

    // async-loaded via the confirmed Repository accessors
    @State private var restScore: Double?          // sleep_performance, day-keyed
    /// Input providers for the three scores, keyed by recovery / strain / sleep_performance.
    @State private var heroProviderByMetric: [String: ScoreInputProvider] = [:]
    @State private var stress: Double?             // StressModel(...).score, 0–3
    @State private var fitnessAge: Double?         // exploreSeries("fitness_age").last
    @State private var vo2max: Double?             // exploreSeries("vo2max_est").last (#1391)
    @State private var vitality: Double?           // exploreSeries("vitality").last
    // Queue 11a: day-keyed "spo2_candidate" metricSeries (WHOOP `spo2_candidate_82` or Oura
    // ceiling@100 `0x6F`, device-conditional — see `IntelligenceEngine`). Empty when the
    // experimental toggle is OFF (the engine writes nothing) or the owner has no in-band reading.
    // Read unconditionally like the classic TodayView's `spo2CandidateSpark` — always empty when
    // the toggle is off, so no separate gate is needed at fetch time.
    @State private var spo2CandidateByDay: [String: Double] = [:]
    @State private var stepsEst: Double?           // steps_est, day-keyed to the selected day (fallback)
    @State private var importedStepsDay: Int?      // Apple Health steps for the selected day (middle tier)
    @State private var importedActiveKcalDay: Double?  // #616: Apple Health active energy for the day (calorie fallback)
    @State private var weightKg: Double?           // #204: Apple Health weight ?: profile fallback
    @State private var hrValues: [Double] = []     // hrBuckets since midnight → 5-min means
    /// Line identity for [hrValues], from the bucket timestamps this used to discard (#2082).
    ///
    /// A bucket with no samples is simply absent from the aggregate, so mapping straight to `bpm` closed
    /// every hole up and drew a day of sparse live windows as one continuous line. That bites hardest on a
    /// strap whose history never offloads, where heart rate exists ONLY for the windows it was connected.
    @State private var hrSegments: [String] = []
    @State private var workouts: [WorkoutRow] = [] // newest-first
    @State private var homeWorkout: HomeWorkoutTarget?
    /// #today-hosted-cards: the shared SleepModel that backs every SleepModel-derived hosted sleep card
    /// (Stages vs typical today; more to follow). Built ONCE in `load()` from the SAME inputs the Sleep tab
    /// uses (`SleepModel.build`), and only when a sleep-origin card is actually hosted — so a Today with no
    /// hosted sleep card pays none of the extra Repository work. nil until (and unless) it's built.
    @State private var hostedSleepModel: SleepModel? = nil

    // #2040: today's scored stress for the hosted curve card. Loaded only when that card is hosted, the
    // same "hosting none pays nothing" rule the sleep model follows. `StressDayCurve` self-gates on a
    // cheap heart-rate fingerprint and memoises, so the widget, this shell and the other Today view all
    // share one computation rather than scoring the day three times.
    @State private var hostedStressHours: [DaytimeStress.HourPoint] = []
    @State private var hostedStressActivityMaskedHours = 0

    // sheets / expanders
    @State private var guideSection: ScoreSection?
    @State private var customizationDestination: TodayCustomizationDestination?
    /// #1862: the optional Coach launcher sheet. Presentation state only — opening it requests nothing.
    @State private var showCoachLauncher = false
    @State private var routeCoachAfterLauncherDismiss = false
    @State private var showSettings = false
    @State private var synthesisExpanded = false
    @State private var showLiveSession = false

    /// Live Sessions (silent guardian) beta gate — the SAME key the Settings toggle writes. Default ON
    /// (the entry is BETA-labelled in-UI); off removes the Start-session control entirely.
    @AppStorage(LiveSessionPrefs.betaKey) private var liveSessionsBeta = true
    // #today-layout (parity with Android): the user-chosen section order, persisted under the byte-identical
    // "today.sectionOrder" key the Android TodayLayoutPrefs uses. Reordered via the Arrange sheet (native
    // drag-to-reorder rows); every section always renders (decode inserts a missing one at its default spot).
    @AppStorage(TodayLayoutPrefs.orderKey) private var sectionOrderRaw = ""
    @AppStorage(TodayLayoutPrefs.hiddenKey) private var hiddenSectionsRaw = ""
    private var sectionOrder: [TodaySection] {
        TodayLayoutPrefs.visibleOrder(orderRaw: sectionOrderRaw, hiddenRaw: hiddenSectionsRaw)
    }
    // #430 parity: the Key-Metrics grid honours the SAME editor selection/order + Detailed-tiles switch as
    // Android (byte-identical @AppStorage keys). `kSparks` holds the trailing-30-day series the detailed
    // tiles graph (keyed by metric-catalog key), filled by the loader alongside everything else.
    @AppStorage(KeyMetricPrefs.layoutKey) private var keyMetricsRaw = ""
    @AppStorage("today.keyMetricsDetailed") private var keyMetricsDetailed = false
    /// The detailed graphs' trailing window — 1 week / 2 weeks / 1 month (shared key with Android). The
    /// loader banks a day-keyed 30-day superset; render filters down, so a window change applies instantly.
    @AppStorage("today.keyMetricsWindowDays") private var keyMetricsWindowDays = 14
    @State private var kSparks: [String: [(String, Double)]] = [:]
    private var enabledKeyMetrics: [KeyMetric] { KeyMetricPrefs.decodeEnabled(keyMetricsRaw) }

    /// #1001: TODAY's in-progress Effort, scored live in `load()` over the same window this view already
    /// resolves for its other reads. nil for a navigated past day, and nil when the scorer has too few
    /// readings — every Effort read-out then falls back to the stored row rather than a fabricated value.
    @State private var liveTodayStrain: Double?
    @State private var liveEffortRequest = UUID()
    @State private var homeStressByDay: [String: Double] = [:]

    // day navigation (0 = today, 1 = yesterday, …)
    @State private var selectedDayOffset = 0
    @State private var showDayPicker = false
    @State private var heartRateCardFrame: CGRect = .null
    private static let daySwipeSpace = "liquidTodayDaySwipeSpace"

    // PERF: the body was rescanning repo.days (599 days) ~23× per pass for displayDay and ~3× for
    // readiness on EVERY re-render (every HR notify, every canvas frame that invalidates, every scroll).
    // Resolve both ONCE per data/day change in load() and read the cache in body (O(1)).
    @State private var cachedDisplayDay: DailyMetric?
    @State private var cachedReadiness: ReadinessEngine.Readiness?
    /// The recovery-INDEPENDENT prior-day vitals carry (HRV / RHR / respiratory), resolved ONCE in load()
    /// alongside cachedDisplayDay. Fixes the v8 rollover blank: after 04:00, before tonight's sleep scores,
    /// today's row has no vitals yet, so these fall back to the last night that recorded them. Never
    /// resolved in body — body rescans repo.days ~23× per pass, and this cache keeps that read O(1).
    @State private var cachedVitalsDay: DailyMetric?
    @State private var cachedRespDay: DailyMetric?
    @State private var cachedHrvDay: DailyMetric?
    @State private var cachedRestingHrDay: DailyMetric?
    @State private var cachedSkinTempReadingDay: DailyMetric?
    /// The Charge hero's resolved state (#543 carry + the honest label), resolved ONCE in load() alongside
    /// the other caches. It composes `TodayView.lastScoredRecoveryDay`, which is O(days) — exactly the scan
    /// this cache exists to keep out of body. Never resolved in body.
    @State private var cachedChargeDisplay: ChargeDisplay = .noData
    @State private var cachedRecoveryDayKey: String?
    /// Active WHOOP 5 R-R policy bounds for the selected night's missing Charge explanation.
    @State private var whoop5StrictRR = false
    @State private var firstRecordedRRDay: String?
    @State private var firstScorableRRDay: String?
    /// Flips true once the first load() completes. Until then the hero gauges + sky render STATIC so the
    /// launch data-churn (refresh publish + BLE/HR notifies) isn't fighting 4 live canvases + CoreMotion.
    @State private var dataLoaded = false

    // Custom liquid pull-to-refresh: a vessel that FILLS as you drag, releases into a refresh (replaces
    // the system spinner). Driven by the scroll's top overscroll offset.
    @State private var pullY: CGFloat = 0
    @State private var refreshArmed = false
    @State private var refreshing = false
    @State private var pullHaptic = 0
    private let pullThreshold: CGFloat = 80

    /// Measured width of the trailing header-control cluster, feeding the day title's fade mask. Seeded
    /// with the design-system default so the first frame is not laid out against a reserve of zero.
    @State private var headerControlsWidth = NoopMetrics.headerControlReserveWidth

    /// Mock Vitality purple (#9b7bff) has no exact StrandPalette token in this theme.
    private let liquidPurple = Color(.sRGB, red: 0x9b / 255, green: 0x7b / 255, blue: 0xff / 255, opacity: 1)
    /// The liquid heart pink shared with the sync indicator and LiquidThread.
    private let liquidHeart = StrandPalette.liquidHeart
    /// Hero / session-start chrome uses theme-aware `NoopPanelSurface` (design-system surfaces that
    /// flip with Light/Dark). Upstream #1160/#1161 moved the classic RoundedRectangle hero onto
    /// `StrandPalette.heroFill` / `heroBorder` for the same theme-aware goal; #1068 keeps the panel
    /// surface treatment while preserving that Light/Dark readability.
    /// "Card transparency" (0–100, default 100): fades every liquid card surface here — the hero, the
    /// session-start row, the metric tiles and the `card` helper — in lockstep with the frosted cards.
    /// Content sits above the surface so it stays readable. Mirrors Kotlin `NoopPrefs.cardOpacityPercent`.
    @AppStorage(CardAppearancePrefs.opacityKey) private var cardOpacityPercent = CardAppearancePrefs.defaultPercent
    private var cardOpacity: Double { max(0, min(1, Double(cardOpacityPercent) / 100)) }
    /// "Sky behind cards" (default ON): extend the day-cycle sky behind the WHOLE scroll so the
    /// Card-transparency slider reveals it under every card. User-toggleable. Mirrors Kotlin `NoopPrefs.skyBehindCards`.
    @AppStorage(SkyBehindCardsPrefs.enabledKey) private var skyBehindCards = true
    /// Day-cycle scene backdrop (#698). Default ON. When off, the liquid Today drops the sky for the plain
    /// dark canvas — parity with Android and the classic TodayView, which already honour this pref. Mirrors
    /// Kotlin `NoopPrefs.showDayCycleBackground`.
    @AppStorage(SceneBackgroundPrefs.enabledKey) private var showDayCycleBackground = true
    /// Custom background image (#custom-background): when active it overrides the sky in the backdrop below.
    @ObservedObject private var backgroundStore = BackgroundImageStore.shared

    // MARK: - Day navigation (ported from classic Today: swipe + calendar, day-keyed reads)

    /// The logical day the selector resolves to (offset 0 = today's logical day, rolls at 04:00).
    private var selectedLogicalDay: Date {
        let base = Repository.logicalDay(Date())
        return Calendar.current.date(byAdding: .day, value: -selectedDayOffset, to: base) ?? base
    }
    /// The day key the day-scoped read-outs key on. At offset 0 follows repo.today?.day.
    private var selectedDayKey: String {
        if selectedDayOffset == 0, let todayKey = repo.today?.day { return todayKey }
        return Repository.localDayKey(selectedLogicalDay)
    }
    /// The DailyMetric shown for the selected day — read from the cache resolved in load() (was an
    /// O(days) `.last(where:)` scan referenced ~23× per body pass; now O(1)).
    private var displayDay: DailyMetric? { cachedDisplayDay }
    /// The prior-day vitals carry (see `cachedVitalsDay`), read O(1) from the cache. Non-nil only at
    /// offset 0 (today); a navigated past day carries nothing (its own row is the whole story).
    private var vitalsDay: DailyMetric? { cachedVitalsDay }
    /// The prior-day RESPIRATORY carry (#1331): staleness-bounded, so a recent missed night reads the last
    /// real value while a weeks-old one honestly shows "No Data". Non-nil only at offset 0.
    private var respDay: DailyMetric? { cachedRespDay }

    /// PER-FIELD HRV / resting-HR carries (#1842), read O(1) from the cache like `vitalsDay`. `vitalsDay`'s
    /// predicate is an OR across HRV / resting-HR / respiratory, so it resolves the freshest row with ANY of
    /// them — a respiratory-only row blanks HRV and Resting HR on both the vitals card and the Key Metrics
    /// tiles. Twins of `DailyMetric.lastHrvDay` / `lastRestingHrDay`; mirror the Android per-field rows.
    private var hrvDay: DailyMetric? { cachedHrvDay }

    private var restingHrDay: DailyMetric? { cachedRestingHrDay }

    /// The skin-temp reading these cards LEAD with (#1844): today's row if it holds either number, else the
    /// vitals carry, else the freshest prior row with either. Both numbers come off the SAME row, so an
    /// absolute is never paired with another night's deviation. Twin of `TodayView.skinTempLeadReading`.
    private var skinTempLeadReading: SkinTempDisplay.Reading? {
        let row = [displayDay, vitalsDay, cachedSkinTempReadingDay]
            .compactMap { $0 }
            .first { $0.skinTempC != nil || $0.skinTempDevC != nil }
        return SkinTempDisplay.leadReading(absC: row?.skinTempC, devC: row?.skinTempDevC,
                                           prefer: SkinTempDisplay.Kind(rawValue: skinTempDisplayRaw) ?? .absolute)
    }
    /// The Charge hero's resolved state (see `cachedChargeDisplay`), read O(1) from the cache.
    private var chargeDisplay: ChargeDisplay { cachedChargeDisplay }

    /// Match classic Today's existing legacy-night judgement, including its selected-day gate.
    private var chargeLegacyRRGap: Bool {
        guard let day = displayDay, day.recovery == nil else { return false }
        return Whoop5RR.legacyUnscorableNight(
            strictWhoop5: whoop5StrictRR, day: day.day,
            firstRecordedDay: firstRecordedRRDay, firstScorableDay: firstScorableRRDay,
            avgHrv: day.avgHrv, totalSleepMin: day.totalSleepMin)
    }

    /// The actual O(days) resolution. Offset 0 prefers live repo.today; past offsets look up. Run ONCE
    /// per data/day change from load(), never from body.
    private func resolveDisplayDay() -> DailyMetric? {
        if selectedDayOffset == 0 {
            return repo.today ?? repo.days.last(where: { $0.day == selectedDayKey })
        }
        return repo.days.last(where: { $0.day == selectedDayKey })
    }
    /// How far back navigation can go (whole days from the earliest banked day to today).
    private var earliestDayOffset: Int {
        Self.maxDayOffset(earliestDayKey: repo.freshness.earliestDay,
                          todayKey: Repository.logicalDayKey(Date()))
    }
    /// The big header title: Today / Yesterday / weekday for older days.
    private var dayTitle: String {
        switch selectedDayOffset {
        // #1013: these must localize — the header showed English "Today"/"Yesterday"/weekday even when the
        // system UI (tab bar etc.) was another language. "Today"/"Yesterday" go through String(localized:)
        // (matching the classic TodayView.dayNavLabel), and the weekday name is formatted in the user's
        // locale, not the en_US_POSIX one used only for machine day-keys.
        case 0: return String(localized: "Today")
        case 1: return String(localized: "Yesterday")
        default:
            return selectedLogicalDay.formatted(.dateTime.weekday(.wide).locale(AppLanguage.activeLocale))
        }
    }
    /// Two-way binding for the graphical calendar: reads the shown day, writes back an offset.
    private var dayPickerBinding: Binding<Date> {
        Binding(
            get: { selectedLogicalDay },
            set: { newValue in
                selectedDayOffset = Self.pickedDayOffset(pickedDate: newValue,
                                                         anchorLogicalDay: Repository.logicalDay(Date()))
                showDayPicker = false
            }
        )
    }
    /// Horizontal swipe between days (right = older, left = newer — `TodayView.daySwipeDelta`, #2378),
    /// clamped to [today, earliest].
    private var daySwipeGesture: some Gesture {
        DragGesture(minimumDistance: 24, coordinateSpace: .named(Self.daySwipeSpace))
            .onEnded { value in
                guard !heartRateCardFrame.contains(value.startLocation) else { return }
                let dx = value.translation.width, dy = value.translation.height
                guard abs(dx) > abs(dy) * 1.5, abs(dx) > 50 else { return }
                let delta = TodayView.daySwipeDelta(dx: dx)
                let next = Self.clampedDayOffset(current: selectedDayOffset, delta: delta,
                                                 maxOffset: earliestDayOffset)
                guard next != selectedDayOffset else { return }
                withAnimation(StrandMotion.interactive) { selectedDayOffset = next }
            }
    }

    static func clampedDayOffset(current: Int, delta: Int, maxOffset: Int) -> Int {
        min(max(0, maxOffset), max(0, current + delta))
    }
    static func maxDayOffset(earliestDayKey: String?, todayKey: String) -> Int {
        guard let earliestKey = earliestDayKey,
              let earliest = dayKeyParser.date(from: earliestKey),
              let today = dayKeyParser.date(from: todayKey) else { return 0 }
        let gap = Calendar.current.dateComponents([.day],
                                                  from: Calendar.current.startOfDay(for: earliest),
                                                  to: Calendar.current.startOfDay(for: today)).day ?? 0
        return max(0, gap)
    }
    static func pickedDayOffset(pickedDate: Date, anchorLogicalDay: Date) -> Int {
        let cal = Calendar.current
        let days = cal.dateComponents([.day], from: cal.startOfDay(for: pickedDate),
                                      to: cal.startOfDay(for: anchorLogicalDay)).day ?? 0
        return max(0, days)
    }
    private static let dayKeyParser: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    /// Scroll-to-top on an at-root Today re-tap (#198 follow-up); default 0 so macOS/other contexts stay inert.
    @Environment(\.scrollToTopSignal) private var scrollToTopSignal
    private static let topAnchorID = "liquidToday.top"

    var body: some View {
        ScrollViewReader { proxy in
        ScrollView {
            VStack(spacing: 0) {
                // Zero-height scroll-to-top anchor (#198 follow-up): the target for an at-root Today re-tap.
                Color.clear.frame(height: 0).id(Self.topAnchorID)
                // Scroll-offset probe at the very top (before padding), so its minY in the scroll's
                // coordinate space reads the top OVERSCROLL: ~0 at rest, positive as you pull down.
                GeometryReader { g in
                    Color.clear.preference(key: PullOffsetKey.self,
                                           value: g.frame(in: .named(Self.pullSpace)).minY)
                }
                .frame(height: 0)

                liquidRefreshIndicator   // grows in the revealed space; a vessel filling with the pull

                LazyVStack(alignment: .leading, spacing: NoopMetrics.sectionGap) {
                    HomeDateChrome(selectedOffset: $selectedDayOffset, selectedDate: Self.dayKeyParser.date(from: selectedDayKey) ?? selectedLogicalDay,
                        dateLabel: selectedDayOffset < 2 ? dayTitle
                            : selectedLogicalDay.formatted(.dateTime.day().month(.abbreviated).locale(AppLanguage.activeLocale)),
                        maxOffset: earliestDayOffset, streak: homeStreak,
                        onProfile: { showSettings = true })
                    if selectedDayOffset == 0 { HealthAlertBanner() }
                    homeDashboard
                    if selectedDayOffset == 0 { AutoWorkoutCard() }
                    dataSourcesSection
                    Color.clear.frame(height: NoopMetrics.tabBarClearance)
                }
                .padding(.horizontal, NoopMetrics.screenHPadding)
                .padding(.top, NoopMetrics.space3)
            }
            #if os(macOS)
            // Keep the phone-shaped column readable + centred on the wide mac detail pane. The sky is a
            // ScrollView background (full-bleed), so constraining the content column here doesn't touch it.
            .frame(maxWidth: 680)
            .frame(maxWidth: .infinity)
            #endif
        }
        .coordinateSpace(name: Self.pullSpace)
        #if os(iOS)
        // #697 parity: ScreenScaffold already stops a vertical scroll from drifting/bouncing the
        // screen left-right on every other tab. Liquid Today runs its own ScrollView (not
        // ScreenScaffold) and never got the fix, so it was the one screen left with the spurious
        // horizontal rubber-band/swipe. `.basedOnSize` only permits horizontal bounce when content
        // genuinely overflows the width (it does not here, the column is width-capped), so this
        // brings Today's scroll behaviour in line with the rest of the app without touching the
        // vertical pull-to-refresh gesture above.
        .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
        #endif
        .onPreferenceChange(PullOffsetKey.self) { handlePull($0) }
        .background {
            ZStack {
                StrandPalette.surfaceBase
                if backgroundStore.isActive { BackgroundImageBackdrop() }
            }
            .ignoresSafeArea()
        }
        .coordinateSpace(name: Self.daySwipeSpace)
        .onPreferenceChange(LiquidHeartRateCardFrameKey.self) { heartRateCardFrame = $0 }
        // Swipe left/right to change DAYS (WHOOP-style). Tab-swipe is disabled on Today in RootTabView so
        // this owns the horizontal gesture here.
        .simultaneousGesture(daySwipeGesture)
        // A light tick when the day changes (swipe or calendar pick) — the WHOOP-style day nav should
        // feel physical ("every tiny little thing").
        .liquidSelectionHaptic(trigger: selectedDayOffset)
        // A firm tick when the pull passes the release threshold (the custom liquid refresh).
        .liquidMediumHaptic(trigger: pullHaptic)
        // hydrationSeq joins the id so logging a drink re-reads the card immediately, the same trigger set
        // classic TodayView's reloadHydration() uses.
        .onChangeCompat(of: selectedDayOffset) { _ in
            liveEffortRequest = UUID()
            cachedDisplayDay = nil
            cachedChargeDisplay = .noData
            cachedRecoveryDayKey = nil
            liveTodayStrain = nil
            restScore = nil
            workouts = []
        }
        .task(id: "\(repo.refreshSeq)-\(selectedDayOffset)-\(repo.hydrationSeq)-\(hydrationEnabled)-\(dayCycleModeRaw)") {
            DashboardCardPrefs.migrateLegacyStepsAverage()
            await load()
        }
        .sheet(item: $homeWorkout) { target in
            NavigationStack { WorkoutDetailView(row: target.row).environmentObject(repo) }
        }
        .sheet(item: $guideSection) { section in
            NavigationStack { ScoringGuideView(initialSection: section, onClose: { guideSection = nil }) }
        }
        .sheet(item: $customizationDestination) { destination in
            TodayCustomizationSheet(
                initialDestination: destination,
                sectionOrderRaw: $sectionOrderRaw,
                hiddenSectionsRaw: $hiddenSectionsRaw,
                keyMetricsRaw: $keyMetricsRaw,
                keyMetricsDetailed: $keyMetricsDetailed,
                keyMetricsWindowDays: $keyMetricsWindowDays,
                dashboardCardsRaw: $dashboardCardsRaw,
                hostedCardsRaw: $hostedCardsRaw
            )
        }
        .sheet(isPresented: $showCoachLauncher, onDismiss: {
            if routeCoachAfterLauncherDismiss {
                routeCoachAfterLauncherDismiss = false
                router.openCoach()
            }
        }) {
            CoachLauncherSheet { routeCoachAfterLauncherDismiss = true }
        }
        .sheet(isPresented: $showSettings) {
            NavigationStack {
                SettingsView()
                    .background(StrandPalette.surfaceBase.ignoresSafeArea())
                    .liquidSheetDoneChrome { showSettings = false }
            }
        }
        // Live Session (silent guardian, beta): the in-session screen owns the whole display — full
        // screen on iOS (nothing should compete with the ring mid-workout), a sheet on macOS where
        // fullScreenCover doesn't exist.
        .liveSessionCover(isPresented: $showLiveSession)
        #if os(macOS)
        // Hide the mac window toolbar's vibrant material so the full-bleed day-of-sky reads dark + edge-to-edge
        // at the top instead of the white scroll-under-titlebar wash.
        .toolbarBackground(.hidden, for: .windowToolbar)
        #endif
        #if os(iOS)
        // Scroll-to-top on an at-root Today re-tap (#198 follow-up); iOS-only — the tab shell is the only driver.
        .onChange(of: scrollToTopSignal) { _, _ in
            withAnimation(.easeOut(duration: 0.35)) { proxy.scrollTo(Self.topAnchorID, anchor: .top) }
        }
        #endif
        }
    }

    // MARK: - Liquid pull-to-refresh

    static let pullSpace = "liqTodayScroll"

    /// Reserves the revealed space at the top and shows a vessel that fills with the pull, then sloshes
    /// while the refresh runs. A plain computed property (not a LiveState-isolated leaf) — it doesn't read
    /// LiveState itself, so it's cheap to re-evaluate as part of the main body. It hands the actual
    /// visibility decision to `LiquidRefreshIndicator` below, which DOES own LiveState.
    private var liquidRefreshIndicator: some View {
        LiquidRefreshIndicator(pullY: pullY, pullThreshold: pullThreshold, refreshing: refreshing,
                               liquidHeart: liquidHeart)
    }

    /// Arm the refresh once the pull passes the threshold; FIRE it when the finger releases (the pull
    /// springs back toward zero). Guarded so it can't double-fire or re-trigger mid-refresh.
    private func handlePull(_ y: CGFloat) {
        pullY = max(0, y)
        guard !refreshing else { return }
        // #1748 twin: gate the ARM, not the release. `syncNow()`'s own gate checks connected + bonded, and
        // `bonded` is set by the live-HR path for a 5/MG that has never completed a handshake — so the pull
        // was accepted and then declined in silence. `historyReady` is the client's OWN precondition, so
        // this cannot withhold a sync that would have run.
        //
        // On the ARM specifically: gating the RELEASE below would leave `refreshArmed` stuck true for the
        // rest of the gesture, since that branch is the only thing that clears it — a worse failure than
        // the silent one being fixed. Not arming also withholds the haptic, which is the honest signal
        // that the gesture is unavailable rather than unresponsive.
        if pullY >= pullThreshold, !refreshArmed, ble.state.historyReady {
            refreshArmed = true
            pullHaptic &+= 1
        }
        if refreshArmed, pullY < 6 {
            refreshArmed = false
            refreshing = true
            Task {
                // #334 (iOS twin of Android #426): a pull requests a fresh strap history offload, not just
                // a UI reload. syncNow() is internally gated (connected + bonded + not-already-backfilling),
                // so a pull while disconnected or mid-offload safely no-ops. The sync status chip owns the
                // ongoing offload progress; the pull spinner stays short (the reload below).
                ble.syncNow()
                await repo.refresh()
                await load()
                try? await Task.sleep(nanoseconds: 350_000_000)   // let the fill read as "done"
                withAnimation(.easeOut(duration: 0.25)) { refreshing = false }
            }
        }
    }

    // MARK: - Scene (sky title + controls + hero)

    private var scene: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack(alignment: .topTrailing) {
                Button { showDayPicker = true } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(dayTitle)
                            .font(StrandFont.rounded(28))
                            .foregroundStyle(StrandPalette.textPrimary)
                            .shadow(color: .black.opacity(0.4), radius: 10, y: 1)
                        Text(dateLine)
                            .font(StrandFont.caption)
                            .foregroundStyle(StrandPalette.textSecondary)
                            .shadow(color: .black.opacity(0.35), radius: 8, y: 1)
                    }
                    .contentShape(Rectangle())
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(dayTitle). Tap to pick a day, swipe to change day.")
                .popover(isPresented: $showDayPicker) {
                    DatePicker("", selection: dayPickerBinding, in: ...Repository.logicalDay(Date()),
                               displayedComponents: [.date])
                        .datePickerStyle(.graphical)
                        .labelsHidden()
                        .padding(12)
                        .frame(minWidth: 320, minHeight: 360)
                        .liquidPopoverAdaptation()
                }
                // Long names fade beneath the trailing controls while an expanded transient control
                // participates in layout and pushes its preceding siblings left. The reserve is the
                // cluster's MEASURED width, not a constant: a constant is only ever right for the exact
                // set of controls it was written against, and this row has already gained one (Customize,
                // #1207) since. Measuring also means the fade tracks the sync capsule as it expands,
                // which is the push-left behaviour rather than a separate approximation of it.
                .headerTrailingControlFadeMask(reserving: headerControlsWidth)
                HStack(spacing: headerClusterSpacing) {
                    // Profile pic (the one set in Settings) → opens Settings, matching the classic Today.
                    Button { showSettings = true } label: {
                        Color.clear.frame(
                            width: NoopMetrics.compactControlSize,
                            height: NoopMetrics.compactControlSize
                        )
                    }
                    .nativeLiquidGlassHeaderButton()
                    .overlay {
                        GeometryReader { proxy in
                            let diameter = min(proxy.size.width, proxy.size.height)
                            ProfileAvatarView(imageData: profile.avatarImageData, size: diameter)
                                .frame(width: diameter, height: diameter)
                                .position(x: proxy.size.width / 2, y: proxy.size.height / 2)
                        }
                        .allowsHitTesting(false)
                    }
                    .nativeLiquidGlassPhotoFinish()
                    .accessibilityLabel("Profile and settings")
                    LiquidAddButton()
                    LiquidBatteryButton()
                    // One entry point for section order/visibility and both nested card editors.
                    Button { customizationDestination = .today } label: {
                        Image(systemName: "slider.horizontal.3")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(StrandPalette.textPrimary)
                            .frame(
                                width: NoopMetrics.compactControlSize,
                                height: NoopMetrics.compactControlSize
                            )
                    }
                    .nativeLiquidGlassHeaderButton()
                    .accessibilityLabel("Customize Today")
                }
                .background(
                    GeometryReader { proxy in
                        Color.clear.preference(
                            key: HeaderControlsWidthKey.self,
                            value: proxy.size.width
                        )
                    }
                )
                .zIndex(1)
            }
            .onPreferenceChange(HeaderControlsWidthKey.self) { measured in
                // Ignore sub-point churn so a rounding wobble cannot re-render the mask every frame.
                guard measured > 0, abs(measured - headerControlsWidth) > 0.5 else { return }
                headerControlsWidth = measured
            }
            // Subtle NOOP wordmark in the sky between header and hero. Perfectly centred (a letter row has
            // no trailing tracking gap the way `Text(...).tracking()` does), with a tap easter egg.
            // #today-layout: the hero + Start-session row moved OUT of the scene into the reorderable
            // section block below. The wordmark's bottom pad (10) + the section VStack's 12 spacing keeps
            // the default hero-under-wordmark gap at the original 22.
            LiquidWordmark()
                .padding(.top, 30)
                .padding(.bottom, 10)
        }
    }

    /// One-tap Live Session start (silent guardian, beta) — sits directly under the hero scores, the
    /// Charge its band is gated on. Same translucent chrome as the hero card so it reads as part of the
    /// sky scene, quiet by design.
    private var liveSessionStartRow: some View {
        Button { showLiveSession = true } label: {
            HStack(spacing: 10) {
                Image(systemName: "shield.lefthalf.filled")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(StrandPalette.metricCyan)
                // Theme-aware session-start chrome (#1160 parity): NoopPanelSurface + normal text
                // tokens — light ink on Dark, dark ink on Light. (Was pinned-dark + on-dark tokens.)
                Text("Start session")
                    .font(StrandFont.subhead)
                    .foregroundStyle(StrandPalette.textPrimary)
                Text("BETA")
                    .font(StrandFont.overlineScaled(8.5)).tracking(1.2)
                    .foregroundStyle(StrandPalette.textSecondary)
                    .padding(.horizontal, 8).padding(.vertical, 2.5)
                    .background(Capsule().fill(StrandPalette.surfaceInset.opacity(0.72))
                        .overlay(Capsule().strokeBorder(
                            StrandPalette.hairline,
                            lineWidth: NoopMetrics.hairlineWidth
                        )))
                Spacer(minLength: 8)
                Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(StrandPalette.textTertiary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            .background(NoopPanelSurface(cornerRadius: 18, surfaceOpacity: cardOpacity))
        }
        .buttonStyle(LiquidPressStyle())
        .accessibilityLabel("Start a live session. Beta. Silent strap coaching against today's Charge.")
    }

    private var heroCard: some View {
        HStack(alignment: .top, spacing: 4) {
            // #543 carry: an unscored today shows the last scored night's REAL Charge (labelled as prior by
            // the state pill) rather than an empty vessel, matching the classic Today, the widget/watch/Live
            // Activity (`Repository.widgetAnchor`) and Android. Effort deliberately does NOT carry — it is
            // today's own accumulation, so yesterday's number would be a false statement, not a stale one.
            HeroScoreCell(label: String(localized: "Charge"), score: chargeDisplay.pct,
                          tint: chargeDisplay.pct.map { StrandPalette.recoveryColor($0) } ?? StrandPalette.chargeColor,
                          animated: dataLoaded, onGuide: { guideSection = .charge },
                          detailRoute: .metric(HeroRingMetric.charge))
            // #45: the hero Effort must honour the user's Effort scale like every other Effort read-out.
            // Show the value on the chosen scale (0–100 or WHOOP 0–21) with the matching vessel max, and
            // one decimal on the compressed 0–21 axis to match the app-wide `effortDisplay` convention
            // (12.6, not a rounded "13"); the 0–100 hero stays a whole number as before.
            HeroScoreCell(label: String(localized: "Effort"),
                          score: effortStrain(displayDay).map { UnitFormatter.effortValue($0, scale: effortScale) },
                          tint: StrandPalette.effortColor, animated: dataLoaded,
                          onGuide: { guideSection = .effort },
                          maxValue: effortScale == .whoop ? 21 : 100,
                          decimals: effortScale == .whoop ? 1 : 0,
                          detailRoute: .metric(HeroRingMetric.effort))
            HeroScoreCell(label: String(localized: "Rest"), score: restScore, tint: StrandPalette.restColor,
                          animated: dataLoaded, onGuide: { guideSection = .rest },
                          detailRoute: .metric(HeroRingMetric.rest))
                .overlay(alignment: .top) {
                    if let sourceLabel = heroSourceLabel {
                        SourceBadge("\(sourceLabel)", tint: StrandPalette.textSecondary)
                            // Match the badge's trailing edge to the Rest vessel and centre it on the card border.
                            .fixedSize()
                            .frame(width: HeroScoreCell.vesselDiameter, alignment: .trailing)
                            .offset(y: -(NoopMetrics.space4 + NoopMetrics.sourceBadgeHeight / 2))
                            .allowsHitTesting(false)
                            .accessibilityLabel(Text("Source: \(sourceLabel)"))
                    }
                }
        }
        .padding(.vertical, NoopMetrics.space4)
        .padding(.horizontal, NoopMetrics.space3)
        .background(NoopPanelSurface(cornerRadius: 26, elevated: true, surfaceOpacity: cardOpacity))
    }

    // MARK: - Heart rate

    private var heartRateSection: some View {
        VStack(spacing: 8) {
            sectionHead("HEART RATE", trailing: "Live")
            // #979: the whole-day HR trend (Deep Timeline) still exists but was buried behind Metrics →
            // Show all → Deep Timeline. The whole live HR card remains a one-tap route into it.
            NavigationLink(value: TabRoute.fullDayChart) {
                card {
                    // Isolated leaf: it observes LiveState so the ~1 Hz HR notifies re-render ONLY
                    // this card, never the whole Today. Shows the current bpm live with a rolling
                    // beat-by-beat trace; falls back to today's banked 5-minute trace when idle.
                    LiquidLiveHR(tint: liquidHeart, fallback: hrValues, fallbackSegments: hrSegments,
                                 animated: dataLoaded)
                }
            }
            .buttonStyle(LiquidPressStyle())
            .accessibilityHint("Opens the full-day heart rate timeline")
            .background {
                GeometryReader { geometry in
                    Color.clear.preference(key: LiquidHeartRateCardFrameKey.self,
                                           value: geometry.frame(in: .named(Self.daySwipeSpace)))
                }
            }
        }
    }

    // MARK: - Your cards

    private var yourCardsSection: some View {
        VStack(spacing: 8) {
            HStack {
                Text("YOUR CARDS").font(StrandFont.overline).tracking(1.6)
                    .foregroundStyle(StrandPalette.textTertiary)
                Spacer()
                Button { customizationDestination = .yourCards } label: {
                    // #492 item 4 parity: unify the Your Cards / Key Metrics edit affordance to "EDIT" across
                    // platforms (Android #563). Reuse the localized "Edit" key, uppercased at display, so this
                    // stays translated (BEARBEITEN / MODIFIER / …) without a new literal.
                    Text(String(localized: "Edit").uppercased()).font(StrandFont.overlineScaled(11)).tracking(1.0)
                        .foregroundStyle(StrandPalette.accent)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 2)
            .padding(.top, 4)

            // Data-driven off the SAME @AppStorage the CUSTOMISE editor writes, so add / remove /
            // reorder in Customise reflects on the home screen live. The hydration filter mirrors classic
            // TodayView's `enabledDashboardCards` and Android's `it != HYDRATION || hydrationEnabled`.
            ForEach(DashboardCardPrefs.decodeEnabled(dashboardCardsRaw)
                        .filter { hydrationEnabled || $0 != .hydration }
                        // Coach off means the AI is off, so the launcher card goes with the tab: leaving it
                        // on Today would offer a feature the wearer has just switched off. Same gate shape
                        // as hydration, so a card they had added keeps its place and returns on re-enable.
                        .filter { coachEnabled || $0 != .coach }) { card in
                liquidCard(for: card)
            }
        }
    }

    // MARK: - Added cards (#today-hosted-cards)

    /// The Trends/Sleep cards the user hosted in Today, in their arranged order. Data-driven off the SAME
    /// @AppStorage the Customise editor writes, so add / remove / reorder reflects live. Each hosted card
    /// is the SAME view its home tab renders (a mirror, not a copy) and carries its own header, so this
    /// section adds no header of its own. Renders nothing until the user hosts a card.
    @ViewBuilder
    private var hostedCardsSection: some View {
        let cards = HostedCardPrefs.decodeEnabled(hostedCardsRaw)
        if !cards.isEmpty {
            VStack(spacing: NoopMetrics.sectionGap) {
                ForEach(cards) { card in
                    if let route = card.route {
                        NavigationLink(value: route) { hostedCard(for: card) }
                            .buttonStyle(.plain)
                    } else {
                        hostedCard(for: card)
                    }
                }
            }
        }
    }

    /// Dispatch a hosted card id to its native view. Each case renders the exact view the originating tab
    /// uses, so the Today copy and the home-tab copy never diverge. P0 hosts only Sleep marks.
    @ViewBuilder
    private func hostedCard(for card: HostedCard) -> some View {
        switch card {
        case .sleepMarks: SleepMarkCard()
        case .trendHRV, .trendRestingHR, .trendEffort:
            // The Trends charts, drawn by the tab's own ChartCard + TrendChart from the SAME resolved
            // points. `HostedTrendData` walks the `days` already in hand, so unlike the sleep model and
            // the stress curve there is no read behind these and nothing to gate.
            HostedTrendCard(card: card, days: repo.days, effortScale: effortScale)
        case .stressToday:
            // READ-ONLY, like `stages`: the Stress tab keeps the interactive timeline and this mirrors
            // only the display. `DaytimeLoadLine` is the tab's OWN line, so the host cannot drift into
            // a second drawing of the same day.
            NoopCard(tint: StressRamp.calm) {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Stress through the day").strandOverline()
                    if hostedStressHours.contains(where: { $0.level != nil }) {
                        DaytimeLoadLine(hours: hostedStressHours)
                    } else {
                        // The honest blank: only waking hours score and an hour needs enough heart
                        // rate, so early morning is empty by construction rather than by failure.
                        Text("Calibrating")
                            .font(StrandFont.subhead)
                            .foregroundStyle(StrandPalette.textTertiary)
                            .frame(maxWidth: .infinity, minHeight: 60, alignment: .center)
                    }
                    if let maskedCaption = stressActivityMaskedHoursCaption(hostedStressActivityMaskedHours) {
                        Text(maskedCaption)
                            .font(StrandFont.footnote)
                            .foregroundStyle(StrandPalette.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        case .asleepDuration: AsleepDurationCard(data: AsleepDurationData.build(days: repo.days))
        case .stagesVsTypical:
            // Renders from the shared SleepModel built in load() (same inputs as the Sleep tab). Until that
            // async build lands — or on a device with no usable latest night — show the graceful placeholder
            // rather than a half-built card, mirroring how AsleepDuration degrades on no data.
            if let m = hostedSleepModel {
                StagesVsTypicalCard(model: m)
            } else {
                hostedSleepPlaceholder
            }
        case .nightDetail:
            // Renders from the same shared SleepModel built in load(). Until that async build lands — or on a
            // device with no usable latest night — show the graceful placeholder, mirroring stagesVsTypical.
            if let m = hostedSleepModel {
                NightDetailCard(model: m)
            } else {
                hostedNightDetailPlaceholder
            }
        case .sleepDebt:
            // Renders from the same shared SleepModel built in load(). Until that async build lands — or on a
            // device with no usable latest night — show the graceful placeholder, mirroring stagesVsTypical.
            if let m = hostedSleepModel {
                SleepDebtLedgerCard(model: m)
            } else {
                hostedSleepDebtPlaceholder
            }
        case .stages:
            // The READ-ONLY latest-night stage card — same shared SleepModel (same night + intervals as the
            // Sleep tab), rendered without the Sleep tab's nav/edit/nap interaction. Until the async build
            // lands — or on a device with no usable latest night — show the placeholder, as above.
            if let m = hostedSleepModel {
                StagesCard(model: m)
            } else {
                hostedSleepPlaceholder
            }
        case .hoursVsNeeded:
            // The single hours-vs-need % metric, rendered from the same shared SleepModel built in load().
            // Until that async build lands — or on a device with no usable latest night — show the graceful
            // placeholder, mirroring stagesVsTypical.
            if let m = hostedSleepModel {
                HoursVsNeededCard(model: m)
            } else {
                hostedHoursVsNeededPlaceholder
            }
        case .consistency:
            // The single sleep-consistency % metric, rendered from the same shared SleepModel built in
            // load(). Until that async build lands — or on a device with no usable latest night — show the
            // graceful placeholder, mirroring stagesVsTypical.
            if let m = hostedSleepModel {
                ConsistencyCard(model: m)
            } else {
                hostedConsistencyPlaceholder
            }
        }
    }

    /// Graceful empty state for a SleepModel-backed hosted card whose model hasn't built yet (first frame)
    /// or is nil (no usable latest night). Keeps the hosted slot present + labelled so add/remove/reorder in
    /// Customise still reads, without rendering a partial card. #today-hosted-cards.
    private var hostedSleepPlaceholder: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.gap) {
            SectionHeader("Stages vs typical", overline: "Last night")
            Text("Not enough nights yet.")
                .font(StrandFont.subhead)
                .foregroundStyle(StrandPalette.textTertiary)
                .frame(maxWidth: .infinity, minHeight: 60, alignment: .center)
                .background(NoopPanelSurface(tint: StrandPalette.restColor, cornerRadius: 12))
        }
    }

    /// Graceful empty state for the hosted "Night detail" grid before its shared SleepModel builds (first
    /// frame) or when there is no usable latest night. Same treatment as `hostedSleepPlaceholder`, labelled
    /// for this card so add/remove/reorder in Customise still reads. #today-hosted-cards.
    private var hostedNightDetailPlaceholder: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.gap) {
            SectionHeader("Night detail", overline: "Metrics")
            Text("Not enough nights yet.")
                .font(StrandFont.subhead)
                .foregroundStyle(StrandPalette.textTertiary)
                .frame(maxWidth: .infinity, minHeight: 60, alignment: .center)
                .background(NoopPanelSurface(tint: StrandPalette.restColor, cornerRadius: 12))
        }
    }

    /// Graceful empty state for the hosted "Sleep-debt ledger" before its shared SleepModel builds (first
    /// frame) or when there is no usable latest night. Same treatment as `hostedSleepPlaceholder`, labelled
    /// for this card so add/remove/reorder in Customise still reads. #today-hosted-cards.
    private var hostedSleepDebtPlaceholder: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.gap) {
            SectionHeader("Sleep-debt ledger", overline: "Last 14 nights")
            Text("Not enough nights yet.")
                .font(StrandFont.subhead)
                .foregroundStyle(StrandPalette.textTertiary)
                .frame(maxWidth: .infinity, minHeight: 60, alignment: .center)
                .background(NoopPanelSurface(tint: StrandPalette.restColor, cornerRadius: 12))
        }
    }

    /// Graceful empty state for the hosted "Hours vs Needed" card before its shared SleepModel builds (first
    /// frame) or when there is no usable latest night. Same treatment as `hostedSleepPlaceholder`, labelled
    /// for this card so add/remove/reorder in Customise still reads. #today-hosted-cards.
    private var hostedHoursVsNeededPlaceholder: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.gap) {
            SectionHeader("Hours vs Needed", overline: "Sleep")
            Text("Not enough nights yet.")
                .font(StrandFont.subhead)
                .foregroundStyle(StrandPalette.textTertiary)
                .frame(maxWidth: .infinity, minHeight: 60, alignment: .center)
                .background(NoopPanelSurface(tint: StrandPalette.restColor, cornerRadius: 12))
        }
    }

    /// Graceful empty state for the hosted "Consistency" card before its shared SleepModel builds (first
    /// frame) or when there is no usable latest night. Same treatment as `hostedSleepPlaceholder`, labelled
    /// for this card so add/remove/reorder in Customise still reads. #today-hosted-cards.
    private var hostedConsistencyPlaceholder: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.gap) {
            SectionHeader("Consistency", overline: "Sleep")
            Text("Not enough nights yet.")
                .font(StrandFont.subhead)
                .foregroundStyle(StrandPalette.textTertiary)
                .frame(maxWidth: .infinity, minHeight: 60, alignment: .center)
                .background(NoopPanelSurface(tint: StrandPalette.restColor, cornerRadius: 12))
        }
    }

    /// One "Your cards" row for a given card type — honours the user's CUSTOMISE selection + order.
    /// Wired cards show real values; the rest render "–" for now (they still appear, so add/remove/
    /// reorder is reflected). stress → Stress screen, sleep → Sleep, everything else → Health.
    @ViewBuilder
    private func liquidCard(for card: DashboardCard) -> some View {
        switch card {
        case .stepsAverage30:
            RollingStepsAverageCard(day: selectedDayKey)
        case .stress:
            cardLink(.stress, title: card.title, sub: card.subtitle,
                     value: stressText, tint: StrandPalette.accent, frac: fracOver(stress, 3))
        case .fitnessAge:
            cardLink(.metric("fitness_age"), title: card.title, sub: card.subtitle,
                     // Bound symbol as on the Health hero (#2173), so a floored reading does not read
                     // exact here and bounded there.
                     value: fitnessAge.map { "\(fitnessAgeBoundSymbol($0))" + unitText($0, card.unit) }
                         ?? unitText(fitnessAge, card.unit),
                     tint: StrandPalette.chargeColor, frac: 0.5)
        case .vo2max:
            cardLink(.metric("vo2max_est"), title: card.title, sub: card.subtitle,
                     value: unitText(vo2max, card.unit), tint: StrandPalette.chargeColor, frac: 0.5)
        case .vitality:
            cardLink(.metric("vitality"), title: card.title, sub: card.subtitle,
                     value: intText(vitality), tint: liquidPurple, frac: frac(vitality))
        case .hrv:
            cardLink(.metric("hrv"), title: card.title, sub: card.subtitle,
                     value: unitText(displayDay?.avgHrv, card.unit), tint: StrandPalette.metricCyan,
                     frac: fracOver(displayDay?.avgHrv, 120))
        case .restingHr:
            cardLink(.metric("rhr"), title: card.title, sub: card.subtitle,
                     value: unitText(displayDay?.restingHr.map(Double.init), card.unit),
                     tint: StrandPalette.metricRose, frac: fracOver(displayDay?.restingHr.map(Double.init), 100))
        case .respiratory:
            cardLink(.metric("resp_rate"), title: card.title, sub: card.subtitle,
                     value: unitText(displayDay?.respRateBpm, card.unit, decimals: 1),
                     tint: StrandPalette.accent, frac: fracOver(displayDay?.respRateBpm, 24))
        case .steps:
            // Route by the EXACT (key, source) the tile chose to display — measured my-whoop, imported
            // apple-health, or the my-whoop estimate — NOT by bare key (bare "steps" resolves to
            // apple-health and would mismatch a WHOOP-measured value). Order-independent.
            cardLink(.metricSourced(key: stepsDetailKey, source: stepsDetailSource), title: card.title, sub: card.subtitle,
                     value: stepsText, tint: StrandPalette.metricCyan, frac: fracOver(stepCount, 10000))
        case .bloodOxygen:
            // #1627: these two were the last cards still on the "not wired yet" placeholder, so on iOS 26 —
            // where Liquid Today is the DEFAULT Today screen — Blood Oxygen and Skin Temp read "–" for
            // everyone while every other card on the same screen showed a real number off the same
            // `displayDay`. Reported with a clean A/B: turning Liquid Today off restored both immediately.
            //
            // The VALUE resolution is copied from the Key Metrics tile below rather than reinvented —
            // candidate fallback and experimental gating included — so the card and the tile cannot
            // disagree about the same day's number. The tile's key/route handling is deliberately NOT
            // copied; see below for why the two are not interchangeable.
            let spo2Real = displayDay?.spo2Pct ?? vitalsDay?.spo2Pct
            let spo2CandidateOn = PuffinExperiment.spo2CandidateDisplayEnabled
            let spo2Candidate = spo2Real == nil && spo2CandidateOn
                ? spo2CandidateByDay[cachedDisplayDay?.day ?? selectedDayKey]
                : nil
            let spo2 = spo2Real ?? spo2Candidate
            // ALWAYS routes to "spo2", never "spo2_candidate". The Key Metrics tile switches that string,
            // but there it is a SPARKLINE SERIES key (ktile feeds it to windowedSpark; navigation goes
            // through its separate detailMetric argument). Here the string is a NAVIGATION route resolved
            // against MetricCatalog — which has no "spo2_candidate" entry — so switching it would drop the
            // tap into the Health catch-all instead of the Blood Oxygen detail. Same literal, two different
            // key spaces.
            // The candidate MUST carry its label. Every other surface that shows it says "strap estimate
            // (unverified)" — the Key Metrics tile below, VitalSignsSummary, the classic TodayView — and
            // PuffinExperiment's own doc says the toggle surfaces it "in the Blood Oxygen tile/card,
            // labelled". An unlabelled number here would read as a measured SpO2 on the one surface that
            // is the DEFAULT Today screen on iOS 26. The subtitle is the slot this card has.
            cardLink(.metric("spo2"),
                     title: card.title,
                     sub: spo2Candidate != nil ? String(localized: "strap estimate (unverified)") : card.subtitle,
                     // Em dash, not the en dash the stub used: the classic Blood Oxygen card and
                     // skinTempCardValue both return "—", so the stub's "–" would have left the two
                     // adjacent cards printing different glyphs for the same "no reading" state.
                     value: spo2.map { String(format: "%.0f%%", locale: AppLanguage.activeLocale, $0) } ?? "—",
                     tint: StrandPalette.metricCyan, frac: fracOver(spo2, 100))
        case .skinTemp:
            // Skin temp has NO Key Metrics tile to mirror (KeyMetric has no skinTemp case), so this uses
            // the classic card's extracted resolver instead — the same one TodayView calls, which is why
            // it is a static: the formatting decision is testable without a live view.
            //
            // frac stays nil deliberately. A signed deviation has no natural 0–100 fill, and a ring drawn
            // from one would imply a magnitude the number does not carry.
            // #1844: lead with the night's measured ABSOLUTE when it has one; deviation nights unchanged.
            let skin = skinTempLeadReading
            cardLink(.metric("skin_temp"), title: card.title, sub: card.subtitle,
                     value: TodayView.skinTempCardValue(reading: skin, fahrenheit: temperatureUnit == .fahrenheit),
                     tint: StrandPalette.metricAmber, frac: nil)
        case .calories:
            // #616: show the resolved imported-first value and route to the matching detail source, like
            // the Steps card — was a "–" placeholder wired to the imported-only detail.
            cardLink(.metricSourced(key: caloriesDetailKey, source: caloriesDetailSource), title: card.title, sub: card.subtitle,
                     value: intText(caloriesCount), tint: StrandPalette.metricAmber, frac: fracOver(caloriesCount, 800))
        case .sleep:
            cardLink(.sleep, title: card.title, sub: card.subtitle,
                     value: sleepText, tint: StrandPalette.restColor, frac: fracOver(displayDay?.totalSleepMin, 480))
        case .hydration:
            // #989: was hardcoded "–". `HydrationGoal.cardValueString` is unit-tested and byte-identical to
            // the Android twin, but classic TodayView was its only caller — so on the DEFAULT screen a
            // logged drink never appeared. Same "<total> / <goal> L" string and the same goal fraction on
            // the ring as classic; "—" only when the goal is genuinely underivable.
            cardLink(.hydration, title: card.title, sub: card.subtitle,
                     value: hydrationGoalML.map {
                         HydrationGoal.cardValueString(totalML: hydrationTotalML ?? 0, goalML: $0)
                     } ?? "—",
                     tint: StrandPalette.metricCyan,
                     frac: hydrationGoalML.map {
                         HydrationGoal.fraction(totalML: hydrationTotalML ?? 0, goalML: $0)
                     })
        case .coupled:
            // A tap-through to the full Coupled day screen. No value.
            cardLink(.coupled, title: card.title, sub: card.subtitle,
                     value: "", tint: StrandPalette.chargeColor, frac: 0.6)
        case .coach:
            // #1862: a sheet rather than a push — the point of the card is to try Coach WITHOUT
            // leaving Today.
            cardAction(title: card.title, sub: card.subtitle, value: "",
                       tint: StrandPalette.accent, frac: 0.5) { showCoachLauncher = true }
        }
    }

    /// One card row pushing its `TabRoute` by value — the first hop off the Today root must ride
    /// the tab's `NavigationPath` so a re-tap of the Today tab can pop it (#198; see TabRoute.swift).
    private func cardLink(_ route: TabRoute, title: String, sub: String,
                          value: String, tint: Color, frac: Double?) -> some View {
        NavigationLink(value: route) {
            cardLinkBody(title: title, sub: sub, value: value, tint: tint, frac: frac)
        }
        .buttonStyle(LiquidPressStyle())
    }

    /// The same row, running `action` instead of pushing (#1862). Coach is the one dashboard card that
    /// opens a SHEET, so it cannot ride `NavigationLink`; sharing `cardLinkBody` keeps the two kinds of
    /// row from drifting apart visually.
    private func cardAction(title: String, sub: String, value: String, tint: Color, frac: Double?,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            cardLinkBody(title: title, sub: sub, value: value, tint: tint, frac: frac)
        }
        .buttonStyle(LiquidPressStyle())
    }

    @ViewBuilder
    private func cardLinkBody(title: String, sub: String, value: String,
                              tint: Color, frac: Double?) -> some View {
        HStack(spacing: 12) {
                // tapPassesThrough: the vessel's splash gesture would otherwise swallow the enclosing
                // Button's tap, leaving a dead 30pt disc on the leading edge of a tappable card row.
                LiquidVessel(value: frac, tint: tint, animated: false, tapPassesThrough: true)
                    .frame(width: 30, height: 30)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title.uppercased()).font(StrandFont.overlineScaled(11)).tracking(1.0)
                        .foregroundStyle(StrandPalette.textPrimary)
                    Text(sub).font(StrandFont.caption).foregroundStyle(StrandPalette.textTertiary)
                }
                Spacer(minLength: 8)
                Text(value).font(StrandFont.number(17)).foregroundStyle(StrandPalette.textPrimary)
                Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(StrandPalette.textTertiary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
        .background(NoopPanelSurface(tint: tint, cornerRadius: 20, surfaceOpacity: cardOpacity))
    }

    // MARK: - Synthesis (greeting + readiness pills + one-liner)

    /// Liquid parity with classic `effortZeroNote`: the "no cardio load yet" line shown in the synthesis
    /// card when today's Effort is ~0, so a calm day explains itself instead of a bare 0. Reuses classic's
    /// String Catalog entry verbatim — one key serves both Today screens.
    private var effortZeroNote: String? {
        guard EffortDisplay.showsZeroNote(strain: effortStrain(displayDay), isToday: selectedDayOffset == 0) else { return nil }
        return String(localized: "No cardio load yet. Effort builds once your heart rate climbs into your effort zone (around 50% of your heart-rate reserve). A calm day honestly reads near zero.")
    }

    private var synthesisSection: some View {
        VStack(spacing: 8) {
            HStack {
                Text(greeting).font(StrandFont.rounded(19)).foregroundStyle(StrandPalette.textPrimary)
                    .lineLimit(1).minimumScaleFactor(0.6)   // yield to the pills rather than push them to wrap
                Spacer(minLength: 8)
                HStack(spacing: 8) {
                    if let word = readinessWord {
                        Text(word)
                            .font(StrandFont.caption.weight(.bold))
                            .foregroundStyle(StrandPalette.chargeColor)
                            .padding(.horizontal, 13)
                            .padding(.vertical, 6)
                            .background(Capsule().fill(StrandPalette.chargeColor.opacity(0.14))
                                .overlay(Capsule().strokeBorder(StrandPalette.chargeColor.opacity(0.3), lineWidth: 1)))
                    }
                    HStack(spacing: 5) {
                        Circle().fill(StrandPalette.chargeColor).frame(width: 6, height: 6)
                        Text(chargeDisplay.stateLabel)
                            .font(StrandFont.caption.weight(.bold))
                            .foregroundStyle(StrandPalette.chargeColor)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Capsule().strokeBorder(StrandPalette.chargeColor.opacity(0.3), lineWidth: 1))
                }
                .fixedSize(horizontal: true, vertical: false)   // pills keep their natural width — no "Calibrating" wrap
            }
            .padding(.horizontal, 2)
            .padding(.top, 4)

            Button { withAnimation(.easeInOut(duration: 0.2)) { synthesisExpanded.toggle() } } label: {
                card {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text("SYNTHESIS").font(StrandFont.overline).tracking(1.6)
                                .foregroundStyle(StrandPalette.textSecondary)
                            Spacer()
                            Text(synthesisExpanded
                                 ? String(localized: "hide")
                                 : String(localized: "show"))
                                .font(StrandFont.caption)
                                .foregroundStyle(StrandPalette.textTertiary)
                        }
                        // While the baseline calibrates, the honest "N of 4 nights" progress replaces the
                        // readiness one-liner here — the same swap classic makes (`calibrationDetail ??
                        // synthesisCardDetail`), so the count the short greeting pill can't carry lands in
                        // the card and both Today screens read identically.
                        Text(chargeDisplay.calibrationDetail ?? synthLine)
                            .font(StrandFont.body).foregroundStyle(StrandPalette.textPrimary)
                            .fixedSize(horizontal: false, vertical: true)
                        // The reason the count is not moving, when nights are arriving empty. Sits under
                        // the progress rather than replacing it: the wearer needs both the number and why.
                        if let why = chargeDisplay.calibrationReason(
                            dayKeys: repo.hrvCalibrationHistory.map(\.day), nightlyHrv: repo.hrvCalibrationHistory.map(\.value),
                            today: Repository.logicalDayKey(Date())) {
                            Text(why).font(StrandFont.caption)
                                .foregroundStyle(StrandPalette.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        // #530 follow-up: the classic hero's "no cardio load yet" note (effortZeroNote),
                        // shown on a calm day so today's ~0 Effort explains itself instead of a bare 0.
                        if let note = effortZeroNote {
                            HStack(alignment: .top, spacing: 6) {
                                Image(systemName: "info.circle")
                                    .font(StrandFont.footnote)
                                    .foregroundStyle(StrandPalette.effortColor)
                                    .accessibilityHidden(true)
                                Text(note)
                                    .font(StrandFont.footnote)
                                    .foregroundStyle(StrandPalette.textTertiary)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        if synthesisExpanded {
                            Text(LocalizedStringKey(readiness.summary)).font(StrandFont.caption)
                                .foregroundStyle(StrandPalette.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
            }
            .buttonStyle(LiquidPressStyle())
        }
    }

    // MARK: - Recovery vitals

    private var recoveryVitalsSection: some View {
        // PER-FIELD, today-first carry: each vital reads today's own value, else falls back to the prior
        // day that recorded THAT vital (#1842 — `vitalsDay` is the freshest row with ANY of the three, so it
        // blanks one the row lacks). Coalesce ONCE so the number and its fill fraction agree.
        let hrv = displayDay?.avgHrv ?? hrvDay?.avgHrv
        let rhr = (displayDay?.restingHr ?? restingHrDay?.restingHr).map(Double.init)
        let resp = displayDay?.respRateBpm ?? vitalsDay?.respRateBpm
        return card {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("RECOVERY VITALS").font(StrandFont.overline).tracking(1.6)
                        .foregroundStyle(StrandPalette.textSecondary)
                    Spacer()
                    if let line = vitalsProvenanceLine {
                        Text(line).font(StrandFont.caption).foregroundStyle(StrandPalette.textTertiary)
                    }
                }
                // #706/#684: the same trends the HRV / Resting HR / Respiratory dashboard cards push. These
                // rows show the SAME three vitals and had no way through, so this card was the one place on
                // Today where a metric was a dead end. Routes taken from `liquidCard`'s own cases so the two
                // surfaces cannot send the same vital to different trends.
                vitalRow(String(localized: "Heart-rate variability"), unitText(hrv, "ms"),
                         StrandPalette.metricCyan, fracOver(hrv, 120), route: .metric("hrv"))
                vitalRow(String(localized: "Resting heart rate"), unitText(rhr, "bpm"),
                         StrandPalette.metricRose, fracOver(rhr, 100), route: .metric("rhr"))
                vitalRow(String(localized: "Breaths per minute"), unitText(resp, "rpm", decimals: 1),
                         StrandPalette.accent, fracOver(resp, 24), route: .metric("resp_rate"))
            }
        }
    }

    /// A recovery-vital row, optionally pushing its own metric trend.
    ///
    /// `route: nil` renders exactly what shipped before - no link, no chevron - so a row that goes nowhere
    /// never claims otherwise. `LiquidPressStyle` is not decoration: a bare `NavigationLink` applies the
    /// default link chrome and would tint the whole row, which is the same reason `cardLink` carries it.
    private func vitalRow(_ label: String, _ value: String, _ tint: Color, _ frac: Double?,
                          route: TabRoute? = nil) -> some View {
        Group {
            if let route {
                NavigationLink(value: route) { vitalRowBody(label, value, tint, frac, linked: true) }
                    .buttonStyle(LiquidPressStyle())
            } else {
                vitalRowBody(label, value, tint, frac, linked: false)
            }
        }
    }

    private func vitalRowBody(_ label: String, _ value: String, _ tint: Color, _ frac: Double?,
                              linked: Bool) -> some View {
        HStack(spacing: 12) {
            // Same as cardLinkBody: without this the disc eats the row's NavigationLink tap.
            LiquidVessel(value: frac, tint: tint, animated: false, tapPassesThrough: true)
                .frame(width: 26, height: 26)
            Text(label).font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
            Spacer()
            Text(value).font(StrandFont.number(15)).foregroundStyle(StrandPalette.textPrimary)
            if linked {
                Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(StrandPalette.textTertiary)
            }
        }
    }

    // MARK: - Key metrics grid

    /// The chosen detailed-graph window's oldest day key (1 week / 2 weeks / 1 month ending on the
    /// selected day). The loader banks a 30-day superset; render filters down so a window change in the
    /// editor applies instantly, no reload.
    private var sparkWindowCutoffKey: String {
        let days = (keyMetricsWindowDays == 7 || keyMetricsWindowDays == 30) ? keyMetricsWindowDays : 14
        let cal = Calendar.current
        let anchor = cal.startOfDay(for: selectedLogicalDay)
        return Repository.localDayKey(cal.date(byAdding: .day, value: -(days - 1), to: anchor) ?? anchor)
    }

    /// A metric's spark values inside the chosen window, oldest → newest.
    private func windowedSpark(_ key: String) -> [Double] {
        let cutoff = sparkWindowCutoffKey
        return (kSparks[key] ?? []).filter { $0.0 >= cutoff }.map { $0.1 }
    }

    /// The Key-Metrics header's trailing label for the chosen detailed-graph window (Android twin).
    private var trendWindowLabel: String {
        switch keyMetricsWindowDays {
        case 7: return String(localized: "7-day trend")
        case 30: return String(localized: "30-day trend")
        default: return String(localized: "14-day trend")
        }
    }

    private var keyMetricsSection: some View {
        // HRV / Rest HR (+ Blood Oxygen / Respiratory) tiles share the recovery vitals' per-field
        // today-first carry so they don't blank at the rollover while Recovery/Strain/Rest stay strictly
        // today's own (they are scored surfaces).
        let hrv = displayDay?.avgHrv ?? hrvDay?.avgHrv
        let rhr = (displayDay?.restingHr ?? restingHrDay?.restingHr).map(Double.init)
        return VStack(spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                // The label names the window the DETAILED tiles graph, so it is only honest while they
                // are drawn: with the trend graphs off (the default) nothing in this section renders a
                // trend, and the header was still announcing one (#2376).
                sectionHead("KEY METRICS", trailing: keyMetricsDetailed ? trendWindowLabel : nil)
                // #430 parity: the SAME editor the classic grid uses — selection + order + Detailed tiles.
                Button { customizationDestination = .keyMetrics } label: {
                    Text(String(localized: "Edit").uppercased())
                        .font(StrandFont.overlineScaled(11))
                        .tracking(1.0)
                        .foregroundStyle(StrandPalette.accent)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Edit Key Metrics")
            }
            // #430 parity: the grid honours the Key-Metrics editor (selection + order, all ten metrics)
            // instead of a hard-coded six — the bespoke Sleep-hours ktile gives way to the shared REST
            // score tile, aligning the liquid grid with the classic macOS grid and Android.
            LazyVGrid(
                columns: Array(
                    repeating: GridItem(.flexible(), spacing: NoopMetrics.gap),
                    count: 2
                ),
                spacing: NoopMetrics.gap
            ) {
                ForEach(enabledKeyMetrics) { metric in
                    ktileFor(metric, hrv: hrv, rhr: rhr)
                }
            }
            NavigationLink(value: TabRoute.metricExplorer) {
                LiquidFullWidthNavigationAction("Show all metrics")
            }
            .buttonStyle(LiquidPressStyle())
        }
    }

    /// One editor-selected Key-Metric tile: the metric's value/tint/fill exactly as the old hard-coded
    /// tiles read them (Android's descriptor map is the twin), plus the metric-catalog `key` that names
    /// both its 14-day spark series and its tap-through detail. Weight has no liquid value source yet —
    /// its tile reads "—" but still taps through to the weight trend detail (which has its own series).
    @ViewBuilder
    private func ktileFor(_ metric: KeyMetric, hrv: Double?, rhr: Double?) -> some View {
        switch metric {
        case .charge:
            let recovery = HomeScoreValue.resolve(chargeDisplay.pct)
            // Reads the SAME resolved Charge the hero draws, not `displayDay?.recovery` raw — the tile and the
            // hero are the same number, so a carry that reached only one of them would put two answers for
            // Charge on one screen. (#543: one prior row feeds every recovery-derived read-out.) Strain below
            // stays raw, matching the Effort hero, which correctly does not carry.
            ktile(String(localized: "Recovery"), icon: keyMetricIcon(metric), RecoveryStrainDetailLogic.recoveryPercent(recovery).map(String.init) ?? "—", "%", recovery.map(StrandPalette.recoveryColor) ?? StrandPalette.ringTrack, frac(recovery), key: HeroRingMetric.charge)
        case .effort:
            // #492: Effort is a load index (0–100 NOOP / 0–21 WHOOP), NOT a percentage, and the unit was
            // wrong on either axis. Fixed on Android and in `TodayView` at the time; THIS view kept the old
            // form, so the tile also ignored the scale toggle — the hero ring above it read ~8 on the WHOOP
            // axis while this read 38. `effortText` is the same shared formatter the ring and the workout
            // rows use, so all three now agree by construction.
            ktile(String(localized: "Strain"), icon: keyMetricIcon(metric), effortStrain(displayDay).map { UnitFormatter.effortDisplay($0, scale: .whoop) } ?? "—", "", StrandPalette.strainPrimary, frac(effortStrain(displayDay)), key: HeroRingMetric.effort)
        case .rest:
            ktile(String(localized: "Sleep"), icon: keyMetricIcon(metric), intText(HomeScoreValue.resolve(restScore)), "%", StrandPalette.sleepPrimary, frac(HomeScoreValue.resolve(restScore)), key: HeroRingMetric.rest)
        case .hrv:
            ktile("HRV", icon: keyMetricIcon(metric), intText(hrv), "ms", StrandPalette.metricCyan, fracOver(hrv, 120), key: "hrv")
        case .restingHr:
            ktile(String(localized: "Rest HR"), icon: keyMetricIcon(metric), intText(rhr), "bpm", StrandPalette.metricRose, fracOver(rhr, 100), key: "rhr")
        case .bloodOxygen:
            // Queue 11a: the Liquid tile used to read `spo2Pct` only, with no candidate fallback at all
            // (unlike the classic `TodayView`/`VitalSignsSummary`), so an Oura-only or BLE-only WHOOP
            // 5/MG install with the experimental toggle ON still saw a bare "—" here. Falls back to the
            // device-conditional "spo2_candidate" mean (WHOOP: `spo2_candidate_82`; Oura: ceiling@100
            // `0x6F`, see `AnalyticsEngine.nightlySpo2CeilingMean`) only when `spo2Pct` is nil AND the
            // toggle is ON — same gating as the classic tile, never as the default.
            let spo2Real = displayDay?.spo2Pct ?? vitalsDay?.spo2Pct
            let spo2CandidateOn = PuffinExperiment.spo2CandidateDisplayEnabled
            let spo2CandidateValue = spo2Real == nil && spo2CandidateOn
                ? spo2CandidateByDay[cachedDisplayDay?.day ?? selectedDayKey]
                : nil
            let spo2 = spo2Real ?? spo2CandidateValue
            ktile(String(localized: "Blood Oxygen"), icon: keyMetricIcon(metric), intText(spo2), "%", StrandPalette.metricCyan, fracOver(spo2, 100), key: spo2CandidateValue != nil ? "spo2_candidate" : "spo2",
                  caption: spo2CandidateValue != nil ? String(localized: "strap estimate (unverified)") : nil)
        case .respiratory:
            let resp = displayDay?.respRateBpm ?? vitalsDay?.respRateBpm ?? respDay?.respRateBpm
            ktile(String(localized: "Respiratory"), icon: keyMetricIcon(metric), resp.map { String(format: "%.1f", locale: AppLanguage.activeLocale, $0) } ?? "—", "rpm", StrandPalette.accent, fracOver(resp, 24), key: "resp_rate")
        case .steps:
            ktile(String(localized: "Steps"), icon: keyMetricIcon(metric), stepsText, "", StrandPalette.chargeColor,
                  fracOver(stepCount, 10000), key: stepsDetailKey, detailMetric: stepsDetailMetric)
        case .weight:
            let (val, cap) = weightTile(weightKg)
            ktile(String(localized: "Weight"), icon: keyMetricIcon(metric), val, "", StrandPalette.metricAmber, nil, key: "weight", caption: cap)
        case .calories:
            // #616: imported-first value (imported ?: activeEnergyKcalEst) + route the tap to the matching
            // detail source, so the number, its sparkline and the chart it opens all agree.
            ktile(String(localized: "Calories"), icon: keyMetricIcon(metric), intText(caloriesCount), "kcal", StrandPalette.metricAmber,
                  fracOver(caloriesCount, 800), key: "active_kcal", detailMetric: caloriesDetailMetric)
        case .skinTemp:
            // Added 2026-08-24 (queue 11c follow-up): first Key Metrics appearance for Skin Temp — was
            // already a "Your Cards" tile (`DashboardCard.skinTemp`), never a Key Metrics one. Same
            // 2-level carry the Blood Oxygen case just above uses (displayDay → the cached vitals carry),
            // and the SAME `SkinTempDisplay` formatter every other skin-temp surface uses so a deviation
            // reads "+0.1 Δ°C" here exactly as it does on "Your Cards"/the Deep Timeline, never the plain
            // `%+.1f°` that read a fabricated absolute value for a signed deviation (#622).
            // #1844: same lead-with-the-absolute resolution as "Your Cards" above, so the two agree.
            let skinText = TodayView.skinTempCardValue(reading: skinTempLeadReading,
                                                       fahrenheit: temperatureUnit == .fahrenheit)
            // The card's own unit is deliberately empty — the value carries "°C"/"Δ°F" itself, same as
            // the classic TodayView Skin Temp card.
            ktile(String(localized: "Skin Temp"), icon: keyMetricIcon(metric), skinText, "", StrandPalette.metricAmber, nil, key: "skin_temp")
        }
    }

    private func keyMetricIcon(_ metric: KeyMetric) -> String {
        switch metric {
        case .charge: return "heart.fill"
        case .effort: return "bolt.fill"
        case .rest: return "moon.stars.fill"
        case .hrv: return "waveform.path.ecg"
        case .restingHr: return "heart.circle.fill"
        case .bloodOxygen: return "drop.fill"
        case .respiratory: return "lungs.fill"
        case .steps: return "figure.walk"
        case .weight: return "scalemass.fill"
        case .calories: return "flame.fill"
        case .skinTemp: return "thermometer.medium"
        }
    }

    private func ktile(_ label: String, icon: String, _ value: String, _ unit: String, _ tint: Color, _ frac: Double?,
                       key: String? = nil, detailMetric: MetricDescriptor? = nil, caption: String? = nil) -> some View {
        let displayValue = Self.tileDisplayValue(value, unit: unit)
        let tile = VStack(alignment: .leading, spacing: NoopMetrics.space2) {
            MetricCard(label: label, value: displayValue, detail: caption, systemImage: icon, color: tint)
            if keyMetricsDetailed {
                let spark = key.map { windowedSpark($0) } ?? []
                if spark.count >= 2 {
                    Sparkline(values: spark, gradient: Gradient(colors: [tint.opacity(0.5), tint]))
                        .frame(height: NoopMetrics.space6)
                        .accessibilityHidden(true)
                }
            }
        }
        // #430 parity: tap -> the metric's trend detail (the same Explore dossier its MetricRow pushes,
        // closure-based NavigationLink per #38). A metric with no catalog entry stays inert.
        return Group {
            if key == HeroRingMetric.charge {
                NavigationLink(value: TabRoute.recoveryDetailForDay(dayKey: cachedRecoveryDayKey ?? selectedDayKey)) { tile }
                .buttonStyle(.plain)
            } else if key == HeroRingMetric.effort {
                NavigationLink(value: TabRoute.strainDetailForDay(dayKey: selectedDayKey, effortOverride: effortStrain(displayDay).flatMap { $0.isFinite && (0...100).contains($0) ? $0 : nil }, windowDayKey: Repository.localDayKey(selectedLogicalDay))) { tile }
                .buttonStyle(.plain)
            } else if key == HeroRingMetric.rest {
