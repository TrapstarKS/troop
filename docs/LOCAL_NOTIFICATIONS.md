# Local notifications and More

More groups the local profile, device, app preferences, notifications, integrations, data tools and help. The feature index retains every previous mobile destination. Settings opens a task directory; All settings retains the complete form, including experiments and restore controls. Existing data-affecting explanations remain beside their switches. The provider settings have their own destination.

All new briefing and notification copy is **newly authored**. It is a clean-room local summary, not WHOOP text or a reproduction of its proprietary coaching algorithm. No server, account, push service or telemetry is introduced. The Coach destination opens the existing BYOK provider conversation when a key is saved, or the offline briefing when no key is saved. The offline destination sends no provider request. The existing Coach retains its key, data-consent and first-open/scheduled-brief generation controls; the destination chooser adds no network request.

## Delivery rules

| Family | Local rule | Deduplication |
| --- | --- | --- |
| Recovery / Sleep ready | A resolved daily reading, the canonical recorded main-night wake, completed sync and scoring | Recorded-night group coverage |
| Morning recap / Daily Outlook | Recorded wake plus at least one available Recovery or Sleep reading | Recorded-night group coverage |
| Strain ready / Day in Review | Today's saved reading, after 20:00 local time; Strain is the value saved so far | Recorded-evening group coverage |
| Streak summary | At least one consecutive recorded Recovery day, after 20:00 | Recorded-evening group coverage |
| Post-workout summary | A completed recorded activity; existing Android frontier behavior is preserved | Workout start timestamp |
| Device disconnected | A previously observed active-WHOOP connection remains absent for five minutes | Local day |
| Wear reminder | Observed active-WHOOP on-wrist → off-wrist transition remains connected for thirty minutes | Local day |
| Weekly Plan check-in / recap | Friday / Monday after 17:00, only when a saved plan provider supplies content | Family plus Monday week key |
| Battery | Existing low, full, predictive, stale and critical policies | Existing battery crossing markers |
| Charging needs attention | Unavailable: the shared charge flag and sample history cannot establish a charger fault | No alert emitted |

Charging-fault inference is deliberately unavailable: pack attachment can transiently set the shared charge flag, and the rolling samples lack per-reading charge/source provenance. The center lists this unsupported family without enabling a misleading alert. New supported families are opt-in and default off. The existing battery defaults and existing Android report preferences are preserved. No missing value is replaced with zero. Strain uses the existing presentation mapping from stored 0–100 to displayed 0–21; stored analytics remain unchanged. Main-night selection uses the same bridged sleep selector and learned midsleep as the Sleep surface, so split-night fragments do not provide an independent early wake.

Device clocks reset on active-device/family change, and wear clocks reset on disconnect and never seed from a last-known false state; an off-wrist strap after reconnect stays silent until another observed transition. The dispatcher uses one captured local clock, re-evaluates after data refreshes and every minute while the process can run, and checks OS authorization plus the existing shared quiet-hour window. Quiet hours wrap across midnight; equal start/end keeps the existing empty-window behavior. New daily events expire after 24 hours. A successful OS post advances the delivery key; denial, quiet hours and an add failure leave it eligible for re-evaluation. Apple background execution is best effort, not an exact wake guarantee. Android re-evaluation uses the retained app session and catches up when reopened; it does not promise a new background wake after the ViewModel is released. The existing alarm deadline retains its owner’s behavior.

The sleep/alarm/debt policies and Health Monitor policies remain owned by their feature modules. The center lists their canonical controls and links to those settings rather than scheduling a second implementation. Wrist mirroring and Live Activity controls remain distinct from new local reports. The provider morning brief retains its existing key/consent gates and clearly discloses that the configured provider can receive data.

## Integration boundary

`LocalNotificationDispatcher.weeklyPlanProvider` accepts an optional `WeeklyPlanNotificationProviding` on Apple and `WeeklyPlanNotificationProvider` on Android. An absent provider emits nothing; it never fabricates a saved plan. The Plan track's notice API resolves Friday's current saved week and Monday's previous saved week. Notification delivery must not acknowledge or dismiss the in-app notice.

Local notification taps carry an explicit destination (`local_briefing`, `devices`, `workouts`, or `weekly_plan`) through `localNotificationRoute`. They do not open whichever page happened to be visible last. The shell owns consumption of those route requests. Briefing taps open the offline report even when a provider is configured.

New settings use canonical `localNotifications.<family>.enabled` keys. Delivery markers use `localNotifications.<family>.lastEventKey`. These OS-delivery preferences and markers are device-local and intentionally excluded from the existing `.noopbak` whitelist; no backup schema or stored physiological data changes. Android's pre-existing morning/post-workout preferences keep compatibility aliases. The generic eligibility and quiet-hour helper is mirrored in Swift/Kotlin and checked by a compiled Swift oracle.

The existing Apple `--demo-seed` and Android DemoSeeder daily readings, sleep blocks, workout history and streak days populate these screens. They exercise presentation and local policy inputs; they do not validate Bluetooth behavior or physiological accuracy.

Apple battery delivery retains the latest attempt generation after its asynchronous OS callback finishes. An older accepted callback cannot remove a newer accepted notification under the same identifier. Android posts synchronously through NotificationManager and has no equivalent delayed delivery callback; its accepted-post markers preserve the same eligibility semantics.

## Overlapping report families

One recorded night produces at most one enabled report, in priority order: Daily Outlook, morning recap, Sleep ready, Recovery ready. One recorded evening produces at most one enabled report: Day in Review, Strain ready, streak summary. Available fields gate selection. An accepted report stores the same dated coverage for every family in its group, so changing opt-ins, app restart, or a previously delivered granular report cannot create a richer duplicate. Groups remain independent. Apple additionally holds a group/event lock while the OS post is suspended.

Android captures the active source, local clock, row, input fingerprint and scoring generation before resolving the report. Only the latest unchanged refresh may publish or post. Every daily-scoring entry goes through the shared IntelligenceEngine readiness witness. Queued, running, failed, unrelated-source or unrelated-day passes cannot release cached computed data. A cold computed row waits for a successful process-local pass. Imported readiness requires matching original fields, wake-session sources and streak provenance. These witnesses are in memory; no new schema or backup setting is introduced.

## Dated payload contract

`LocalNotificationContext` is mirrored in StrandAnalytics and Kotlin. Its flat string fields retain route, event ID, family, calendar day, optional plan week and workout start, original notice copy, and an optional immutable `LocalRecordedReport`. The report retains missing readings as absent values. The field names start with `localNotification`; `localNotificationReport=1` distinguishes a captured report from a route-only notice. Both codecs have tests against actual compiled Swift stdout.

The Apple shell attaches `NotificationPresenter.onLocalNotificationContextTapped`; a cold-start tap is held until that complete-context callback exists. Android decodes the original launch or `onNewIntent` using `localNotificationContext(intent)`. Intent data includes route and event identity, and notification tags distinguish retained dates. A later post cannot change an older PendingIntent's extras.

The shell passes report context to `LocalBriefingView(notificationContext:)` or `LocalBriefingScreen(..., notificationContext=)`. These resolve the saved report, rather than the latest database row. Saved weekly-plan or workout notices use `LocalRecordedNoticeView(notificationContext:)` or `LocalRecordedNoticeScreen(context)`, which preserve the original week/activity and copy. A missing captured report never silently resolves live data. Normal Coach/offline destinations without context continue to show current local data.

Weekly Plan delivery calls a nullable saved-plan provider. With no provider or saved copy, it remains silent. Charging-fault status remains unavailable because no reliable strap signal is exposed. No background timing guarantee or physical strap validation is claimed by these interfaces.
