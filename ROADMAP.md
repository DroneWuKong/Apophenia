# Roadmap

The roadmap is ordered by evidence, not novelty. Items move forward when the preceding layer is actually validated.

## 0.2.x — Android physical acceptance

- Install and upgrade testing across several Android vendors and OS versions.
- Confirm widget and Quick Settings behavior.
- Measure rolling-recorder survival and battery cost under real OEM background limits.
- Validate available physical sensors and omission of unavailable values.
- Exercise location, weather, notification, and Health Connect granted/denied/revoked states.
- Publish a clearly labeled GitHub pre-release APK and checksum.

## 0.3.x — Garmin end-to-end acceptance

- Sideload and test all supported Epix Pro sizes where hardware is available.
- Verify watch timestamp preservation through Garmin Connect and Android persistence.
- Test disconnect, reconnect, retry, concurrent tap, and full-queue behavior physically.
- Confirm metric availability and units without fabricating unsupported data.
- Improve watch interaction only after measuring the current flow on hardware.

## 0.4.x — Analysis and longitudinal review

- Expand transparent event/control summaries and data sufficiency explanations.
- Add better lag and pressure/weather trend exploration.
- Improve repeatability views over time without turning associations into claims.
- Add export fixtures and external analysis documentation.

## Later, separately scoped

- Signed public distribution or Play Store packaging.
- Additional Garmin families after capability-specific validation.
- Additional external observer nodes such as ESP32 sources. The first optional read-only Octopod/Home Assistant aggregate source landed in `0.3.0-preview.3` and still needs cluster/device acceptance.
- Privacy-preserving derived acoustic features only if explicitly enabled; no raw continuous audio by default.

## Non-goals

- Medical diagnosis or treatment recommendations.
- Paranormal or psychological conclusions.
- Advertising, engagement telemetry, or mandatory accounts.
- Silent always-on recording.
- Treating dense sensor rows as independent evidence.
