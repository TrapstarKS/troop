# WHOOP-style UI foundation

The reconstruction follows the public May-2025 four-destination shell and the blue-gray Sleep reference family. Colors and geometry below are local reconstruction choices based on public references, not vendor runtime specifications. System fonts, SF Symbols and Material icons are used; proprietary assets and font binaries are not bundled. Existing local analytics, persistence, consent, device controls and feature gates retain their meaning.

## Tokens

Apple tokens live in `StrandDesign`; Android tokens live in `com.noop.ui`. Existing token names remain supported so current screens inherit the new look without being rebuilt. New installs default to dark; explicitly selected appearance and custom backgrounds remain available.

| Role | Apple / Android token | Dark reconstruction |
|---|---|---|
| Canvas gradient | `StrandPalette.canvasTop` / `Palette.canvasTop`; `canvasBottom` | `#283339` → `#101518` |
| Card | `surfaceRaised` on both platforms | `#202528` |
| Elevated card | `surfaceElevated` / `surfaceOverlay` | `#2C2F34` |
| Inset | existing `surfaceInset` / `surfaceInset` | `#080C0D` |
| Divider | existing `hairline` / divider tokens | `#393D41` |
| Main text | `textPrimary` on both platforms | White |
| Supporting text | `textSecondary` on both platforms | `#BABEC0` |
| Quiet text | `textTertiary` on both platforms | Contrast-adjusted gray |
| Recovery bands | `recoveryHigh`, `recoveryMedium`, `recoveryLow` | `#16EC06`, `#FFDE00`, `#FF0026` |
| Strain | `strainPrimary` | `#0093E7` |
| Sleep score | `sleepPrimary` | `#7BA1BB` |
| Favorable comparison | `positive` | `#00F19F`; distinct from Recovery lime |
| Stress | `stressLow`, Apple `stressColor` / Android `stressMedium`, `stressHigh` | Blue `#67AEE6`, teal `#00F19F`, amber `#FFA722` |
| Coach decoration | `coachViolet`, `coachCyan` | Separate violet/cyan gradient |
| Ring track | `ringTrack` | `#353D40` |

Use `StrandFont` / Android theme typography for text, and `NoopMetrics` / `Metrics` for spacing, radii and dimensions. Scores use system bold numerals with tabular digits where the platform supports them. Detail dial is about 260 points/dp; compact dial about 90. Body roles remain scalable. Stage colors retain the existing NOOP stage vocabulary because the current hypnogram colors are unverified. Never treat a missing score as zero.

### Typography and geometry

Apple named text styles follow SwiftUI's platform and Dynamic Type sizes. Android named styles use scalable `sp`; numeric display styles use tabular figures on both platforms. The public roles below should be used instead of a new font declaration.

| Role | Apple `StrandFont` | Android `NoopType` |
|---|---|---|
| Main title | `title1`: system title, bold | `title1`: 26sp, semibold |
| Section title | `title2`: system title2, semibold | `title2`: 22sp, semibold |
| Headline | `headline`: system headline, semibold | `headline`: 17sp, semibold |
| Body | `body`: system body, regular | `body`: 16sp, medium, 23sp line height |
| Supporting copy | `subhead`, `caption`, `footnote`: corresponding system styles | `subhead` 13sp, `caption` 12sp, `footnote` 11sp |
| Uppercase micro-label | `overline` / `overlineScaled`, `overlineTracking` 1.1 | `overline`: 11sp bold, `overlineTracking` 1.4 |
| Full / compact dial value | `display(scoreDisplaySize)` 68 / `display(compactScoreDisplaySize)` 25 | `dialValueFull` 68sp / `dialValueCompact` 26sp |
| Other numbers | `number`, `bodyNumber`, `captionNumber` | `number`, `bodyNumber`, `captionNumber` |
| Raw logs | `mono` | `mono` |

Existing platform footprints remain available. These numbers are layout tokens, not physiological limits. Apple values are points; Android values are dp.

| Role | Apple `NoopMetrics` | Android `Metrics` |
|---|---|---|
| Structural spacing | `space1/2/3/4/5/6/8/10`: 4/8/12/16/20/24/32/40 | `space4/8/12/16/24`: 4/8/12/16/24; optical half steps also named |
| Page inset | `screenPadding` / `screenHPadding`: 20 | `screenPadding`: 24 |
| Card inset / gap | `cardPadding`: 16; `gap`: 12 | `cardPadding`: 16; `gap`: 12 |
| Section gap | `sectionGap`: 24 | `sectionGap`: 28 |
| Card / compact radius | `cardRadius`: 16; `NoopVisualStyle.compactRadius`: 12 | `cardRadius`: 18; `cornerSm`: 12 |
| Full / compact dial diameter | `scoreDialDiameter` 260 / `compactScoreDialDiameter` 90 | `detailDial` 260 / `compactDial` 90 |
| Full / compact dial stroke | `scoreDialStroke` 15 / `compactScoreDialStroke` 5 | `detailDialStroke` 15 / `compactDialStroke` 5 |
| Tab capsule height | `tabHeight`: 60 | `tabHeight`: 64 |
| Coach orb diameter | `coachDiameter`: 58 | `coachOrb`: 60 |
| Compact header control | `compactControlSize`: 36 | `iconButton`: 48 |
| Profile avatar | `TopChrome` uses `touchTarget`: 44 | `chromeAvatar`: 36 inside the 48dp control |
| Interactive target | `touchTarget`: 44 | `iconButton`: 48; Material minimum targets retained |

## Reusable components

Apple implementations are in `Packages/StrandDesign/Sources/StrandDesign/WhoopComponents.swift`; Android implementations are in `android/app/src/main/java/com/noop/ui/WhoopComponents.kt`. All text arguments are localized by the host. Components own drawing and styling; screens own date selection, resolved values, availability, comparisons, device state and navigation.

| Component | Inputs / behavior |
|---|---|
| `ScoreDial` | `label`, formatted `value`, separate `unit`, nullable normalized `progress`, domain `color`, full/compact `size`; optional normalized `target`, `targetRange` and `accessibilityLabel`. Null/nonfinite progress shows an empty track. The whole dial is one accessible element; supply a clean custom label when the visible label contains decorative symbols. Supply already-resolved values; this component does not compute physiology. |
| `MetricCard` | Label, value, unit, optional detail, icon and color. Existing `NoopCard` / `StatTile` remain available. |
| `TrackedSectionHeader` | Title, optional uppercase micro-label and optional action label/callback. |
| `ContributorRow` | Label, value, unit, optional icon, comparison text/icon/color. Direction and favorability are supplied independently. |
| `StatusPill` | Localized state, optional icon and semantic color. Color never carries the state alone. |
| `InsightCallout` | Local explanation and optional action. Decoration does not imply a cloud service or initiate a provider request. |
| `TopChrome` | Date label, previous/next/profile/strap accessibility labels, initials, nullable battery percentage, connection state, next-date gate and five callbacks. Use the screen's existing date resolver. Never create a second date state in the shell. Apple hosts should use the existing `LiveConsoleReadout.batteryPercent` or `StrapBatteryDisplay.resolve` path, including active-device/connection gates and the `--demo-sync` override, rather than reading a raw cached WHOOP battery field. |
| `TabCapsule` | Items with stable `id`, localized `label`, platform icon; `selectedID`, selection callback. |
| `CoachOrb` | Localized accessibility label and tap callback; opens saved-key Coach or offline Daily Outlook. Respects the existing master switch and consent. |
| Chart styling | Apple `ChartTokens` / Android `WhoopChartStyle` plus shared domain/stage tokens and existing `TrendChart`, `Hypnogram`, sparklines and bar components. Preserve real timestamps, gaps and discrete sleep stages. |

Apple capsule item initializer: `TabCapsuleItem(id:label:systemImage:)`. Android uses `TabCapsuleItem(id,label,icon)`. Apple uses `ScoreDialSize.full` / `.compact`; Android uses `ScoreDialSize.Full` / `.Compact`. Percent progress is displayed score / 100. Any Strain normalization belongs to the caller's presentation layer, never storage or scoring.

## Shell map

| Tab | Current host | Preserved routes |
|---|---|---|
| Home | Existing Today; Apple retains Liquid/classic preference | Existing cards, quick actions, date navigation, avatar/settings and device controls |
| Health | Health landing | Health Monitor, Healthspan, Stress Monitor, Live HR and vital details |
| Plan | Local navigation landing | Weekly Plan, Journal/Insights, What Moves You, Trends, Intelligence and Sleep Planner |
| More | MoreHub profile/settings directory and expandable feature index | All prior insights/body/data/app destinations plus explicit Sleep, Sleep Planner and Devices access |
| Coach orb | CoachDestinationView / saved-key chooser | Saved-key Coach or offline Daily Outlook; configuration and consent remain explicit |

Apple retains a `TabView` with four stable tags (Home 0, Health 1, Plan 2, More 3), independent navigation paths and scroll-to-top tokens. Its native tab bar is hidden in favor of the shared capsule. Re-tapping a selected tab refreshes and pops its path, or scrolls its root to the top. Home retains its day swipe; pushed screens retain native back gestures. Quick-action, active-workout, devices and pillar sheets stay available. `NavRouter.openTrends()` opens Trends inside Plan; `openCoach()` presents Coach; journal requests retain their day-offset handoff. Coach requests dismiss an ordinary quick-action/device sheet or minimize an open Lift Session before presenting, so a notification request cannot compete with that presentation. Minimizing preserves the running session. The routed sheet remains occupied through its closing animation; a competing Home Screen shortcut or Coach request is delivered from the dismissal callback, with the latest request winning.

### App route hooks for screen tracks

Swift `TabRoute` and `tabRouteDestinations()` are internal to the app module and available to its screen files; they are not exports of the `StrandDesign` package. Android `WhoopRoute` is public. Apple screens push `NavigationLink(value: TabRoute.<case>)` and rely on **one** `.tabRouteDestinations()` registration per stack. Do not register the same enum twice. The shell owns destination wiring; new screens can replace the existing host for their case without renaming it.

| Purpose | Apple `TabRoute` | Android `WhoopRoute` | Current content |
|---|---|---|---|
| Recovery detail | `.recoveryDetail` | `recoveryDetail` (`recovery_detail`) | RecoveryDetailView / RecoveryDetailScreen |
| Strain detail | `.strainDetail` | `strainDetail` (`strain_detail`) | StrainDetailView / StrainDetailScreen |
| Sleep detail | `.sleepDetail` | `sleepDetail` (`sleep`) | Existing Sleep |
| Sleep Planner | `.sleepPlanner` | `sleepPlanner` (`smart_alarm`) | Existing alarm settings; not a claim of WHOOP planner parity |
| Health Monitor | `.healthMonitor` | `healthMonitor` (`vital_signs`) | HealthMonitorView / HealthMonitorScreen |
| Healthspan | `.healthspan` | `healthspan` (`healthspan`) | HealthspanView / HealthspanScreen; local estimates retain their provenance |
| Stress Monitor | `.stressMonitor` | `stressMonitor` (`stress`) | StressMonitorView / StressMonitorScreen |
| Weekly Plan | `.weeklyPlan` | `weeklyPlan` (`weekly_plan`) | WeeklyPlanView / WeeklyPlanScreen; saved plans remain local |
| Journal | `.journal` | `journal` (`insights`) | Existing Journal/Insights |

Day-aware Apple first hops use `.recoveryDetailForDay(dayKey:)`, `.strainDetailForDay(dayKey:effortOverride:windowDayKey:)` and `.sleepDetailForDay(dayKey:)`. Recovery carries the resolved scored day; Strain carries the selected day and only a finite stored 0…100 Effort override. Bare cases remain compatible. Their destinations share the shell's scroll observer and bound path, so a selected-tab re-tap pops the detail.

`NavRouter.openLocalNotification(route:)` accepts `devices`, `workouts`, `weekly_plan` and `local_briefing`. Both Apple apps attach the buffered presenter callback; shells consume on appearance and later changes only after mandatory gates. iOS waits for an ordinary sheet dismissal before delivering those requests. Local reports always open LocalBriefingView, even with Coach disabled or a configured provider. macOS adds More, Weekly Plan and Daily Outlook sidebar entries.

Android `WhoopRoute.localBriefing` and `.localNotifications` expose `local_briefing` and `local_notifications`. MoreHubScreen supplies category routes `settings/{category}` with the existing SettingsCategory names; plain `settings` opens the directory. MainActivity retains LOCAL_NOTIFICATION_ROUTE through crash recovery, terms and onboarding, forwards warm intents, and removes the extra after AppRoot consumes it. Known external details install their owning tab first: Weekly Plan and Updates → Trends use Plan; device/workout/report taps use More. A re-tap pops to that root. Stress breathwork pushes Breathe so Back retains Stress. Coach and Coach Settings use one root view-model owner, including direct More entry and restored conversations, so consent and configuration readouts update together. Sharing the model does not generate a reply or send a provider request.

Old `TabRoute.sleep`, `.health`, `.stress`, metric routes and all Android route strings remain supported. Android top-level `Destination.Plan` uses `plan`. Screen tracks should consume these components/tokens and request shell wiring changes through D1, keeping the route names stable.

## Verification boundaries

The first milestone establishes the API and shell, while the subsequent screen tracks replace screen contents. Pixel fidelity is not claimed for unobserved Health, planner, Journal-entry or hypnogram layouts. No BLE commands, schema, analytic formulas, medical classifications or remote service behavior are introduced by this foundation. Build/test results and screenshots are recorded in the external D1 verification log and track status files.

For pure macOS hosted unit tests, an opt-in Debug condition `NOOP_PURE_TEST_HOST` selects `StrandPureTestHost`, an empty SwiftUI App that does not construct `AppModel` or its production services. `StrandApp` remains typechecked. Normal Debug builds and all Release builds keep the production entry. Use the existing Strand scheme with `OTHER_SWIFT_FLAGS='$(inherited) -D NOOP_PURE_TEST_HOST'` and separate DerivedData for the test command, through the orchestration heavy wrapper. Preserve the normal Debug and SwiftPM compilation conditions; a global `SWIFT_ACTIVE_COMPILATION_CONDITIONS` override removes package-specific conditions such as `SWIFT_PACKAGE`. Pass the additive argument through structured process arguments or single-quote it in a shell so `$(inherited)` remains literal. This removes implicit app startup; selected tests must also avoid constructing production BLE services themselves.

The macOS Sleep sidebar pane and iOS Debug direct-screen host each register `.tabRouteDestinations()` once in their own navigation stack, so Sleep Planner value links remain usable outside the four-tab shell.

Runtime shell captures use Apple Debug `--demo-seed` or the dedicated Android `ENABLE_DEMO` flavor. The consumed D6 startup/transport dependencies resolve an immutable process policy before constructing Bluetooth managers, guard source switching and discovery, and suppress saved reconnect/broadcast/service promotion for synthetic sessions. They preserve saved preferences and radio settings. The Apple prerequisite chain also includes the owner's normal startup-ordering fixes; hardware behavior is unverified. The inert unit-test host above is a separate entry and does not substitute for the runtime demo policy.

The iOS26 `noop.bottomBarAutoHide` preference hides the capsule/Coach row on downward user scrolling and restores it on upward scrolling, at the top, and after preference/tab/path changes. The footer inset stays stable to avoid scroll-clamp feedback; the active Lift bar remains visible. `tabRouteDestinations(onVerticalScroll: …)` adds an optional, default-nil callback for the shell; existing zero-argument callers keep compiling. Scroll observation is attached separately to each owned root/destination, following [Apple's scroll-geometry API](https://developer.apple.com/documentation/swiftui/view/onscrollgeometrychange(for:of:action:)), and does not replace screen gestures. Keep the callback installed when the preference changes to preserve view identity.

Selected-day Home links use `sleepDetailForDay(dayKey:)`, `recoveryDetailForDay(dayKey:)`, and `strainDetailForDay(dayKey:effortOverride:windowDayKey:)`. Strain carries its calendar-window anchor independently from its displayed row key; `windowDayKey` defaults to nil for existing callers. Sleep tiles in both Classic and Liquid use the same value route as the Home dial and My Day row.
