# Apophenia 0.3.0-preview.1

This is a development pre-release for software and physical acceptance testing. It is not Play Store signed, medically validated, or broadly hardware validated.

## Highlights

- One-to-one event/control matching by local four-hour block and weekday/weekend.
- Optional neutral check-ins that create user-confirmed controls through the normal pipeline.
- Effect-size confidence intervals, recorded permutation seed/resolution, and separate effect/evidence labels.
- Application-scoped observation storage and an injectable Garmin ingest boundary.
- Observable Garmin connection and malformed/retry diagnostics.
- Rolling-recorder heartbeat, captured-sample count, and last-error visibility.

## Install warning

The attached APK uses an Android debug signature. Export anything you need before replacing a debug build signed on another machine. Android may require uninstalling the older debug build first.

## Evidence boundary

The Android unit/lint/build suite and emulator UI tests are the software gate. Connect IQ compilation and simulator queue tests are separate Garmin software evidence. Follow [PHYSICAL_ACCEPTANCE.md](PHYSICAL_ACCEPTANCE.md) before describing any phone/watch combination as hardware validated.

The APK SHA-256 is recorded on the GitHub release page.
