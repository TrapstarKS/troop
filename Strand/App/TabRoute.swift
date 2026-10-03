import SwiftUI
import StrandDesign
import StrandAnalytics

struct LocalNotificationRoutePayload: Hashable {
    let context: LocalNotificationContext
    static func == (lhs: Self, rhs: Self) -> Bool { lhs.context == rhs.context }
    func hash(into hasher: inout Hasher) { hasher.combine(context.identity) }
}

// MARK: - TabRoute
//
// Value-based routes for every push that leaves a primary tab's ROOT (#198, Path A). The iOS tab
// shell binds each tab's `NavigationStack` to a `NavigationPath`, and a path only tracks pushes
// made through it — a closure-destination `NavigationLink` bypasses the path entirely. So the
// root-level links in the tab roots must push a VALUE for "re-tap the active tab" to pop back to
// the root (#135) without the #197 rebuild. Deeper links stay closure-based on purpose: popping a
// route off the path also pops everything pushed above it, so only the first hop needs a value.
//
// Shared with macOS because the tab roots (TodayView / LiquidTodayView / TrendsView) are the SAME
// views the sidebar shell hosts — every `NavigationStack` that hosts one must register
// `tabRouteDestinations()`, and must register it exactly ONCE: the same value type resolving
// against two registrations in one stack double-pushes (see MetricExplorerView, #38).

/// One first-hop destination reachable from a tab root. `Hashable` so it can ride a `NavigationPath`.
enum TabRoute: Hashable {
    /// The whole-day, full-resolution HR timeline (Liquid Today's live-HR card tap, #979).
    case fullDayChart
    /// One metric's detail page by `MetricCatalog` key — the same tap-through Today's cards and
    /// Trends' small-multiples share. Each card opens ITS metric (2026-07-02: not the shared
    /// Health screen).
    case metric(String)
    /// One metric's detail by BOTH key and source. `steps` exists under several sources (my-whoop,
    /// apple-health, xiaomi-band); routing by bare key alone resolves whichever catalog entry is
    /// declared first, so a card's tap-through would silently depend on declaration order. This pins
    /// the exact source, so the catalog's ordering can never decide where a card taps through.
    case metricSourced(key: String, source: String)
    case metricExplorer
    case workouts
    case dataSources
    case stress
    case sleep
    case health
    case hydration
    case coupled
    case recoveryDetail
    case strainDetail
    case recoveryDetailForDay(dayKey: String?)
    case strainDetailForDay(dayKey: String?, effortOverride: Double?, windowDayKey: String? = nil)
    case sleepDetailForDay(dayKey: String?)
                case .localBriefing: LocalBriefingView()
                case .localNotice(let payload):
                    if payload.context.route == "local_briefing" {
                        LocalBriefingView(notificationContext: payload.context)
                    } else {
                        LocalRecordedNoticeView(notificationContext: payload.context)
                    }
                case .sleepDetail: SleepView()
                case .sleepPlanner: SmartAlarmView()
                case .healthMonitor: HealthMonitorView()
                case .healthspan: HealthspanView()
                case .stressMonitor: StressMonitorView()
