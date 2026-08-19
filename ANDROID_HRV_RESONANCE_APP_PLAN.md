# Android HRV-Adaptive Resonance Breathing App Plan

Status: implementation-ready product and engineering plan
Prepared: 2026-08-12
Primary input: `hrv_resonance_breathing_complete_chat_guide.md`

## 1. Outcome

Build a native Android wellness app that:

1. Connects directly to a Polar H9 chest strap over Bluetooth Low Energy (BLE).
2. Receives live heart rate and beat-to-beat R-R intervals.
3. Guides breathing with a simple visual, sound, and optional haptic pacer.
4. Measures how strongly and regularly heart rate oscillates at the instructed breathing rate.
5. Slowly adjusts the breathing rate toward the user's best observed resonance range.
6. Saves raw measurements, controller decisions, session summaries, and trends locally.

The app should reproduce the useful behavior of Ohm—a calm, adaptive, closed-loop breathing guide—not its branding, visuals, copy, proprietary algorithm, or hardware experience.

## 2. Important feasibility conclusion

This is feasible with the Polar H9.

Polar documents that the H9 provides heart rate in beats per minute and R-R intervals in milliseconds through the standard BLE Heart Rate service. It does not support Polar Measurement Data (PMD) streaming such as raw ECG. Raw ECG is not required for this product because the adaptive signal can be computed from cleaned R-R intervals.

The H9 does **not** measure respiration. The app knows the breathing cue it displayed, but it cannot prove that the user followed it. It can only infer likely coupling when the heart-rate wave becomes large, smooth, and concentrated near the cue frequency. Product copy and the UI must preserve this distinction.

A brand-new user's resonance frequency also cannot be searched reliably across the full range in a three-minute session. At 5–6 breaths/minute, three minutes contain only about 15–18 breathing cycles. The product should therefore combine:

- a deliberate initial calibration across several fixed rates; and
- later short sessions that fine-tune near the user's previously learned rate.

## 3. Product boundaries

### In the first releasable version

- Android phone app, portrait-first.
- Polar H9 support; H10 should work through the same standard HR service but is secondary.
- Device discovery, explicit selection, saved-device reconnect, live HR, R-R capture, contact/signal status, and battery level where available.
- Fixed-rate breathing sessions.
- Guided resonance calibration.
- Conservative adaptive sessions.
- Visual pacer, optional sound, and optional phone vibration.
- Local session history, trends, raw-data export, and delete-all-data.
- No account and no server.

### Deliberately out of scope initially

- Diagnosing stress, arrhythmias, cardiovascular disease, or mental-health conditions.
- Claiming clinical equivalence to Ohm or to clinician-guided resonance-frequency assessment.
- Inferring exact inhale/exhale timing without a respiration sensor.
- Smartwatch/Wear OS support.
- Apple/iOS support.
- Social features, coaching content, subscriptions, or a cloud backend.
- Google Health Connect writes until the core measurement pipeline is validated.
- Background sessions with the screen locked. Keep the session screen awake; if the app leaves the foreground, pause safely and make the state explicit.

## 4. Primary user flows

### A. First run and sensor setup

1. Explain that the app reads heartbeats from a chest strap and is not a medical device.
2. Request Nearby Devices permission only when the user chooses **Connect sensor**.
3. Instruct the user to wet the electrodes, wear the strap, and close other apps using the H9 Bluetooth connection.
4. Scan for compatible heart-rate devices and show name, device ID, and signal strength.
5. Connect to the selected H9 and verify that R-R intervals—not only average HR—are arriving.
6. Save the selected device ID for explicit reconnect on the next launch.

The H9 does not provide H10's optional dual-Bluetooth mode, so connection troubleshooting must tell users to disconnect Polar Flow, Elite HRV, or another active BLE receiver before retrying.

### B. Fixed session

1. Choose duration, breathing rate, and inhale/exhale ratio.
2. Show a 30-second settle-in period while validating signal quality.
3. Run a continuous pacer with live HR and a subtle quality indicator.
4. Pause pace evaluation on poor signal; do not turn artifacts into positive feedback.
5. End with an ease score and optional symptom flags.
6. Show a restrained summary: duration, followed rate, HR range, usable-data percentage, artifact-corrected RMSSD, lnRMSSD-based HRV score, oscillation amplitude, regularity, and resonance score.

### C. Initial calibration

Use the protocol in the source guide as the transparent baseline:

- 7.0 breaths/minute: 4.3 seconds in / 4.3 seconds out
- 6.5 breaths/minute: 4.6 / 4.6
- 6.0 breaths/minute: 5.0 / 5.0
- 5.5 breaths/minute: approximately 5.45 / 5.45
- 5.0 breaths/minute: 6.0 / 6.0

For each candidate:

1. Breathe for at least two minutes.
2. Rest naturally for at least two minutes.
3. Rate ease from 1–5 and report dizziness, tingling, pounding heart, air hunger, or discomfort.

Calibration may be completed in one longer sitting or split across comparable days. Rank candidates using signal-qualified heart-rate oscillation amplitude and regularity plus the user's ease score. If two candidates are effectively tied, prefer comfort, then the slower rate. Offer a later optional comparison of the top two with a 40/60 inhale/exhale split.

### D. Adaptive session

1. Start at the user's best established rate, or 6.0 breaths/minute before calibration.
2. Collect a settle-in window before scoring.
3. Explore only a small neighboring rate.
4. Hold the rate long enough to observe several complete cycles.
5. Keep the change only when signal quality and improvement confidence are sufficient.
6. Otherwise return to or hold the established rate.
7. Show **Finding your rhythm**, **Holding steady**, or **Signal interrupted** rather than exposing unstable numeric decisions during the session.

The app should never speed up or slow down breath-by-breath in response to a single R-R interval.

## 5. Recommended technical stack

- Kotlin.
- Jetpack Compose and Material 3.
- Coroutines and `StateFlow` at app boundaries.
- Room for structured local data.
- DataStore for settings and the saved device ID.
- A small charting implementation using Compose Canvas; avoid a large chart dependency for one waveform.
- Gradle version catalog with pinned dependency versions.
- JUnit plus Kotlin coroutine test tools for deterministic unit tests.

### Heart-rate sensor integration decision

Use Android's native Bluetooth GATT APIs behind an app-owned `HeartRateSensor` interface. Scan for the standard Heart Rate Service (`0x180D`), subscribe to Heart Rate Measurement (`0x2A37`), and parse every R-R interval in each notification. The Polar H9 is the primary test sensor, but the transport intentionally follows the Bluetooth standard rather than depending on Polar's SDK.

Benefits:

- The same interoperability path used by Elite HRV and other standards-based HRV applications.
- No vendor SDK channel or ReactiveX bridge between Android GATT callbacks and the app.
- Smaller release artifacts and direct visibility into Android connection status codes.
- Compatibility with other straps that expose accurate R-R intervals through `0x2A37`.

Costs:

- GATT operation ordering, stale callbacks, reconnect timing, notification subscription, and payload parsing are app responsibilities.
- Physical-device testing is required because emulators cannot validate BLE sensor behavior.

`StandardBleHeartRateSensor` exposes only coroutine `Flow` and domain models to the rest of the app. It always closes a stale `BluetoothGatt` before opening a new one, declares readiness only after real samples arrive, and uses a bounded watchdog to rebuild the connection when notifications stop.

## 6. Architecture

A single application module is sufficient initially, organized by explicit package boundaries. Split Gradle modules only when the boundaries produce a real build or reuse benefit.

```text
UI (Compose screens and pacer)
    |
Session coordinator (lifecycle and state machines)
    |-----------------------------|
HeartRateSensor                    AdaptiveBreathingController
(standard BLE GATT adapter)        (pure Kotlin)
    |                              |
RR quality and signal pipeline ----|
    |
Room repositories and exports
```

Recommended packages:

```text
app/
  sensor/       HeartRateSensor, Polar adapter, discovery/reconnect state
  signal/       RR validation, artifact flags, resampling, HRV and resonance metrics
  controller/   calibration ranking, adaptive policy, confidence and safety gates
  session/      session state machine, timestamps, pacer schedule, event logging
  data/         Room entities, repositories, DataStore settings, CSV/JSON export
  ui/           setup, home, session, summary, history, settings
  guidance/     audio and haptic cue engines
```

Core interfaces should look conceptually like:

```kotlin
interface HeartRateSensor {
    val connectionState: StateFlow<SensorConnectionState>
    val samples: Flow<HeartRateSample>
    suspend fun scan(): Flow<SensorDevice>
    suspend fun connect(deviceId: String)
    suspend fun disconnect()
}

interface ResonanceAnalyzer {
    fun analyze(window: List<RrSample>, cue: BreathingCue): ResonanceObservation
}

interface AdaptiveBreathingController {
    fun next(
        state: ControllerState,
        observation: ResonanceObservation,
        userFeedback: UserFeedback?
    ): ControllerDecision
}
```

The controller and analyzer must not depend on Android, Bluetooth, Room, or wall-clock time. Pass a monotonic timestamp into samples and decisions so recorded sessions can be replayed exactly in tests.

## 7. Runtime state machines

### Sensor connection

```text
Disconnected -> Scanning -> Connecting -> Connected/Streaming
                                      -> Recovering -> Connected/Streaming
                                      -> Failed
```

Rules:

- Never silently connect to the first nearby strap on initial setup.
- Reconnect only to the saved device ID.
- Use bounded exponential backoff during an active session.
- Preserve the session timeline across a short dropout but mark the gap unusable.
- After a longer dropout, pause guidance and require a stable signal before resuming evaluation.

### Session

```text
Idle -> Preparing -> Settling -> Guiding/Evaluating -> Cooldown -> Complete
                             \-> SignalPaused -------^
                             \-> UserPaused ---------^
```

### Controller

```text
Unknown -> Holding -> ExploringFaster/ExploringSlower -> Accepting/Reverting
                 \-> LowConfidenceHold
```

Every transition should be persisted as an event with its reason. This is essential for debugging and validating the adaptive behavior.

## 8. R-R and signal-processing pipeline

### Capture

For every BLE heart-rate notification, store:

- monotonic receive timestamp;
- wall-clock timestamp for export only;
- heart rate in bpm;
- all `rrsMs` values in the notification;
- `rrAvailable`;
- contact status if supported;
- connection/device ID;
- current pacer phase and commanded breathing rate.

A notification can contain more than one R-R interval. Never assume one notification equals one heartbeat. Construct beat timestamps by accumulating R-R durations against a monotonic anchor, while retaining receive time for diagnostics.

### Validation and artifact handling

Classify rather than erase raw data:

1. Reject impossible or missing values using a broad physiological range.
2. Compare each interval with a robust local median and flag abrupt deviations, including likely missed/double beats.
3. Track contact loss and BLE gaps.
4. Keep the original R-R value, correction flag, and corrected analysis value separately.
5. Use short interpolation only for analysis when the artifact rate is below the configured quality threshold.
6. Mark the entire scoring window low confidence when artifact/gap thresholds are exceeded.

Artifact thresholds must be configuration values backed by tests and recorded in the exported analysis version. They should not be casually tuned against one person's data.

### Derived signals

From qualified R-R intervals:

- instantaneous HR = `60,000 / RR_ms`;
- resample the uneven heart-rate series onto a 4 Hz timeline for spectral and waveform analysis;
- remove the local mean or linear trend within each evaluation window;
- retain raw RMSSD and SDNN as descriptive session metrics, not the controller's primary target.

### Resonance observation

For each evaluation window, compute:

1. **Usable-data fraction** — proportion not lost to artifacts, contact loss, or BLE gaps.
2. **Target-frequency amplitude** — amplitude of a sine/cosine regression at the commanded breathing frequency.
3. **Wave regularity** — goodness of fit after allowing a physiologically plausible phase delay.
4. **Spectral concentration** — power near the commanded frequency divided by relevant total power.
5. **Peak-frequency error** — distance between the dominant slow oscillation and the commanded frequency.
6. **Peak-to-trough HR amplitude** — a user-readable secondary measure.
7. **Confidence** — a combined measure of sample count, complete breathing cycles, stationarity, artifacts, and agreement among metrics.

Do not maximize RMSSD alone. Artifacts and non-respiratory variation can inflate it, and paced breathing can raise session HRV without implying a better resting baseline.

### Score design

Use a versioned composite score for ranking, not a claim of clinical coherence:

```text
resonance score =
    amplitude component
  + regularity component
  + target-frequency concentration component
  - artifact and frequency-error penalties
```

Normalize amplitude against the user's own recent qualified sessions so the score does not reward naturally high-HRV users or punish naturally low-HRV users. Display confidence separately; never turn low confidence into a low wellness score.

The weights and normalization must live in a versioned `AnalysisConfig`, be written into each session record, and be validated with replay data before closed-loop use.

## 9. Adaptive controller policy

Use a conservative, bounded search rather than an opaque machine-learning model for the first release.

Suggested initial policy:

- Allowed range: 4.5–7.0 breaths/minute.
- Start: calibrated best rate; otherwise 6.0.
- Exploration step: 0.2 breaths/minute initially, optionally 0.1 near a stable optimum.
- Settle after a pace change before evaluating.
- Evaluate only after enough qualified data and complete breathing cycles have accumulated.
- Make at most one small pace change per evaluation interval.
- Require a minimum improvement margin and confidence threshold before accepting a new rate.
- Use hysteresis so noisy adjacent observations do not cause oscillation.
- Revert after poor signal, discomfort, or a confidently worse response.
- Persist a per-user prior across sessions, but allow slow decay/recalibration because resonance may vary with context and time.

The actual settling/window durations, improvement margin, and confidence threshold must be chosen from replay experiments and physical-device testing rather than guessed into production.

Keep inhale/exhale ratio separate from rate optimization. First find a useful rate with a 50/50 split. Then let the user test 40/60 at the best one or two rates. Do not let the controller change rate and ratio simultaneously because the response would become uninterpretable.

### Safety gates

The controller must hold the current rate and suppress positive feedback when:

- R-R data is unavailable;
- contact is lost;
- usable-data percentage is below threshold;
- a BLE gap crosses the evaluation window;
- the user pauses, moves excessively, or reports discomfort;
- HR or R-R patterns are outside conservative app limits;
- too few complete cycles exist to compare rates.

Stop the session and show plain guidance if the user reports chest pain, fainting, significant shortness of breath, or an unusual/persistent pounding or irregular heartbeat. For dizziness, tingling, or air hunger, stop paced guidance, return to comfortable natural breathing, and advise reducing breath depth next time. The app must not diagnose the cause.

## 10. Data model and privacy

Suggested entities:

- `SensorDevice`: device ID, display name, model, last connected, firmware if available.
- `Session`: type, start/end, status, analysis version, selected ratio, user ease, notes, summary metrics.
- `RrSample`: session ID, monotonic offset, raw RR, analysis RR, quality flags, HR, contact state.
- `BreathingSegment`: start/end offsets, commanded rate, inhale ratio, controller state.
- `AnalysisWindow`: interval, metrics, score, confidence, accepted/rejected reason.
- `SessionEvent`: connection, signal, pause, symptom, and controller transitions.

Privacy defaults:

- Store everything locally in app-private storage.
- No login, advertising identifier, third-party analytics, or health-data upload in the MVP.
- Provide explicit CSV/JSON export, per-session deletion, and delete-all-data.
- Exports must identify raw versus corrected R-R values and the analysis algorithm version.
- Add Health Connect only as a later opt-in feature and write records after a completed session, not continuously.
- Complete Play Store Data safety and health-app declarations before distribution.

## 11. UX principles

- The breathing pacer is the visual focus; live physiology is secondary and should not make users chase a number.
- Animate from a monotonic session clock, not chained animation callbacks, so the pacer does not drift.
- Maintain phase continuity when rate changes; ease into the next cycle rather than jumping mid-breath.
- Use accessible color contrast and never rely on color alone for inhale, exhale, connection, or signal state.
- Make sound and haptics optional and test them for phase accuracy.
- Keep numerical scores out of the active breathing focal area.
- Say **resonance response** or **coupling estimate**, not **nervous system is balanced**.
- Clearly separate natural-breathing baseline measurements from paced-breathing session measurements.

Minimum screens:

1. Onboarding and safety.
2. Sensor setup/troubleshooting.
3. Home: quick session, fixed session, calibration, history.
4. Active session.
5. Ease/symptom check-in.
6. Session summary.
7. History/trends.
8. Settings, data export, and deletion.

## 12. Implementation phases and gates

### Phase 0 — Project skeleton and algorithm harness (2–3 days)

Deliver:

- Kotlin/Compose project, CI, package boundaries, dependency pinning.
- Pure Kotlin models for R-R samples, cues, observations, and controller decisions.
- A replay command/test harness that consumes timestamped R-R fixture files.
- Synthetic signal generator for known breathing frequencies, amplitudes, drift, gaps, and artifacts.

Gate:

- The same fixture produces byte-for-byte equivalent analysis/controller decisions across repeated runs.

### Phase 1 — Polar H9 vertical slice (3–5 days)

Deliver:

- Permission flow for Android 12+ Nearby Devices and legacy behavior if the chosen minimum SDK requires it.
- Explicit device scan, connect, saved-device reconnect, disconnect, and bounded recovery.
- Live HR, R-R intervals, contact/signal state, and raw export.
- An on-device diagnostic screen with notification count, R-R count, gaps, and artifact flags.

Gate:

- A physical H9 produces continuous R-R data for a 20-minute seated session on at least two Android phone models.
- Airplane/Bluetooth toggles, permission denial, app backgrounding, sensor removal, and competing-connection cases fail clearly without crashes or invented samples.

### Phase 2 — Fixed breathing MVP (4–6 days)

Deliver:

- Drift-free visual pacer with fixed rate and 50/50 or 40/60 ratio.
- Optional sound and haptic guidance.
- Session state machine, keep-screen-awake behavior, local persistence, ease/symptom feedback, summary, and export.
- Live quality indicator and poor-signal pause.

Gate:

- A 20-minute session has no cumulative cue drift beyond the chosen timing tolerance.
- Process recreation, rotation, audio interruption, and temporary BLE dropout have defined, tested outcomes.

### Phase 3 — Signal analysis and calibration (1–2 weeks)

Deliver:

- Versioned artifact detection and analysis pipeline.
- Synthetic and recorded-fixture tests for amplitude, target frequency, regularity, spectral concentration, RMSSD, and SDNN.
- Guided five-rate calibration, split-across-days support, candidate ranking, and confidence reporting.
- Session waveform and export suitable for comparison with an external HRV tool.

Gate:

- Known synthetic frequencies are identified within an agreed tolerance.
- Adding isolated missed/double beats does not falsely improve the score.
- Low-quality windows are rejected rather than ranked.
- Replayed real sessions reproduce stored summaries within tolerance.

### Phase 4 — Adaptive controller in shadow mode (1 week plus data collection)

Deliver:

- Bounded hill-climb controller with settling, hysteresis, confidence gates, and per-user prior.
- Controller decisions logged while the visible pacer remains fixed.
- Offline reports comparing recommendations with manual calibration results and ease scores.

Gate:

- The controller does not recommend changes during poor signal.
- It converges on known optima in synthetic/replay scenarios without rate ping-pong.
- Shadow recommendations are directionally consistent with repeated controlled calibration sessions often enough to meet a predeclared acceptance target.

Do not skip shadow mode. It is the main protection against shipping an adaptive feature that merely follows noise.

### Phase 5 — Closed-loop adaptive beta (1–2 weeks)

Deliver:

- Visible adaptive pacing for opted-in testers.
- Smooth pace transitions, explanations, fallback to fixed pace, and complete decision telemetry stored locally/exportable.
- Calibration refresh and manual override.

Gate:

- No safety or signal gate is bypassed in fault-injection tests.
- Physical H9 tests cover multiple heart rates, strap qualities, phone models, and session lengths.
- Repeated user sessions show at least non-inferiority to fixed/calibrated pacing on comfort and qualified resonance metrics before making stronger product claims.

### Phase 6 — Release hardening (1 week)

Deliver:

- Accessibility pass, battery/performance profiling, privacy copy, data deletion/export verification, crash handling, Play declarations, and support guide.
- Signed internal/beta build and a repeatable release checklist.

Gate:

- Unit, replay, instrumentation, UI, and physical-device acceptance suites pass.
- A fresh-install end-to-end test succeeds without developer tools.
- Wellness claims and safety language receive appropriate review before public distribution.

### Rough schedule

One experienced Android engineer can reach a fixed-pacer H9 MVP in roughly 2–3 weeks and a defensible adaptive beta in roughly 6–9 weeks. Scientific validation and public claims are a separate workstream and should not be compressed into the engineering estimate.

## 13. Test strategy

### Unit and property tests

- BLE notification containing zero, one, or multiple R-R intervals.
- Beat timestamp reconstruction.
- Physiological bounds, local-median artifact flags, interpolation limits, and gap handling.
- RMSSD/SDNN against hand-calculated fixtures.
- Frequency/amplitude estimates against generated sinusoids.
- Controller range, step-size, hysteresis, settling, and confidence invariants.
- Pacer phase continuity and duration calculations for 5.0, 5.5, 6.0, 6.5, and 7.0 breaths/minute.

### Replay tests

- Clean 5–7 breaths/minute sessions.
- Natural breathing, flat response, drift, ectopic-like intervals, missed/double beats, and disconnections.
- Same physiology with different overall HR and HRV scale.
- Candidate order reversed to reveal time/order bias.
- Golden output for every analysis version.

### Android tests

- Permission granted, denied, denied permanently, and Bluetooth off.
- Multiple nearby straps and reconnect to the saved ID only.
- Another app occupying the H9 connection.
- Rotation, process recreation, navigation away, incoming audio focus changes, and screen timeout.
- Room migration, export, per-session delete, and delete-all-data.
- Accessibility with large font, TalkBack, reduced motion, and color-blind-safe states.

### Physical validation protocol

- Test at least two supported Android versions and two manufacturers.
- Record simultaneous app export and a trusted comparison recording where the H9 connection topology permits it; otherwise repeat a standardized protocol.
- Compare raw R-R sequences and summary metrics, not only displayed HR.
- Repeat candidate rates across days and alternate their order.
- Record posture, time, caffeine, exercise, sleep, comfort, and artifacts.
- Treat disagreement or insufficient signal as inconclusive, not as evidence that the adaptive choice is correct.

## 14. Definition of done for the requested app

The product is not done merely when a phone displays heart rate. It is done when all of these are demonstrated:

- A fresh install can discover, select, connect, disconnect, and reconnect to a physical Polar H9.
- The app proves R-R availability and preserves raw R-R data with reliable monotonic timing.
- Fixed pacing is smooth, correctly timed, and usable with visual-only, audio, or haptic guidance.
- Signal artifacts and BLE gaps cannot produce false positive resonance feedback.
- Calibration ranks rates using documented metrics plus comfort, with confidence and repeatability checks.
- Adaptive pacing changes slowly, stays within bounds, observes settling windows, uses hysteresis, and falls back safely.
- Every adaptive decision can be reproduced from an exported session.
- Local history, export, per-session deletion, and complete deletion work.
- Required automated, replay, Android, and physical-H9 tests pass.
- Product language remains in the wellness/biofeedback scope and does not make unsupported diagnostic or clinical claims.

## 15. First implementation backlog

Create these issues in order:

1. Bootstrap the Android project and deterministic signal replay harness.
2. Define domain models and the `HeartRateSensor` abstraction.
3. Integrate standard BLE Heart Rate Service notifications and show device discovery/connection state.
4. Capture and export every H9 R-R interval with quality metadata.
5. Build a diagnostic 20-minute H9 soak test.
6. Implement the monotonic fixed breathing pacer.
7. Add session state, local persistence, summary, export, and deletion.
8. Implement artifact classification and quality gates.
9. Implement target-frequency amplitude, regularity, spectral concentration, and confidence.
10. Build the guided five-rate calibration flow.
11. Add calibration ranking with ease as an explicit input.
12. Implement and test the adaptive policy against synthetic and replayed sessions.
13. Run the controller in shadow mode and set acceptance thresholds before closed-loop beta.
14. Enable adaptive pacing for opt-in testing, then harden for release.

## 16. Sources checked

- Local HRV and resonance-breathing guide: `hrv_resonance_breathing_complete_chat_guide.md`
- Elite HRV standard BLE service/characteristic documentation: https://help.elitehrv.com/article/378-connecting-a-3rd-party-device-to-elite-hrv
- Bluetooth SIG Heart Rate Service specification: https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/HRS_v1.0/out/en/index-en.html
- Android GATT connection guide: https://developer.android.com/develop/connectivity/bluetooth/ble/connect-gatt-server
- Android GATT notification guide: https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data
- Polar H9 manual: https://support.polar.com/e_manuals/h9-heart-rate-sensor/polar-h9-user-manual-english/manual.pdf
- Android Bluetooth permissions: https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- Android Health Connect write guidance: https://developer.android.com/health-and-fitness/health-connect/write-data
- Ohm's public product behavior: https://ohm.health/experience and https://ohm.health/support
- Shaffer and Meehan, practical resonance-frequency assessment guide: https://pmc.ncbi.nlm.nih.gov/articles/PMC7578229/
- Lehrer and Gevirtz, HRV biofeedback mechanisms: https://pmc.ncbi.nlm.nih.gov/articles/PMC4104929/
- Paced-breathing and hyperventilation considerations: https://pmc.ncbi.nlm.nih.gov/articles/PMC6586331/
