# Project handoff — 2 October 2026

## Goal

Apophenia is a personal observation and context recorder for things a person notices but cannot easily explain: symptoms, lights changing, sounds, device behavior, coincidences, environmental changes, or simply **THAT WAS WEIRD**.

The system should record evidence neutrally and test patterns instead of reinforcing them.

## Current Android implementation

- Kotlin / Jetpack Compose native Android app
- package: `com.dronewukong.apophenia`
- v0.2.0
- Android min SDK 26
- explicit hardware/simulation gates
- local SQLite database
- observation types: observation, coincidence, hypothesis note, weird
- immediate timestamp-first logging
- widget and Quick Settings tile
- random control sampling
- phone sensors
- device context
- location/weather enrichment
- 30-minute rolling pre-event black box
- 30-minute post-event collection
- event-vs-control association engine
- JSON export
- optional, permission-gated Health Connect reads
- database schema v3 with observation origin, external-event deduplication, and explicit context phase

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

The Android Garmin bridge uses app id:
`4f4d0f7b3d6f4b36b3e88b91129c70a2`

## Statistical rules

- Observations are evidence, not conclusions.
- Hypotheses are stored separately from observations.
- Random controls are required for comparison.
- Dense rolling samples are aggregated at event/control level so they are not treated as independent observations.
- Post-event samples are preserved for exploration but excluded from predictor calculations.
- Correlation does not establish causation.

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

Garmin compilation requires Connect IQ SDK / Monkey C and a developer signing key outside the repository:

```powershell
./tools/build-garmin.ps1 -KeyPath C:/path/to/developer_key.der
```

## Validation status — 2026-10-02

- `./gradlew.bat testDebugUnitTest lintDebug :app:assembleDebug`: passed locally on JDK 17.
- Unit coverage includes observation timestamp preservation and deduplication, v2-to-v3 migration, rolling pre/post separation, control grouping, hypothesis separation, JSON export, association statistics, Garmin packet parsing, and the full simulation enrichment/database path.
- `./gradlew.bat connectedDebugAndroidTest`: passed on an API 36.1 Android emulator; launches Compose, performs **THAT WAS WEIRD**, and verifies the timeline entry.
- APK produced at `app/build/outputs/apk/debug/app-debug.apk`.
- Connect IQ SDK 9.2.0: all three Epix Pro targets compiled.
- Garmin Run No Evil: all four pending-queue tests passed on the Epix Pro 47 mm simulator.
- None of the above is physical Android or Garmin hardware validation.

## Next work

1. Download and physically install the debug APK.
2. Sideload the compiled Connect IQ watch companion using the owner's Garmin signing key.
3. Validate watch event → Garmin Connect → phone observation → attached Garmin metrics.
4. Validate 30-minute pre-event/post-event capture, foreground-service survival, widget, and Quick Settings tile on a real Android phone.
5. Validate each available physical sensor and confirm unavailable values are omitted.
6. Validate Health Connect permission and data behavior on supported physical devices.
7. Validate weather enrichment with real permission/network/location conditions.
8. Add external observer nodes / Home Assistant / ESP32 ingestion only as later scoped work.
