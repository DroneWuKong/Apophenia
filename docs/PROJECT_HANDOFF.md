# Project handoff — 3 October 2026

## Goal

Apophenia is a personal observation and context recorder for things a person notices but cannot easily explain: symptoms, lights changing, sounds, device behavior, coincidences, environmental changes, or simply **THAT WAS WEIRD**.

The system should record evidence neutrally and test patterns instead of reinforcing them.

## Current Android implementation

- Kotlin / Jetpack Compose native Android app
- package: `com.dronewukong.apophenia`
- v0.3.0-preview.3
- Android min SDK 26
- explicit hardware/simulation gates
- local SQLite database
- observation types: observation, coincidence, hypothesis note, weird
- immediate timestamp-first logging
- widget and Quick Settings tile
- random control sampling
- one-to-one control matching by local four-hour block and weekday/weekend
- optional, explicitly enabled neutral check-in prompts using the same control pipeline
- phone sensors
- device context
- location/weather enrichment
- 30-minute rolling pre-event black box
- 30-minute post-event collection
- event-vs-control association engine
- JSON export
- optional, permission-gated Health Connect reads
- first-run context onboarding with visible location, notification, weather, and Health Connect status
- database schema v3 with observation origin, external-event deduplication, and explicit context phase
- application-scoped observation repository shared by UI, widget, tile, external intent, and Garmin ingest
- optional privacy-reduced Octopod home context shared by event and control captures
- expandable timeline context capsules with explicit pre/instant/post phases

## Garmin Epix Pro (Gen 2)

`garmin-epix-pro/` contains the Connect IQ companion for:
- epix2pro42mm
- epix2pro47mm
- epix2pro51mm

The watch sends:
- original watch event timestamp
- heart rate
- stress
- Body Battery
- Pulse Ox
- pressure
- temperature
- phone-connected state

A bounded watch-side pending queue preserves events during temporary disconnects.

Watch protocol v3 adds a persistent installation/sequence event ID. Android uses the Garmin origin plus this ID to suppress replay duplicates while retaining the original watch timestamp. Legacy v2 packets remain readable but cannot provide the same replay guarantee.

Protocol v3.1 keeps an event queued after BLE transport completion and removes it only after Android confirms durable SQLite storage. A lost receipt yields a safe replay that the phone deduplicates and acknowledges. Distinct watch haptics identify local capture and confirmed phone storage.

The bridge exposes connection and diagnostic state as a `StateFlow`, and Compose observes it directly. It refreshes device status before opening the watch app and accepts both flat and nested Connect IQ batch payloads. Packet parsing/ingest is separated from the Garmin SDK callback behind an injectable store, with unit coverage for timestamp preservation, metric attachment, malformed packets, nested batches, stale device status, and disconnected/replayed event deduplication.

The Android Garmin bridge uses app id:
`4f4d0f7b3d6f4b36b3e88b91129c70a2`

## Statistical rules

- Observations are evidence, not conclusions.
- Hypotheses are stored separately from observations.
- Random controls are required for comparison.
- Dense rolling samples are aggregated at event/control level so they are not treated as independent observations.
- Post-event samples are preserved for exploration but excluded from predictor calculations.
- Correlation does not establish causation.
- Effect magnitude and evidence strength are separate outputs.
- Permutation seeds and attainable p-value resolution are recorded; metric families use Benjamini-Hochberg FDR adjustment.

## Hardware boundary

`HardwareGates.kt` is the explicit boundary.

Build gates:
- `LIVE_SENSOR_CAPTURE`
- `LIVE_LOCATION_CAPTURE`
- `LIVE_ENVIRONMENT_LOOKUP`
- `LIVE_GARMIN_BRIDGE`

Runtime `SIMULATION` bypasses physical adapters while preserving storage and analysis behavior.

## Build

CI workflow: `.github/workflows/android.yml`

Expected command:

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug
```

Expected APK:

`app/build/outputs/apk/debug/app-debug.apk`

The emulator UI check is `./gradlew connectedDebugAndroidTest`. CI runs it on API 36 and uploads the debug APK.

Public project material is indexed from `README.md`. Installation, architecture, release, contribution, security, and Reddit launch documents are present. Current UI screenshots live under `docs/images/` and contain emulator/demo state only.

Garmin compilation requires Connect IQ SDK / Monkey C and a developer signing key outside the repository:

```powershell
./tools/build-garmin.ps1 -KeyPath C:/path/to/developer_key.der
```

## Validation status — 2026-10-03

- `./gradlew.bat testDebugUnitTest lintDebug :app:assembleDebug`: passed locally on JDK 17.
- Unit coverage includes observation timestamp preservation and deduplication, v2-to-v3 migration, rolling pre/post separation, control grouping and matching, prompted neutral controls, hypothesis separation, JSON export, association statistics, Garmin packet parsing/ingest/retry behavior, and the full simulation enrichment/database path.
- `./gradlew.bat connectedDebugAndroidTest`: two tests passed on an API 36.1 Android emulator; they cover the refined logging surface, **THAT WAS WEIRD**, the timeline entry, and the optional-context settings surface.
- Native Android location and notification permission prompts were exercised on the emulator. Weather returned a live Open-Meteo result after location approval.
- Health Connect's permission controller was exercised end-to-end on the emulator, including the required privacy-rationale declaration, six read permissions, and optional background access. The app returned to a connected state.
- APK produced at `app/build/outputs/apk/debug/app-debug.apk`.
- Preview.3 release artifacts are prepared locally; publish URL and checksums are recorded in the release notes after the final validation commit.
- Connect IQ SDK 9.2.0: all three Epix Pro targets compiled.
- Garmin Run No Evil: all six pending-queue/receipt tests passed on the Epix Pro 47 mm simulator.
- A freshly signed Connect IQ build was physically sideloaded and opened on an Epix Pro 51 mm running firmware 27.18. This proves installation and launch only.
- The receipt-enabled 51 mm PRG was copied into `GARMIN/Apps` on the physically attached watch. USB disconnect and launch are still required before claiming the replacement was processed.
- Watch-to-phone event delivery has not yet passed physical acceptance. The first physical attempt exposed stale Android device selection and nested-batch decoding defects; preview.3 also adds durable phone-storage receipts.
- Octopod parsing and simulation are tested; cluster reachability from the physical phone is not yet proven.

## Next work

1. Disconnect USB, open Apophenia on the watch, and confirm the v0.3 replacement launches.
2. Install the `0.3.0-preview.3` debug APK on the physical phone.
3. Validate watch event → Garmin Connect → phone observation → attached Garmin metrics → **Saved on phone** receipt.
4. Validate 30-minute pre-event/post-event capture, foreground-service survival, widget, and Quick Settings tile on a real Android phone.
5. Validate each available physical sensor and confirm unavailable values are omitted.
6. Validate Health Connect permission and data behavior on supported physical devices.
7. Validate weather enrichment with real permission/network/location conditions.
8. Merge the accepted release/documentation commits to `main` after review; the development pre-release already points to the tested branch commit.
9. Test the optional Octopod observer on the home Wi-Fi; keep future ESP32 sources separately scoped.
