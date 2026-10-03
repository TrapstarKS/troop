# Recovery, Strain and activity detail

The detail surfaces use the shared score dial, comparison rows, cards and semantic colors. Reference images are comparison material only; no vendor assets, copy or algorithm implementations are bundled.

Recovery uses the requested resolved day. A Home read carried from a previous night must pass that night's key and retain its date disclosure. A historical day never borrows a newer score. Missing scores remain unavailable; the resolved current day can show the existing baseline calibration count. HRV, resting heart rate and respiration compare against finite readings in the previous 30 calendar days, excluding the displayed day and future days. These simple comparison averages are distinct from the local scorer's weighted baseline. Skin temperature preserves absolute versus baseline-deviation meaning and the existing unit preference.

Recovery's whole-percent display truncates toward zero through one shared presentation helper. The scorer stores a continuous value; rounding 66.75 to 67 would otherwise show a high-band number with a moderate-band color. Truncation keeps the displayed number in the raw score's 0–33, 34–66 or 67–100 band. Stored scores, chart heights, comparisons and classifications retain their original precision. Home should use the same helper.

Strain uses the existing `UnitFormatter` display conversion: stored Effort × 21/100. This reversible change of units leaves stored values, imports and analytics untouched; decimal formatting only rounds the visible number. The score is from NOOP's local model and does not claim the official proprietary model. The suggested range reuses the existing recovery-dependent Coupled view bands. A missing recovery leaves the range unavailable. Both endpoints are included in the within-range state.

Day HR, zones and activity membership use Home's configured calendar or sleep-onset day window. Whole-day zone reads keep the 200,000-row limit used by day scoring instead of the shorter workout default. Chart lines break across absent buckets. Activity ownership follows the start timestamp in the same half-open cycle interval used by the daily workout count.

The window is resolved once from the selected logical calendar day, cycle mode, onset markers and one captured clock. The current logical day extends through the captured time, including readings after midnight before the 04:00 rollover. A historical day without a following onset ends before its next local midnight; a recorded following onset can extend beyond midnight. Calendar mode ignores onset markers. Reversed or future windows remain empty. The logical calendar anchor is passed separately from the displayed row key because Home can display the current calendar row before rollover. Twin tests pin the standalone Swift oracle, including short and long calendar days and invalid marker timestamps.

Activity HR and derived zones use existing source-aware workout reads. Imported zone distributions take precedence. Imported rows are edited through the existing manual-copy flow; local rows use the existing editor. Further details keep the existing GPS, steps and post-exercise HR recovery surfaces reachable.

Imported zone percentages require a positive finite activity duration. Otherwise the existing recorded-HR fallback supplies both the zone values and their recorded provenance. Values and their source label are resolved together, so malformed imported durations cannot relabel recorded zones as imported.

The expanded activity drill-down omits its former Effort summary. Activity's shared score dial is the single score readout in this flow, so opening further details cannot switch that score to a different preferred axis or legacy gauge.

Android detail reads collect the registry's active strap and reset the day/HR read state when it changes. Workout curves, zones and HR recovery accept that snapshot instead of the startup-only device ID; workout-list loads discard results after a source switch. Swift's repository already updates its read device ID from the registry.

`WorkoutRow.energyKcal` does not persist an active/total discriminator. Its display is therefore “Recorded energy”, with an explicit explanation. No resting energy is added and no stored row is reinterpreted.

Debug demo seeding adds a separate recent-HR function and a completed activity. In the first 45 minutes of a day, the activity belongs to the previous day. It runs only within the original empty-store demo seed, preserving the protection for real stores. The iOS direct demo names are `recovery`, `strain` and `activity`. A fresh demo fixture is required to acquire the added samples.

Apple constructors are `RecoveryDetailView(dayKey:)`, `StrainDetailView(dayKey:effortOverride:windowDayKey:)` and `ActivityDetailView(row:)`. Android equivalents are `RecoveryDetailScreen`, `StrainDetailScreen` and `ActivityDetailScreen`. Home must forward its selected date and logical window anchor; the optional effort override is the already-resolved stored-axis live read shown by Home. Apple's first detail push uses the shell owner's day-aware value route so Home tab reselection clears the bound navigation path. The shell owns route registration; these screens own no independent Home date state.
