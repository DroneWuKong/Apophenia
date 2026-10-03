# Install and test Apophenia

This guide is for development-preview testers. Apophenia is not yet distributed through Google Play.

## Install remotely from GitHub Actions

1. On the phone or another computer, open the [Android Actions page](https://github.com/DroneWuKong/Apophenia/actions/workflows/android.yml).
2. Open the newest green workflow run.
3. Download the `apophenia-debug-apk` artifact and unzip it.
4. Transfer `app-debug.apk` to the Android phone if necessary.
5. Open it and approve installation from that browser or file manager.

GitHub may require a signed-in account to download workflow artifacts. If Android reports that the package conflicts with an existing installation, the older APK was probably signed by a different debug key. Export any data you need, uninstall the older debug build, and install the new APK. Uninstalling deletes the app's local database.

## Five-minute acceptance test

### 1. First launch

- Expected: a **Choose your context** explanation appears.
- Choose **Review access** to open Settings or **Not now** to start with basic logging.
- Phone sensor access does not produce an Android runtime prompt. Location, notifications, and Health Connect are separate optional actions.

### 2. Timestamp-first logging

- Tap **THAT WAS WEIRD**.
- Open **Timeline**.
- Expected: a new **That was weird** entry appears immediately.

Also try **Observation**, **Headache**, **Light changed**, or **Other**. Custom observations should take no more than two taps plus text entry.

### 3. Context access

Open **Settings**.

- **Location + weather:** tap **Allow**, accept an Android location choice, then tap **Test**. Expected: a result or a specific network/location error, never a silent button.
- **Recorder notifications:** tap **Allow**. Expected on Android 13+: the native notification prompt appears.
- **Health Connect:** tap **Connect**. Expected on supported devices: Android's Health Connect permission UI appears and returns to a connected, partially connected, or denied state.

Basic observation logging must continue when every optional permission is denied.

### 4. Rolling recorder

- Turn on **Rolling black box**.
- Expected: Android shows the recorder notification and the app reports that capture is active.
- Leave it running, return later, and log an event.
- Expected: the app remains responsive and the timeline entry is created immediately.

Long-duration survival and OEM battery restrictions are physical-device validation items. Report the phone model and any battery-optimization setting that was active.

### 5. Simulation

- Enable **Simulation mode** in Settings.
- Log several observations and wait for control generation.
- Expected: the normal timeline/database/analysis path works without physical sensor, GPS, weather, Health Connect, or Garmin access.

## Physical soak and watch delivery

For a real phone or Garmin watch, continue with [PHYSICAL_ACCEPTANCE.md](PHYSICAL_ACCEPTANCE.md). It covers the 48-hour foreground-recorder soak, screen-off/OEM battery behavior, pre/post separation, offline Garmin queue replay, duplicate prevention, and evidence to record without sharing private exports.

### 6. Export and delete

- Export JSON and choose a destination you control.
- Inspect only if you are comfortable handling the personal data it contains.
- Test **Delete all local data** only after saving anything you want to keep.

## Garmin acceptance test

Garmin testing requires a supported Epix Pro (Gen 2), Garmin Connect, and a matching watch build. Follow [GARMIN_EPIX_PRO.md](GARMIN_EPIX_PRO.md). Report Android-only results separately from watch/phone BLE results.

## What to include in a bug report

```text
App version/commit:
Phone model:
Android version:
LIVE or SIMULATION:
Recorder on or off:
Optional sources enabled:
Steps to reproduce:
Expected result:
Actual result:
Does basic one-tap logging still work?:
```

Redact notification content, timestamps, coordinates, health values, exported JSON, device identifiers, and Garmin identifiers before posting publicly. Use a private [security advisory](https://github.com/DroneWuKong/Apophenia/security/advisories/new) for a vulnerability or unintended data disclosure.
