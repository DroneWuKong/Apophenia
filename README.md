# Apophenia

**Notice now. Understand later.**

[![Android CI](https://github.com/DroneWuKong/Apophenia/actions/workflows/android.yml/badge.svg)](https://github.com/DroneWuKong/Apophenia/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Android 8+](https://img.shields.io/badge/Android-8%2B-3DDC84.svg)](https://developer.android.com/about/versions/oreo)

Apophenia is an open-source, local-first Android and Garmin observation recorder. Tap once when something stands out; the app preserves the exact timestamp first, then adds whatever phone, environment, and wearable context is available.

It is designed to investigate patterns without assuming they are meaningful. Observations stay separate from hypotheses, event windows are compared with random control windows, and the app never presents correlation as proof of medical, psychological, paranormal, or causal claims.

<p align="center">
  <img src="docs/images/apophenia-log.png" alt="Apophenia quick logging screen" width="360">
  <img src="docs/images/apophenia-settings.png" alt="Apophenia context and privacy settings" width="360">
</p>

## Why this exists

People are good at noticing unusual moments and bad at reconstructing the surrounding conditions afterward. Apophenia acts like a small personal black box:

1. You record the moment in one tap.
2. The timestamp is stored immediately.
3. Optional enrichment runs afterward, so slow hardware or network calls cannot move the event time.
4. The app compares event context with ordinary randomly sampled context.

The goal is better evidence, not a more confident story.

## What works

- One-tap **THAT WAS WEIRD** logging
- Observation, headache, sinus, light, sound, body sensation, coincidence, hypothesis, and custom entries
- Home-screen widget and Quick Settings tile
- Local SQLite storage and JSON export
- User-enabled 30-minute rolling pre-event buffer
- Approximately 30 minutes of labeled post-event context
- Random control windows using the same capture pipeline
- Phone sensors, device state, battery, screen, network, and optional location
- Optional Open-Meteo weather enrichment
- Optional, read-only Health Connect context
- Garmin Epix Pro (Gen 2) companion for 42 mm, 47 mm, and 51 mm models
- Explicit LIVE and SIMULATION modes
- Association analysis with robust summaries, permutation testing, persistence checks, and multiple-comparison caution

No account, advertising SDK, analytics, continuous microphone recording, or camera recording is included.

## Project status

Apophenia is a **development preview**, not a Play Store release and not a medical device.

| Area | Current evidence |
| --- | --- |
| Android build | JDK 17 build, unit tests, lint, and debug APK pass locally and in GitHub Actions |
| Android UI | Two Compose smoke tests pass on an API 36 emulator |
| Permissions | Location, notifications, weather, and Health Connect flows exercised in an emulator |
| Garmin app | All three Epix Pro targets compile with Connect IQ SDK 9.2.0 |
| Garmin queue | Four native Monkey C tests pass in the 47 mm simulator |
| Physical hardware | Still requires broader phone/watch acceptance testing |

See [PROJECT_HANDOFF.md](docs/PROJECT_HANDOFF.md) for the detailed evidence boundary and remaining hardware work.

## Install a development build

1. Open the latest successful [Android workflow run](https://github.com/DroneWuKong/Apophenia/actions/workflows/android.yml).
2. Download the `apophenia-debug-apk` artifact and unzip it.
3. Open `app-debug.apk` on an Android 8.0 or newer phone.
4. Allow installation from the browser or file manager when Android asks.
5. Review optional context access inside the app. Basic logging works without location, Health Connect, Garmin, or weather.

GitHub may require you to sign in before downloading an Actions artifact. Debug APK signatures can differ between build machines; if Android rejects an update, export anything you need, uninstall the old debug build, and install the new one.

Use the [install and test guide](docs/INSTALL_AND_TEST.md) for a short acceptance checklist and a safe bug-report template.

## Build the Android app

Requirements:

- JDK 17
- Android SDK with API 37 installed
- No Garmin hardware, GPS fix, weather service, or Health Connect data is required for software tests

On Windows:

```powershell
./gradlew.bat testDebugUnitTest lintDebug :app:assembleDebug
./gradlew.bat connectedDebugAndroidTest
```

On macOS or Linux:

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug
./gradlew connectedDebugAndroidTest
```

The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## How the capture pipeline works

```text
tap / widget / tile / Garmin event
              |
              v
     save exact event timestamp
              |
              +--> freeze preceding rolling window
              +--> collect available instant context
              +--> schedule labeled post-event context
              +--> store everything locally
                           |
                           v
             compare event windows with controls
```

Dense samples are grouped by capture window rather than counted as independent evidence. Post-event samples are retained for exploration but excluded as event predictors. Hypotheses are stored separately from raw observations.

Read [ARCHITECTURE.md](docs/ARCHITECTURE.md) and [DATA_MODEL.md](docs/DATA_MODEL.md) for the implementation contract.

## Garmin companion

The Connect IQ app supports:

- `epix2pro42mm`
- `epix2pro47mm`
- `epix2pro51mm`

It preserves the watch timestamp, omits unavailable metrics, and keeps a bounded pending-event queue when the phone is disconnected. Build instructions and the hardware acceptance checklist are in [GARMIN_EPIX_PRO.md](docs/GARMIN_EPIX_PRO.md).

## Privacy

Apophenia is local-first. Optional access is explicit and fail-soft:

- phone sensors that Android exposes without runtime permission;
- location for local context and optional weather lookup;
- notifications for the user-enabled foreground recorder;
- read-only Health Connect history;
- Garmin data delivered through the paired-phone companion path.

You can export or delete local data from Settings. Do not attach real exports, coordinates, or health records to public bug reports. See [PRIVACY.md](docs/PRIVACY.md).

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Data model and statistical boundaries](docs/DATA_MODEL.md)
- [Install and test guide](docs/INSTALL_AND_TEST.md)
- [Garmin Epix Pro integration](docs/GARMIN_EPIX_PRO.md)
- [Privacy and collection boundaries](docs/PRIVACY.md)
- [Project handoff and validation status](docs/PROJECT_HANDOFF.md)
- [Roadmap](ROADMAP.md)
- [Release checklist](docs/RELEASE_CHECKLIST.md)
- [Reddit launch kit](docs/REDDIT_LAUNCH.md)

## Contributing

Bug reports, device compatibility results, UI feedback, and focused pull requests are welcome. Start with [CONTRIBUTING.md](CONTRIBUTING.md). Please keep observations neutral, preserve the pure-software path, and distinguish simulator evidence from physical-hardware evidence.

## License and disclaimer

The source is available under the [MIT License](LICENSE).

Apophenia is an experimental personal data tool. It does not diagnose, treat, predict, or explain a medical or mental-health condition. Its statistical output is exploratory and may reflect chance, bias, missing data, or confounding factors.
