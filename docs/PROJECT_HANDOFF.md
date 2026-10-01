# Project handoff — 1 October 2026

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
gradle testDebugUnitTest :app:assembleDebug
```

Expected APK:

`app/build/outputs/apk/debug/app-debug.apk`

The Garmin watch app still requires Garmin Connect IQ SDK / Monkey C tooling for watch-package compilation.

## Next work

1. Run Android CI and repair any compiler/API issues.
2. Download and physically install the debug APK.
3. Compile/sideload the Connect IQ watch companion.
4. Validate watch event → phone event → Garmin metrics.
5. Validate 30-minute pre-event and post-event windows on a real Android phone.
6. Add Health Connect as an optional source.
7. Add external observer nodes / Home Assistant / ESP32 ingestion.
8. Add event-to-event lag analysis and richer hypothesis management.
