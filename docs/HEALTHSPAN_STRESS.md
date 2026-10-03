# Local Healthspan and Stress Monitor

Healthspan presents the existing on-device `body_age` series as **NOOP Age**. It is a wellness comparison with a ±5-year uncertainty band, not a measured biological age, a clinical risk score, or the official WHOOP calculation. `VitalityEngine` remains the single age calculation: weekly resting-HR and HRV medians, mean sleep duration and duration regularity, and mean steps contribute conservative log-hazard offsets. Its existing correlated-input shrink and age clamp remain unchanged. Fitness Age / VO₂max is a separate fitness comparison; it is not silently blended into Body Age. No database schema, stored score, import or strap command changes.

The display needs an adult profile, 21 unique recovery days in the selected preceding 31 days, and a valid Body Age sample no more than 14 days old. Insufficient or stale data shows calibration/unavailability instead of zero. The number is the last available weekly estimate, not a daily measurement. Historical selection uses the same reference date for calibration, history and contributors.

## Pace of Aging estimate

`HealthspanPresentation` provides an independent, **unvalidated local trend proxy**. A 30-day mean is compared with an up-to-180-day mean from the same stored Body Age series:

```
pace = clamp(1 + 2 * (meanAge30 - meanAge180), -1, 3)
```

The factor 2 turns a six-month difference in years into an annualized ratio. A flat age trend maps to 1×; a lower recent mean reduces the ratio. This is a visualization of the local age estimate, not a prediction of lifespan or a validated rate of biological aging. It is especially sensitive to sparse history, profile changes and estimator noise. The gate additionally requires at least 3 recent weekly samples, 8 samples in the long window and a history span of 90 days. One decimal is quantized identically in Swift and Kotlin; duplicate days use the last canonical sample, non-finite/out-of-domain/future points are discarded. No pace value is persisted or fed to Recovery, illness, notifications or other downstream decisions.

Sleep, Strain and Fitness cards describe recent context. Their numbers are not additive age-impact contributions. Strain and fitness do not feed the existing Body Age model. Unavailable lean mass/body composition is not inferred from strap data. The method sheet preserves these distinctions.

## Stress Monitor

The 0–3 gauge and trace reuse `DaytimeStress` without a scoring change. It is physiological activation relative to the local calm reference, not a mental-health diagnosis. The optional existing personal-baseline lens retains its own opt-in. The monitor never arms a realtime stream or sends a strap command. With no usable banked HR, the gauge is unavailable. A recorded daily stress point is labelled separately with its own day, and never substitutes for an intraday/live reading.

The display timeline uses overlapping one-hour windows every 30 minutes. Zone durations use **only non-overlapping hourly buckets**, clipped at the last observed timestamp; unscored and activity-masked windows do not receive a zone. These are estimates at hourly resolution, not minute-level observations. Low is below 1, medium is [1,2), high is [2,3]. Missing points break the trace. Logged sleep and activity overlays annotate real stored intervals; they do not change the score or establish psychological stress. The motion mask can only separate exertion when gravity observations exist.

Local calendar boundaries bound every raw-data read; inclusive SQL ends before the next midnight. Date selection updates the trace and duration summary together. The latest-recorded-window hero is unavailable if its window is stale on the current day. Selecting a chart point explicitly labels the selected window. The breathing action reuses the existing local Breathing/Breathe screen. Synthetic demo raw samples live only in the guarded seed functions and do not validate physiological accuracy or live hardware behavior.

## Validation contract

The pure Swift helper is compiled standalone and its stdout is copied verbatim into the Kotlin oracle test and a matching Swift test. Cases cover varying rising/flat/falling age inputs, insufficient calibration, minors, stale/invalid samples and stress threshold boundaries. Required application compiles cover shared Swift on both macOS and iOS, plus Android; demo captures are supplemental layout evidence. No physical strap verification is claimed.
