# Local Plan behavior

Weekly Plan needs seven distinct completed recovery days before progress, Friday check-in or Monday recap is shown. A qualifying day has a finite recovery score in 0–100, positive finite daily sleep minutes and a persisted closed sleep session ending on that local day no later than the captured clock. This is local processing evidence; it does not claim WHOOP algorithm equivalence. Missing, nonfinite, future, invalid-date, duplicate or incomplete rows do not add qualifying days.

Journal logging remains available from Plan throughout calibration. Current-week local goals can be created or edited while calibrating; saved goals are retained when the seventh recovery becomes available.

`WeeklyPlanEligibility.resolve` is paired Swift/Kotlin. `WeeklyPlanPreferences.eligibleNotice(today:eligibility:)` / Kotlin `eligibleNotice(today, eligibility)` requires its eligibility result and returns a stable optional kind/weekStart/id. Scheduling or delivering does not dismiss a notice. Only an explicit in-app acknowledgement calls `dismiss`.

DEBUG Apple `--demo-seed --demo-screen weeklyplan --demo-plan-calibrating` and the dedicated Android Demo flavor intent extra `demo_plan_calibrating=true` project six qualifying demo rows into this screen without changing stored metrics or goals. Normal demo launches retain all seeded recoveries.

The eligibility oracle is actual standalone Swift stdout pinned verbatim in both platform tests. It covers 0/1/6/7/8 days, duplicates, missing/nonfinite/out-of-range scores, invalid/future dates, incomplete processing and a subsequent eligible refresh.
