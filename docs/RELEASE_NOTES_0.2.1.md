# Apophenia 0.2.1 development preview

Apophenia is a local-first Android and Garmin observation recorder. Version 0.2.1 focuses on making the Android app clearer to use and making optional context permissions behave visibly and honestly.

## Highlights

- Refined one-tap logging screen with readable two-column categories.
- New first-run explanation of optional context access.
- Working location and notification permission prompts with blocked-state guidance.
- Weather **Test** action with a visible result or error.
- Working Health Connect permission flow, read-only status, and privacy rationale.
- Clear settings states for phone sensors, weather, notifications, Health Connect, Garmin, recorder, and simulation.

## Validation

- 14 Android/JVM tests passed.
- Android lint passed.
- Two Compose tests passed on an API 36.1 emulator.
- Debug APK assembled with JDK 17.
- Location, notification, weather, and Health Connect flows were exercised in an emulator.
- All three Garmin Epix Pro targets previously compiled with Connect IQ SDK 9.2.0.
- Four native Monkey C queue tests previously passed in the Epix Pro 47 mm simulator.

## Important limitations

- This is a development preview, not a Play Store release or medical device.
- Broader physical Android acceptance is still pending.
- Physical Garmin BLE delivery, reconnect behavior, and sensor context remain pending.
- Debug APKs may use different signing keys across build machines. Export data before uninstalling an older debug build.
- Association output is exploratory and does not establish diagnosis or causation.

## Before publishing this release

Replace the following with the checksum of the exact uploaded APK:

```text
app-debug.apk SHA-256: [REPLACE]
```

Then state the physical devices actually tested, or retain the pending limitations above.

Full changes: [CHANGELOG.md](../CHANGELOG.md)
