# Apophenia 0.3.0-preview.2

This development pre-release repairs the first two defects found during physical Garmin acceptance testing.

## Fixed

- **Open logger** no longer contradicts the connected Epix status by trusting a stale SDK device object.
- Pending watch batches are decoded whether Garmin delivers them as a flat list of packets or a list containing the transmitted batch.
- Listener cleanup and registration failures are isolated and reported in Settings diagnostics.

## Validation

- `gradlew testDebugUnitTest :app:assembleDebug` passed.
- New unit tests cover stale-status refresh and both Garmin payload shapes.
- Two Compose instrumentation tests passed on the API 36.1 emulator.
- The signed watch app was installed and opened on a physical Epix Pro 51 mm with firmware 27.18.

Debug APK SHA-256: `5DD73683BB6C639905434F5E9DA090E9997448036023F95705EF8A0E75ACBFC0`

## Still pending

- Install this replacement APK on the physical phone.
- Confirm **Open logger** opens or prompts on the watch.
- Log an event on the watch and confirm it reaches the Android timeline with its original watch timestamp.
- Confirm offline retry and Garmin metric attachment.

The app remains a development preview and does not make medical, causal, psychological, or paranormal claims.
