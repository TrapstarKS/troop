# WHOOP family detection and defaults

Settings identifies the registry's active strap on launch, pairing, activation, and model correction.
Positive WHOOP model evidence is required before calling `DeviceFamily.forRegistryModel`; its legacy
unknown-model fallback is retained for arithmetic and is not evidence that a device is WHOOP 5.
MG and plain 5.0 share a protocol family. ECG requires positive MG Device Information Service attestation;
the combined wizard label alone does not prove that electrodes exist.

The only newly enabled preference is passive history capture. Existing persisted values, including
OFF, count as user choices even on installations predating the touched marker. A user toggle sets
`noopPuffinCapture.userTouched`. The once-per-strap ledger is a sorted JSON string at
`noop.whoopFamilyDefaults.appliedStrapIds`; the active effective family is an integer (0, 4, 5) at
`noop.whoopFamilyDefaults.activeFamily`. These names and value types match on Apple and Android.
Switching to 4.0 or an unidentified/non-WHOOP device disables effective capture without erasing the
user's preference. Returning to a processed strap does not apply defaults again.

These device/install-specific keys do not cross `.noopbak`, whose whitelist remains unchanged.
Android migrates the legacy `noopWhoop5Capture` value, including explicit false, into the shared
`noopPuffinCapture` key before applying defaults. No strap-write permission participates in the
automatic policy, and applying defaults sends no Bluetooth command.

## Feature inventory

Apple preferences live in `UserDefaults.standard`. Unless noted, Android preferences live in
SharedPreferences `noop_experiments`. The initial defaults below describe an unset preference;
saved user choices take precedence. iOS and macOS share the Apple implementation.

| Feature / key | Initial default | Handling | Effect on the strap or local data |
|---|---|---|---|
| Passive history/protocol trace — `noopPuffinCapture` | Off before detection; on for an untouched confirmed 5/MG | Automatic, once per active strap id | Records arriving frames into bounded local files. Does not request sensors. Effective only on 5/MG. |
| Normal skin temperature, DSP/aux decoding, step inputs | Available when packets exist | Already automatic | Decodes ordinary history; no feature-enable preference or additional command. Family-specific temperature scaling uses the canonical resolver. |
| Protocol probes / 5/MG firmware-alarm permission — `noopPuffinExperiments` | Off | Optional card, confirmation | Permits experimental strap-alarm writes, including saved alarm configuration after bonding. |
| Broadcast HR — `noopBroadcastHr` | Off | Optional card, confirmation | Writes reversible broadcast configuration; reapplies the opted-in value after reconnect. Also supported on 4.0 by its existing command. |
| Legacy R22 flags — `noopWhoop5DeepData` | Off | Optional card, confirmation | Sends sixteen persistent feature-flag writes only from the enable action. Clear remains available independently of the opt-in. Not needed for normal sync or the collector. |
| MG ECG raw-data gate — `noopEcgRawDataGate` | Off | Optional card, confirmation, MG attestation | Writes persistent `enable_raw_data_w_ecg` and reads it back. |
| MG ECG session controls — `noopWhoop5Ecg` | Off | Optional card, existing session confirmation, MG attestation | Sends Labrador session controls. Wrist selection is another explicit strap write; stopping remains possible after disabling the opt-in. |
| Bounded Raw Data Collector | Inactive; session files store metadata | Optional card opens collector; start confirmation | Sends reversible raw-data/IMU sensor commands for a bounded session. Restores only previously requested active sessions belonging to the active registry strap. |
| Explicit OS bonding — `noopWhoop5ExplicitBond` | Off | Android optional card, confirmation | May call `createBond` on the next connection. Platform-specific; Apple has no equivalent control. |
| Unbonded offload experiment — `noopWhoop5UnbondedOffload` | Off | Android optional card, confirmation | Subscribes, reads the clock, then may set the clock and request history. Enabling clears persisted refusal/silence budgets. |
| Clear stale OS pairing — `noopWhoop5ClearStaleBond` | Off | Android optional card, confirmation | May call `removeBond` after repeated failures. Apple cannot safely identify/remove an OS pairing this way. |
| Hello despite refusal — `noopWhoop5HelloDespiteRefusal` | Off | Android research preference; no automatic enable | Overrides handshake suppression and can recreate a reconnect/drop loop. No current rendered Test Centre control. |
| PPG HR sub-lag estimate — `noopPpgHrSubLagInterp` | Off | Existing research opt-in | Local v26 gap-fill HR estimation changes inferred stored HR and may affect analytics. Family identity is not validation of an experimental algorithm. |
| SpO₂ candidate display — `noopSpo2CandidateDisplay` | Off | Existing research opt-in | Local WHOOP5/Oura candidate display, explicitly unvalidated; remains off under the derived-biosignal rule. Android stores this in `noop_prefs`. Never feeds recovery/illness gates. |
| Skin-temperature display — `units.skinTempDisplay` | Absolute | Existing unit choice | Generic local presentation; no enable gate or strap command. Android stores this in `noop_prefs`. |
| Motion-aware wake — `noopMotionAwareWake` | Off | Existing research opt-in | Local staging refinement, gated by observed sensor density rather than model. Not a 5/MG-only permission. |
| HRV readiness — `noopHrvReadiness` | Off | Existing research opt-in | Read-only Test Centre tier display; model-agnostic. |
| Sleep staging V2 — `noopExperimentalSleepV2` | On | Already enabled | Validated local analysis engine; model-agnostic. |
| Continuous HRV — `noopContinuousHrv` | Off | Existing streaming opt-in | Requests realtime streaming, so it is not a local-only feature. Overnight restriction `noopContinuousHrvOvernightOnly` defaults on; both platforms store these in their normal app preferences. |
| Test Centre domain modes / `requires5MG` | Off | Existing diagnostic opt-ins | Every currently shipped domain mode has `requires5MG=false`; filtering does not itself enable a feature. |
| Android fast history / fast PHY | Off | Existing connection opt-ins | Generic radio experiments, not 5/MG-only; remain explicit choices. |

## Implementation and verification

The pure decision policy lives in `Packages/WhoopProtocol/Sources/WhoopProtocol/WhoopFamilyDefaults.swift` and its Android
protocol twin. The tests pin identical standalone optimized Swift output across model spellings,
brands, missing identities, already-processed identities, touched/stored combinations, and effective
capture gates. Runtime preference tests additionally cover persistence and legacy choices.

Settings and Test Centre share one optional-features card per screen, with model detection sourced
from the active registry row. A scan choice is not a detected model. Unidentified devices and legacy
`WHOOP` rows wait for validated service/model evidence. Automatic defaults do not change the
connection handshake or grant permission to R22, broadcast, ECG, alarms, or sensor starts.

Real hardware validation remains necessary for model detection during BLE discovery and every
explicit strap command. Package tests and app builds verify logic and compilation, not hardware
behavior.
