# HRV AI

Native Android HRV-adaptive resonance-breathing app for the Polar H9 chest strap.

Permanent Android application ID: `quest.byai.hrv`.

The app reads beat-to-beat R-R intervals through Polar's BLE Heart Rate service, guides slow breathing, measures the resulting heart-rate oscillation, and cautiously explores nearby breathing rates when the signal is qualified. All health data stays in the app's private local database unless the user explicitly exports a session.

See [ANDROID_HRV_RESONANCE_APP_PLAN.md](ANDROID_HRV_RESONANCE_APP_PLAN.md) for the product rationale, phased validation plan, and definition of done.

See [FEATURES.md](FEATURES.md) for the implemented feature inventory, real-time calculation behavior, and current validation boundary.

## Implemented

- Standard BLE Heart Rate Service discovery, explicit selection, saved-device reconnect, HR/R-R streaming, contact state, battery state, and error reporting, tested primarily with Polar H9.
- Elite HRV-inspired connection recovery: service-filtered scanning, a fresh native GATT connection for each attempt, stale-connection cancellation, notification readiness based on actual samples, and full GATT rebuilds when samples stop.
- Live connection diagnostics for device identity, HR/R-R counts, last values, sample age, contact, recovery attempts, GATT status, and a `BleHeartRateSensor` Logcat trail.
- Android 12+ Nearby Devices permission flow. The app currently targets Android 13 and newer (`minSdk` 33).
- Fixed, calibration, and adaptive session flows.
- Monotonic visual breathing pacer with 50/50 and 40/60 ratios, optional sound, optional haptics, and screen-awake behavior.
- R-R timestamp reconstruction for BLE notifications containing multiple intervals.
- Physiological range and local-median artifact classification, limited interpolation, contact-loss flags, and BLE-gap flags.
- 4 Hz resampling, linear detrending, target-frequency sine fit, amplitude, waveform regularity, spectral concentration, dominant frequency, RMSSD, SDNN, confidence, and versioned composite scoring.
- Bounded adaptive controller with 0.2 breaths/min exploration, 0.1 fine exploration, confidence gating, a three-point improvement margin, hysteresis through an accepted baseline, bounds of 4.5–7.0 breaths/min, and discomfort reversion.
- A 30-second settling period and 75-second evaluation window after each adaptive pace change.
- Room persistence for sessions, raw/corrected R-R values, breathing segments, analysis windows, controller reasons, ease, and symptoms.
- CSV export, individual-session deletion, delete-all-data, and no account/cloud/analytics.
- Session interruption handling: backgrounding pauses the active session and forces a new settling period when resumed; process death marks unfinished database sessions cancelled.
- Deterministic tests for known-frequency signals, mismatched cues, short windows, isolated artifacts, multi-RR BLE notifications, gaps/contact loss, controller acceptance, reversal, confidence gating, and discomfort.

## Toolchain

- JDK 17
- Android SDK 36
- Gradle 8.11.1 wrapper
- Android Gradle Plugin 8.10.1 with R8 8.13.19
- Kotlin 2.2.21
- Jetpack Compose
- Room 2.8.4
- Native Android Bluetooth LE GATT APIs

## Build and verify

```sh
# Run deterministic unit tests
./gradlew testDebugUnitTest

# Run Android lint
./gradlew lintDebug

# Build the debug APK
./gradlew assembleDebug
```

The APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

## Google Play preparation

Publication materials are in `play-store/`: English listing copy, a policy-compliant privacy policy, Play Console declarations/checklist, a 512 × 512 store icon, a 1024 × 500 feature graphic, and emulator-captured phone screenshots. The required public policy is served as a standalone document from GitHub Pages at `https://by211.github.io/hrv-ai/privacy-policy.html`; there is no product website.

Release signing can be loaded from an ignored `keystore.properties` file or the `HRV_UPLOAD_*` environment variables. The macOS scripts in `scripts/` generate a dedicated upload keystore, store its password in Keychain, and build a signed bundle without putting credentials in source control. Back up the keystore separately; without it, locally built release artifacts are intentionally unsigned and cannot be uploaded to Play.

After the upload key is configured, run `./Update-and-build.sh` from the repository root for each new Play build. It increments `versionCode` and the patch version, builds and verifies the signed AAB, copies its full path to the macOS clipboard, and opens the HRV AI Play Console dashboard. A failed build restores the previous version values.

## Physical H9 test

1. Install the debug APK on a phone running Android 13 or newer.
2. Wet both strap electrodes, attach the H9 connector, and wear it snugly.
3. Close Polar Flow, Elite HRV, or another app using the H9's Bluetooth connection. Unlike the H10, the H9 does not offer dual-BLE receiver mode.
4. Open **Polar sensor**, grant Nearby Devices access, scan, and select the device ID printed on the H9.
5. Confirm the app reports **Receiving R-R intervals** before starting a session.
6. Run a fixed two-minute 6.0 breaths/minute test, export the session, and verify that `raw_rr_ms` is populated and `quality_flags` is mostly empty.
7. Run a 10-minute adaptive session only after the fixed session is stable.

## Adaptive behavior

The app does not maximize raw RMSSD and does not react beat-by-beat. For each qualified adaptive window it combines:

- heart-rate amplitude at the commanded breathing frequency;
- sine-wave goodness of fit;
- spectral concentration near the commanded frequency;
- dominant-frequency error; and
- artifact, sample-duration, and cycle-count confidence.

It establishes the current pace as a baseline, explores one neighbor, and keeps the candidate only when its qualified score improves by at least three points. Otherwise it returns to the accepted rate, reverses direction, and uses the finer 0.1 breaths/minute step. Poor signal causes a hold, not a lower wellness score or a pace change.

The coefficients in `AnalysisConfig`, artifact thresholds in `ArtifactConfig`, and search policy in `ControllerConfig` are explicit starting values. They must be tuned only through replay and physical-device validation, not against a single person's preferred outcome.

## Current validation boundary

The project builds, lints without errors, passes its deterministic local test suite, and has been cold-launched and navigated on a Pixel 6a API 36 emulator. The standard BLE transport has also been validated on a physical Pixel 10 running Android 16 with a Polar H9: service-filtered discovery, direct GATT connection, `0x180D` service discovery, `0x2A37` notification subscription, live heart-rate and R-R delivery, contact parsing, and battery reading all succeeded. A forced Bluetooth off/on cycle stopped notifications, triggered the bounded watchdog, rebuilt the GATT connection, and automatically resumed live R-R data. Longer H9 soak testing and the adaptive policy's physiological validity still require the physical validation protocol in the implementation plan.

This is experimental wellness/biofeedback software, not a medical device. It does not measure respiration directly and must not be used to diagnose symptoms or heart rhythm conditions.
