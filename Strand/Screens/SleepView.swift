import SwiftUI
import Foundation
import StrandDesign
import StrandAnalytics
import WhoopStore
#if canImport(UIKit)
import UIKit
#endif

// MARK: - Sleep detail

struct SleepView: View {
    let initialDayKey: String?
    @State private var initialSelectionApplied: Bool
    @State private var pendingInitialDayKey: String?
    @State private var consumedInitialDayKey: String?
    @State private var selectedDayKey: String?
    @State private var unavailableRequestedDay: String?
    @EnvironmentObject var repo: Repository
    @EnvironmentObject private var router: NavRouter
    // NOTE: SleepView itself deliberately does NOT observe `LiveState` OR `AppModel`. A connected strap
    // publishes at ~1 Hz, and `AppModel` itself publishes `bpm` at that same ~1 Hz (AppModel.swift:202) —
    // `@EnvironmentObject` subscribes to the WHOLE object's `objectWillChange` regardless of which
    // properties are read, so holding either here would re-evaluate this heavy ~3000-line body on every
    // tick. The live dependencies — the "going to sleep / awake" mark card (appends to the strap log),
    // the "Syncing strap history…" note, and the body-clock dial's `circadianPhase` (#1680) — each own
    // their OWN `@EnvironmentObject var live`/`appModel` in a small leaf below (mirrors the Today
    // leaf-scoping pattern and HealthView.swift:17-22), so a tick refreshes only that leaf.
    @EnvironmentObject var intelligence: IntelligenceEngine

    /// Memoized snapshot of every expensive derivation (latest Night with its intervals
    /// resolved once, the seven metric series, the trend points, the typical means). Rebuilt
    /// only when the underlying repo data actually changes — NOT on hover/animation/1Hz HR
    /// ticks that merely re-evaluate `body`. `nil` until first build or when there's no night.
    @State private var model: SleepModel?
    /// The Sleep tab's stage-chart shape (Settings → Appearance → Sleep chart). Display-only; Filled/Ribbon
    /// draw the WHOOP-style stepped hypnogram, Classic keeps the per-stage rows. Mirrors Android. (#sleep-chart-style)
    @AppStorage(SleepChartStyle.storageKey) private var sleepChartStyleRaw = SleepChartStyle.classic.rawValue
    /// The repo signature the cached `model` was built from. Cheap to compute every render;
    /// when it differs from the current inputs we rebuild the model.
    @State private var modelKey: SleepInputKey?
    @State private var loadedSleepRefresh: Int?
    @State private var resultTracker = SleepResultChangeTracker()
    @State private var resultNoticeVisible = false
    @State private var resultNoticeRevision = 0
    @State private var resultNoticeScope: String?

    /// Which night the hero hypnogram shows: 0 = last night, N = N sleep-sessions back.
    /// Resolves an active requested day after reload; otherwise resets to 0. A stale offset would point
    /// at a different session after a sync. The memoized trend `model` stays cached since
    /// the trends are night-independent. (#160)
    @State private var nightOffset = 0
    /// Memoized decode of the NAVIGATED night (nil when `nightOffset == 0` — the hero reads
    /// `model.night` then). Rebuilt only in the `nightOffset` / data-key onChange handlers;
    /// `decodedNight` JSON-decodes, which must never run per body pass (1Hz HR ticks). (#160)
    @State private var navNight: Night?

    /// Every sleep BLOCK across both sources, UN-deduplicated (`repo.allSleepSessions`) — `repo.sleeps`
    /// keeps one winner per night for the dashboard, collapsing split-sleep days (a nap + a main
    /// sleep on the same day) into a single block. The hero groups these by day (`navDays`) and
    /// merges each day into one Night, so a split day reads as one correctly-totalled night with the
    /// gaps preserved. Oldest→newest. Falls back to `repo.sleeps` until loaded. (#170)
    @State private var allSessions: [CachedSleepSession] = []
    /// `navDays` memoized, rebuilt where `model` is.
    ///
    /// Grouping calls `Calendar.startOfDay` once per session, and the browsable history is every block
    /// ever recorded, so recomputing it per render is a per-frame pass over years of nights. Body reaches
    /// it more than once (the wake-timestamp list, and `dayBlocks(at:)` for the source blocks), so a
    /// scroll paid it repeatedly. Invalidated by the same two paths that rebuild `model`: `dataKey`
    /// covers a `repo.sleeps` change, and the refresh task covers `allSessions` reloading. Nil falls back
    /// to computing it, so a first render before either has run is correct rather than empty.
    @State private var navDaysCache: [[CachedSleepSession]]?

    /// The user's LEARNED habitual midsleep (local time-of-day seconds), or nil under the cold-start
    /// threshold. Loaded from `repo.habitualMidsleepSec()` — the SAME value `AnalyticsEngine.analyzeDay`
    /// threads into the daily total — and fed into the main-night selector so the hero, the naps split,
    /// and the edit target pick the SAME block the analytics rollup did, for a shift/late sleeper too. nil
    /// keeps the existing cold-start overnight-band fallback. (#547) Refreshed with `allSessions`.
    @State private var habitualMidsleepSec: Int? = nil

    /// Persisted per-epoch MOTION series keyed by each session's detected `startTs` (#407). Loaded in the
    /// same `.task` as `allSessions` from `repo.sessionMotions(sessions:)`, then laid along the hypnogram for
    /// the SAME main-night GROUP blocks the hero resolved (mergeDay's group) — we do NOT re-resolve the
    /// night, only read the already-chosen group's stored motion. A block with no stored series stays absent
    /// (honest empty state for older rows whose `motionJSON` is NULL). Refreshed with `allSessions`.
    @State private var motionByStart: [Int: [Double]] = [:]

    /// Non-nil while the wake-time editor sheet is open. Carries the night's stable key (`startTs`) and
    /// current wake time so the editor seeds its picker; saving routes through `repo.editSleepWakeTime`,
    /// which marks the session `userEdited` so a later strap sync can't revert the correction. (#318)
    @State private var wakeEdit: WakeEdit?

    /// Non-nil while the "Add nap" picker sheet is open (#508). Carries a seed bed/wake for the picker;
    /// saving routes through `repo.addManualNap`, which stages the chosen window from raw and writes it as
    /// its OWN separate session row (`userEdited = 1`) — never folded into the night's main sleep.
    @State private var addNap: AddNapSeed?

    /// True while the hero's "why this is your main sleep" popover is open. The reason text comes
    /// straight from the foundation `MainNightReason` for the displayed night's blocks — never
    /// re-derived here — so the explainer says exactly what the selector decided. (spec 2026-06-20 C1)
    @State private var showMainSleepWhy = false
    /// The stable detected key of the nap whose "why this is a nap" popover is open, or nil. Keyed by
    /// the nap's own `startTs` so one popover shows at a time even with several nap rows. (C1)
    @State private var napWhyStartTs: Int?

    /// WHOOP-style stage highlight: tapping a stage row under the timeline lights that stage up on the
    /// chart and recedes the rest (tap again to clear). Display-only selection state. (ryanAtriumAi #988)
    @State private var selectedStage: SleepStage? = nil

    /// Sleeping heart-rate for the displayed night (1-min buckets), for the WHOOP-style HR chart above
    /// the stage rows. Loaded once per night via `.task(id:)` on the stage card. (ryanAtriumAi #988)
    @State private var nightHR: [HRBucket] = []

    /// The transient UNDO banner shown after a suppressing delete (#65). Non-nil for ~7 seconds: carries
    /// the snapshot needed to restore the deleted night into its ORIGINAL namespace and the window text
    /// for the message. A user-created/edited delete writes no tombstone but still offers undo (restore).
    @State private var sleepUndo: SleepUndoBanner?
    /// The pending auto-dismiss task for `sleepUndo`, cancelled when a new delete replaces the banner or
    /// the user hits Undo, so a stale timer can't clear a fresh banner.
    @State private var sleepUndoTask: Task<Void, Never>?

    // #sleep-layout: the arrangeable analytical-card order + explicit hidden set, byte-identical to the
    // Android SleepLayoutPrefs keys. Reordered via the Arrange sheet; display-only, no metric changes.
    @AppStorage(SleepLayoutPrefs.orderKey) private var sleepSectionOrderRaw = ""
    @AppStorage(SleepLayoutPrefs.hiddenKey) private var sleepHiddenSectionsRaw = ""
    @State private var showSleepCustomize = false

    init(initialDayKey: String? = nil) {
        self.initialDayKey = initialDayKey
        _initialSelectionApplied = State(initialValue: initialDayKey == nil)
        _pendingInitialDayKey = State(initialValue: initialDayKey)
    }

    /// The analytical cards to render, in saved order minus the hidden set.
    private var sleepVisibleSections: [SleepSection] {
        SleepLayoutPrefs.visibleOrder(orderRaw: sleepSectionOrderRaw, hiddenRaw: sleepHiddenSectionsRaw)
    }

    private var requestedSelectionReady: Bool {
        initialSelectionApplied && consumedInitialDayKey == initialDayKey
                    selectNight(at: nightOffset - 1)
