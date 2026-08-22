# HRV AI features

## Product purpose

HRV AI is an Android paced-breathing and HRV biofeedback app designed primarily for the Polar H9 chest strap. It guides slow breathing, measures beat-to-beat cardiac timing, estimates how strongly the resulting heart-rate wave follows the breathing cue, and can cautiously explore nearby breathing rates.

HRV AI is wellness and biofeedback software. It is not a medical device and does not diagnose, monitor, prevent, or treat a medical condition.

## Sensor connectivity

- Direct Android Bluetooth Low Energy connection without a vendor SDK.
- Service-filtered discovery using the standard Heart Rate Service (`0x180D`).
- Heart-rate and R-R interval notifications from Heart Rate Measurement (`0x2A37`).
- Standard Battery Service reading when the sensor exposes it.
- Explicit sensor selection and saved-device reconnect.
- Fresh GATT client for every connection attempt.
- Stale-connection cancellation before reconnecting.
- Bounded automatic recovery after a disconnect or notification stall.
- A sample watchdog that closes and rebuilds the GATT connection when heart-rate notifications stop.
- Primary physical validation with Polar H9 on a Pixel 10 running Android 16.

## Connection diagnostics

- Current connection state.
- Selected device name and Bluetooth address.
- Heart-rate notification count.
- R-R interval count.
- Latest heart rate and R-R interval.
- Age of the latest sample.
- R-R stream confirmation.
- Contact status when provided by the sensor.
- Battery level when available.
- Recovery-attempt count.
- Latest connection event, error, technical cause, and GATT status.
- Copyable diagnostic report containing app version, phone model, Android version, and sensor state.
- Dedicated `BleHeartRateSensor` Logcat tag.

## Breathing modes

### Fixed session

- User-selected constant breathing rate.
- Session duration selection.
- 50/50 or 40/60 inhale-to-exhale timing.
- Visual breathing pacer.
- Three optional peaceful sound styles with noticeably different inhale and exhale cues: relaxed breathing, ocean swell, and singing bowls.
- In-settings inhale/exhale preview whenever a sound style is selected.
- Optional three-breath preparation before timed practice.
- Optional haptic cues.

### Calibration

- Guided comparison of five breathing rates.
- Two-minute test at each rate.
- Signal-quality and resonance analysis for each test.
- User comfort/ease rating after each session.
- Results intended to establish a reasonable starting rhythm rather than claim a medical optimum.

### Adaptive session

- Starts near the saved preferred rate, normally 6.0 breaths per minute.
- Bounded exploration from 4.5 to 7.0 breaths per minute.
- Initial exploration steps of 0.2 breaths per minute.
- Fine exploration steps of 0.1 breaths per minute.
- Requires qualified signal and sufficient confidence before changing pace.
- Requires a meaningful score improvement before accepting a candidate rate.
- Reverts and reverses direction when a candidate does not improve the accepted baseline.
- Holds the current rate when data is missing, noisy, too short, or inconclusive.
- Concise controller status names the baseline rate/score, tested rate/score, and next selected rate.

## Real-time calculation and adjustment

The sensor provides the same raw R-R data needed for both Elite HRV-style lnRMSSD and HRV AI's resonance analysis. HRV AI calculates an Elite-compatible rolling HRV value alongside the existing controller without changing sensor hardware or Bluetooth transport.

Elite HRV's live display uses approximately the latest 15 seconds of R-R intervals, calculates cleaned RMSSD, applies `ln(RMSSD)`, and maps it to a 1–100 display value. That calculation is suitable as a responsive display or secondary trend.

HRV AI exposes that rolling 15-second score during active sessions. Completed sessions also store the Elite-compatible score, lnRMSSD, RMSSD, SDNN, and artifact percentage separately from the resonance score.

Breathing-rate adjustment intentionally does not happen beat by beat or every two seconds. A change in breathing pace takes time to produce a stable cardiovascular response, and a short lnRMSSD window cannot determine whether variability is synchronized with the commanded breathing frequency. The adaptive controller therefore uses:

- a 30-second settling period after starting or changing pace;
- a 75-second evaluation window;
- signal-quality and duration gates;
- heart-rate oscillation amplitude at the commanded breathing frequency;
- waveform regularity;
- spectral concentration near the commanded frequency;
- dominant-frequency error; and
- confidence and user-comfort checks.

The Elite-style live score is an additional metric and visualization. It does not replace the resonance-coupling score as the direct control signal because maximizing short-window RMSSD alone can reward unrelated variability, movement artifacts, ectopic beats, or changes that are not caused by the breathing cue.

## Signal processing

- Monotonic timestamps based on Android elapsed realtime.
- Reconstruction of timing when one BLE notification contains multiple R-R intervals.
- Physiological R-R range checks.
- Local-median abrupt-deviation detection.
- Contact-loss flags.
- BLE-gap detection.
- Limited interpolation only when artifact burden remains acceptable.
- Rejection of analysis windows with insufficient duration or usable data.
- 4 Hz heart-rate resampling for resonance analysis.
- Linear detrending.
- Target-frequency sine fitting.
- Peak-to-trough heart-rate amplitude.
- Waveform regularity.
- Spectral concentration.
- Dominant-frequency estimation and commanded-frequency error.
- RMSSD and SDNN summaries.
- Elite-compatible rolling 15-second lnRMSSD score.
- Elite-compatible completed-session HRV score and artifact summary.
- Versioned composite resonance score and confidence value.

## Session safety and lifecycle

- 30-second settling phase before adaptive evaluation.
- Active sessions pause when the app leaves the foreground.
- Returning to the foreground starts a new settling period before evaluation resumes.
- Interrupted database sessions are marked cancelled after process death.
- The screen remains awake during an active breathing session.
- Post-session prompt captures an ease rating.
- In-app guidance tells users to stop and return to natural breathing if uncomfortable.

## Local data and privacy

- No account required.
- No advertising SDK.
- No analytics SDK.
- No cloud health-data storage.
- Sessions stored in a private Room database on the device.
- Raw and corrected R-R intervals retained for inspection and export.
- Breathing segments, analysis windows, controller decisions, confidence, and user feedback stored locally.
- CSV session export initiated by the user.
- Complete-history ZIP export containing session summaries, every raw R-R sample, rolling HRV/RMSSD measurements, breathing segments, and resonance-analysis windows.
- Rolling 15-second HRV/RMSSD values persisted approximately every two seconds for raw score-history export.
- Individual-session deletion.
- Delete-all-data control.
- Public privacy policy available from the app and Play listing.

## History and results

- Local session history.
- Session status, type, duration, initial rate, and final rate.
- Average heart rate.
- RMSSD and SDNN.
- Resonance score and confidence.
- Usable-data fraction.
- Controller decision reasons.
- User comfort and symptom feedback.

## Android and release support

- Native Kotlin Android application.
- Jetpack Compose and Material 3 UI.
- Minimum Android version: Android 13 (`minSdk 33`).
- Target SDK: Android API 36.
- Separate debug application ID so physical test builds can coexist with the Play-installed release.
- Dedicated upload key stored outside the repository with its password in macOS Keychain.
- Repeatable signed Android App Bundle build script.
- Play Store listing text, icon, feature graphic, screenshots, privacy policy, and publication checklist included in the repository.

## Current validation boundary

- Deterministic unit tests cover signal analysis, controller decisions, R-R timestamp reconstruction, artifact handling, diagnostics, and standard BLE heart-rate payload parsing.
- Android lint completes without errors.
- Debug and minified release builds succeed.
- The signed release bundle verifies successfully.
- Physical Polar H9 discovery, connection, R-R streaming, battery reading, saved-device reconnect, notification watchdog, and recovery after a forced Bluetooth off/on cycle have been verified on Pixel 10/Android 16.
- Longer multi-hour BLE soak testing, broader Android-device testing, and physiological validation across multiple users remain required before treating adaptive recommendations as generally validated.
