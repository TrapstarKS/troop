# Local Sleep Planner and wake capabilities

The planner is an on-device estimate. It is not an implementation of WHOOP's proprietary Sleep
Planner or a prediction of clinical recovery. Goals describe attainment of Sleep Need, not the
composite Sleep Performance score: Peak uses 100%, Perform 85%, and Get By 70%.

The existing normative personal-need estimator supplies base minutes. The existing recency-weighted
Sleep Debt ledger supplies debt, including separately recorded nap repayment. Both platforms round
those inputs to whole minutes, add up to 120 debt minutes for planning, then round the chosen
percentage upward. The 120-minute bound is a local product choice; the underlying debt ledger is
unchanged. Recommended bedtime is the saved wake deadline minus this target. Wind-down is bedtime
minus the configured lead. Day-specific goals and wake overrides use the same calculation. Three
usable nights are required before the estimate is labelled ready; otherwise the usual need remains
visible as a preliminary estimate.

Displayed planning debt is the amount added to Sleep Need before applying the selected goal. The
goal percentage scales the whole estimated need, including debt. Debt guidance names Sleep Need
so the raw debt amount and target duration remain distinct.

Global goal and wake controls are labelled as defaults. The next-plan summary uses the resolved
weekday's goal and deadline, including any override, from the same snapshot as its target duration.

Runtime bedtime and reminder dates count elapsed minutes backward from the resolved wake instant,
so the target duration is preserved across daylight saving changes. A nonexistent wake time moves
forward while preserving its minute; a repeated wake time uses the later occurrence on both platforms.
Apple's [calendar matching policy](https://developer.apple.com/documentation/foundation/calendar/matchingpolicy/nexttimepreservingsmallercomponents)
and the Android calendar resolver supply those local-time rules. The timezone-free pure plan carries
ordinary clock minutes; displayed and scheduled dates use the resolved instants.

Apple time-only controls use a fixed UTC reference day to display and edit clock minutes without
normalizing them through today's daylight saving gap. This reference does not schedule an alarm;
the actual occurrence still uses the local calendar resolver. Android's time controls edit minutes
directly.

Apple one-shot notifications encode the resolved instant as UTC calendar components. A local
hour-and-minute trigger cannot distinguish two occurrences of a repeated hour. Both wake backup
and bedtime advice use the same serializer, while Android's alarm schedules already use epoch
milliseconds. Timezone changes refresh the local plan when the app runs; queued Apple requests
retain their resolved instant until that refresh, matching the strap's existing epoch alarm.

One resolved date and one clock feed each alarm summary. The selected weekdays describe the local
wake day. A skip is persisted as `yyyy-MM-dd|minute` for the resolved wake occurrence. It survives
relaunch and follows the same civil wake date after a timezone change. It does not disable future
weekdays or suppress a different time. Editing the alarm schedule clears the skip. A once-weekly
alarm searches two weeks ahead so skipping next week does not lose the following occurrence.
Skip identity uses the Gregorian date of the resolved instant in the local time zone, regardless
of the phone's preferred calendar. Pending-key decoding uses that same representation; recurrence
dates and selected weekdays retain the caller's calendar.

## Strap alarm

Only the existing curated exact-time arm/disarm commands are reused; no new BLE command is added.
Saving or skipping requires the paired strap connection. A disconnected save reports failure and
preserves the previous alarm settings. WHOOP 5/MG remains behind the existing Protocol probes gate.
Saved settings and a recorded wake command are distinct from matching readback from the active
strap. Existing diagnostic metadata records the arm invocation; it does not prove GATT delivery.
A countdown requires matching readback evidence; an unconfirmed command is labelled unconfirmed.
The 5/MG command has no dependable alarm-time readback and is not
presented as confirmed. Hardware wake reliability still requires a real strap test.

Exact time uses the selected deadline. Sleep-goal and Recovery modes describe the final hour before
that deadline. The pure policy allows early waking only after current-night sleep reaches its target
or valid current-night Recovery reaches 67%, respectively. This hardware build has no safely fresh
current-night sleep/recovery feed for those decisions, so the modes explicitly retain the exact
deadline and show that adaptive waking is unavailable. Yesterday's Recovery is never used to wake
someone early. The legacy Android phone light-sleep alarm remains reachable separately; its heuristic
is not relabelled as Recovery or wired into these modes.

The early-wake card asks whether the user is awake; it does not claim that the strap detected wake.
Its action skips this occurrence using the same save/reconnect safeguards. Strap battery below 20%
and phone battery at or below 20% produce charging advice when the alarm is enabled. These are local
warning choices, not evidence of a contemporary official-app threshold. Unknown charge remains
unknown.

## Phone and bedtime notifications

Apple schedules best-effort iPhone notification deadlines for the next 28 selected wake occurrences,
replacing stable identifiers on refresh and omitting the skipped occurrence. Opening the app refreshes
that coverage. The strap itself holds only one absolute wake time and needs app/reconnect re-arming
after a wake. iOS cannot keep this sideloaded app continuously observing overnight HR, guarantee a
loud wake, bypass Focus/silent mode, or deliver notifications after permission is denied. macOS does
not schedule the phone backup. The built-in Clock alarm remains the recommended backup.

Android retains its existing exact OS alarm at the hard deadline, independent of BLE. When the
phone alarm's actual window start matches the skipped wake, its next deadline is recomputed;
independently timed phone alarms stay scheduled. The existing haptic companion uses that same
resolved window start, including its date across midnight and daylight saving changes. An open
window advances that companion to its next selected occurrence without moving the phone's current
deadline. Android exact-alarm and notification permissions still apply;
a powered-off phone cannot deliver a phone alarm.

Bedtime/wind-down advice follows the same plan and per-day goal. Apple replaces seven upcoming
one-shot bedtime reminders on refresh, omitting the skipped occurrence; opening the app replenishes
that coverage. Advice is suppressed during the shared configured quiet hours. Wake deadlines are
exempt from advice quiet hours. The optional Sleep Debt reduction
nudge requires three usable nights and at least 60 planning debt minutes. This is newly authored local
guidance, not a claimed official WHOOP notification family. When ordinary wind-down and debt advice
both apply, one reminder includes the debt-aware suggestion instead of sending duplicates. No
notification setting enables itself silently. Permission denial is shown at the opt-in boundary.

Newly authored copy includes “Make room for sleep tonight” and the suggested-bedtime/debt guidance.
Generic labels identify the actions without copying proprietary push templates or assets. All UI
uses the shared design tokens. Demo fixtures populate local goals and estimates but never enable or
send a hardware alarm.

Refs ryanbr/noop#758, ryanbr/noop#625, ryanbr/noop#2031, ryanbr/noop#750,
ryanbr/noop#1611, ryanbr/noop#1613, ryanbr/noop#34.


Advice occurrence handling (review round 1): Apple quiet-policy setters immediately replace the pending advice family, including each of the quiet toggle, start, and end settings. Wake-alarm requests are a separate family and remain exempt. Requests carry the canonical Gregorian local wake-date/minute identity instead of queue-position identity. Foreground delivery, taps, and delivered-notification reconciliation retain observed delivery state.

Both platforms also keep the same canonical local advice queue and handled-occurrence strings (`windDown.pendingAdviceQueue`, `windDown.handledAdviceOccurrences`). Whole UTC epoch seconds are encoded as `wake-key=epoch`, one sorted record per line; handled wake keys are sorted one per line. These are device-local scheduling state, excluded from backup. The native Swift oracle pins the serializer and handling policy byte for byte in both language tests. An earlier queued advice time that has passed is conservatively considered handled, even when the OS delivery receipt is unavailable after dismissal or process termination. This avoids a second ordinary/debt reminder for the same wake after a goal or input edit; it does not claim that a notification was delivered. Pending edits before the advice time can still move the recommendation. History before the current local date is discarded, and a later wake remains eligible.
