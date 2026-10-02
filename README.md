# Apophenia

Apophenia is an Android-first personal observation and context recorder for investigating patterns, coincidences, symptoms, environmental changes, and other things you notice in daily life.

The core principle is simple:

> **You record the event. The software records the circumstances.**

## Current scope

- One-tap **THAT WAS WEIRD** logging
- Observation, coincidence, hypothesis-note, and custom event logging
- Home-screen widget and Quick Settings tile
- Local SQLite event/context store
- Phone/tablet sensor capture
- Location and weather/environment enrichment
- Random control observations for baseline comparison
- 30-minute rolling pre-event black-box buffer
- 30-minute post-event capture
- Correlation/association analysis against controls
- JSON export
- Explicit hardware gates and full simulation mode
- Garmin Epix Pro (Gen 2) Connect IQ companion
- Direct Garmin watch-to-phone event bridge
- Watch-side pending-event queue for temporary phone disconnects

## Repository layout

- `app/` — native Android application
- `garmin-epix-pro/` — Garmin Connect IQ companion for Epix Pro (Gen 2)
- `docs/` — architecture, privacy, data model, Garmin integration, validation notes
- `tools/` — software-only domain tests
- `.github/workflows/` — Android CI build

## Important boundaries

The app is designed to test perceived patterns rather than assume they are real. Raw observations are stored separately from hypotheses and interpretation. Controls are sampled independently so an apparent relationship can be compared with ordinary baseline conditions.

The rolling black-box recorder is user-enabled and bounded. It does not silently auto-start at boot. Simulation mode exercises the same persistence/correlation pipeline without physical hardware.

## Build status

Source is configured as a native Kotlin/Android project. The Garmin companion uses Connect IQ and the Android side uses Garmin's Connect IQ Companion SDK.

On Windows with Garmin SDK Manager, the Epix Pro device definitions, Java 17,
and a development signing key installed, build all three watch sizes with:

```powershell
./tools/build-garmin.ps1 -KeyPath C:/path/to/developer_key.der
```

The command writes `.prg` files and reports SHA-256 hashes under `build/garmin/`.
Keep the signing key outside this repository.

Hardware validation remains separate from software validation. Do not treat simulator or unit-test results as proof of physical sensor/watch behavior.
