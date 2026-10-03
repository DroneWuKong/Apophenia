# Release checklist

This checklist prevents software evidence from being presented as hardware evidence.

## Code and version

- [ ] Intended changes are merged to `main`.
- [ ] `versionCode` and `versionName` are correct.
- [ ] `CHANGELOG.md` has a dated entry.
- [ ] README screenshots and behavior descriptions match the release.
- [ ] No signing keys, SDK paths, tokens, exports, or personal data are tracked.

## Android validation

- [ ] `./gradlew testDebugUnitTest lintDebug :app:assembleDebug` passes with JDK 17.
- [ ] `./gradlew connectedDebugAndroidTest` passes on a clean emulator.
- [ ] GitHub Actions build and emulator jobs are green on the release commit.
- [ ] APK exists at `app/build/outputs/apk/debug/app-debug.apk`.
- [ ] SHA-256 checksum is recorded in the release notes.
- [ ] Fresh install and upgrade behavior are tested on at least one physical phone.
- [ ] One-tap logging, timeline, widget, Quick Settings tile, export, and delete are tested physically.
- [ ] Location, weather, notification, and Health Connect denied/granted states are checked physically where available.
- [ ] The relevant phone/watch matrix completed `docs/PHYSICAL_ACCEPTANCE.md`, including the 48-hour recorder soak and Garmin offline retry.
- [ ] Rolling recorder survival and post-event collection are checked under real OEM battery management.

## Garmin validation

- [ ] All three Epix Pro targets compile with the documented Connect IQ SDK.
- [ ] Native Monkey C queue tests pass.
- [ ] Matching Garmin application ID is confirmed on both sides.
- [ ] Physical watch-to-Garmin Connect-to-Android delivery is tested.
- [ ] Watch timestamp preservation and duplicate suppression are verified.
- [ ] Offline queue, reconnect, retry, in-flight tap, and full-queue behavior are verified physically.
- [ ] Unavailable watch metrics are omitted.

Unchecked physical items must be listed as known limitations rather than implied to pass.

## Publish

- [ ] Create a signed tag such as `v0.2.1` from the accepted commit.
- [ ] Create GitHub release notes from `CHANGELOG.md`.
- [ ] Attach the APK and a checksum file.
- [ ] Mark an unvalidated build as a pre-release.
- [ ] Confirm public download links in a signed-out browser.
- [ ] Replace placeholders in [REDDIT_LAUNCH.md](REDDIT_LAUNCH.md).
- [ ] Post screenshots that match the uploaded APK.
- [ ] Watch issues for installation, permission, battery, and device-compatibility reports.

## Release-note evidence block

```text
Android build: [passed/failed] on [environment]
Unit tests: [count/result]
Emulator tests: [count/result/API]
Physical Android: [devices tested or not validated]
Garmin compile: [targets/SDK/result]
Garmin simulator: [tests/result]
Physical Garmin: [devices tested or not validated]
APK SHA-256: [checksum]
Known limitations: [list]
```
