# Contributing to Apophenia

Thanks for helping improve Apophenia. Device compatibility reports, careful bug reports, documentation fixes, focused features, and test coverage are all useful.

## Ground rules

- Preserve the timestamp-first observation pipeline.
- Keep observations separate from hypotheses and interpretation.
- Do not present an association as medical advice, diagnosis, paranormal proof, or established causation.
- Keep random controls statistically comparable with event windows.
- Do not use post-event samples as predictors of the event.
- Preserve a pure-software SIMULATION path for every physical integration.
- Omit unavailable sensor values instead of inventing defaults.
- Label evidence honestly: source review, unit test, emulator, simulator, physical phone, physical watch, or field use are different validation levels.
- Do not add analytics, telemetry, accounts, raw continuous audio, or camera capture by default.

## Before opening an issue

Search existing issues first. For a bug, include:

- app version and commit when known;
- phone model and Android version;
- LIVE or SIMULATION mode;
- exact steps and expected/actual behavior;
- whether Garmin, weather, location, or Health Connect was enabled;
- a redacted log or screenshot if it helps.

Never post real exports, precise coordinates, health records, Garmin identifiers, secrets, or other personal data in a public issue.

## Development setup

Use JDK 17 and an Android SDK containing API 37.

```powershell
./gradlew.bat testDebugUnitTest lintDebug :app:assembleDebug
```

With a running Android emulator:

```powershell
./gradlew.bat connectedDebugAndroidTest
```

The Android build must remain successful without Garmin hardware, physical sensors, GPS, external weather, or Health Connect data.

Garmin work additionally requires the Connect IQ SDK and a developer key stored outside the repository. See [docs/GARMIN_EPIX_PRO.md](docs/GARMIN_EPIX_PRO.md).

## Pull requests

Keep pull requests focused and explain:

1. the user-visible problem;
2. the behavior changed;
3. tests run and their results;
4. hardware or external-service paths affected;
5. validation that remains outstanding.

Add or update tests when changing timestamps, persistence, rolling windows, controls, statistics, permissions, packet parsing, deduplication, or simulation behavior. Update public documentation when behavior or evidence changes.

Do not commit SDK paths, signing keys, credentials, tokens, private exports, generated secrets, or build output.

## Commit style

Short conventional-style subjects are preferred:

```text
feat(android): add a context source
fix(garmin): retain queued events after retry
docs: clarify physical validation boundary
test(data): cover observation migration
```

By contributing, you agree that your contribution is licensed under this repository's MIT License.
