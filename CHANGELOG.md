# Changelog

## [0.3.0-preview.4] - 2026-10-03

### Added

- Opt-in radio-environment snapshots for phone-visible Wi-Fi 2.4/5/6 GHz, Bluetooth LE advertisements, and cellular technologies/signals.
- A permission-aware Settings card with a test snapshot and an explicit full-spectrum/SDR limitation.
- Aggregate-only radio metrics for both events and random controls, plus a software-only simulation path and unit tests.

### Privacy

- SSIDs, BSSIDs, Bluetooth names/addresses, cellular IDs, and raw scan rows are never persisted.
- Radio capture is disabled by default and fails soft when permissions, hardware, or background scan capacity are unavailable.

## [0.3.0-preview.3] - 2026-10-03

### Added

- Durable phone-storage receipts: the watch retains queued events until Android confirms the observation transaction, and retries remain safe through event-ID deduplication.
- Distinct watch haptics for local capture and confirmed phone storage.
- Optional Octopod home context for aggregate Home Assistant/SmartThings state and Wyze camera connectivity; it stores no entity names, images, audio, or service credentials.
- Expandable timeline context capsules showing source, phase, and compact metrics while clearly separating post-event values.

### Changed

- Background workers and the rolling recorder now share the application-scoped database instead of creating ad hoc SQLite helpers.
- Native Monkey C queue coverage now includes transport-without-receipt retention and selective durable acknowledgement.
- Removed the unauthenticated browsable logging deep link; external automation must use the existing signature-protected receiver.

### Validation

- Android unit tests and debug APK assembly pass locally.
- Six native Monkey C tests pass in the Epix Pro 47 mm simulator; all three Epix Pro targets compile with Connect IQ SDK 9.2.0.
- The 51 mm PRG was copied to a physically attached Epix Pro over Windows MTP; final watch-side processing still requires disconnecting USB and opening the app.

Notable project changes are recorded here. The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project intends to use semantic versioning once public releases begin.

## [Unreleased]

### Added

- Total Circumstances gate inventory with standard, deliberate, and capability-conditional consent contracts plus explicit runtime gap reasons.
- Keystore-backed, generation-versioned HMAC identifiers for MAC addresses, BSSIDs, and adapter/system IDs.
- Schema v4 `VIBE` evidence with exact 1–5 presentation labels and a first-class `egress` flag.
- One-tap VIBE controls in the app and a six-option home-screen widget, including the full-width red egress action.
- App long-press note capture that preserves the initial press timestamp while the optional note is entered.
- Gate-backed Bluetooth LE snapshots with locally keyed device hashes, coarse advertised class/name categories, per-device RSSI, and capture-level nearby/strongest-signal metrics.
- Gate-backed Wi-Fi access-point rows with locally keyed BSSID hashes, band/RSSI metadata, and capture-level visible/strongest metrics.
- Connectivity, carrier, network type, roaming, and available cellular-signal snapshots.
- Independent audio-state, display/interaction, power/thermal, time/solar, Wi-Fi Direct, and NFC standard-gate collectors.
- Schema v5 `sensitive_context` storage for notification, calendar, contacts, and message metadata, encrypted with per-install Android Keystore AES-GCM before SQLite insertion.
- Type-the-exact-name Settings confirmations and separate Android permission/access flows for every Tier-2 content gate.
- Schema v6 capture sessions and `session_id` joins on context rows.
- User-started ELM327 Bluetooth `DRIVE_SESSION` capture with standard OBD-II PIDs, stored/pending DTCs, hashed adapter identity, ten-second session samples, and a persistent foreground notification.

### Validation

- Gate-confirmation, hash-stability/rotation, VIBE invariants, schema migration, Tier-2 encryption/AAD rejection, export exclusion, channel bypass, ELM327 parsing/PID/DTC/session behavior, and simulation-pipeline coverage run in the software-only unit suite.

## [0.3.0-preview.2] - 2026-10-02

### Fixed

- Garmin **Open logger** now selects from the SDK's live connected-device list and refreshes stale device statuses before reporting that no watch is connected.
- Garmin event intake now accepts both flat SDK payloads and the nested batch shape produced when the watch flushes its pending-event queue.
- Device and application listeners are registered independently so a failed cleanup call cannot prevent event listening.

### Added

- Regression tests for refreshed Garmin connection selection and flat/nested watch packet batches.
- More specific bridge diagnostics for registration, message status, packet count, and connection refresh failures.

## [0.3.0-preview.1] - 2026-10-02

### Added

- Public contribution, security, conduct, installation, architecture, release, and launch documentation.
- GitHub issue forms, pull-request template, and dependency-update configuration.
- A five-screen Android emulator gallery and public repository metadata.
- Optional neutral check-in prompts that create user-confirmed controls through the normal control pipeline.
- One-to-one control matching by local four-hour block and weekday/weekend.
- Bootstrap effect intervals plus recorded permutation seed, count, and attainable p-value resolution.
- Injectable Garmin packet ingest tests covering timestamp preservation, malformed packets, metric attachment, and duplicate retries.
- Rolling-recorder heartbeat, sample-count, and last-error diagnostics plus a repeatable 48-hour physical acceptance checklist.

### Changed

- README reworked as a screenshot-led, first-person project story with a clearer try-it path.
- GitHub Actions upgraded to current supported action majors.
- Analysis now separates estimated effect magnitude from strength of evidence and retains Benjamini-Hochberg FDR correction.
- Garmin and external event paths now share one application-scoped repository, while bridge state and diagnostics update Compose through `StateFlow`.

## [0.2.1] - 2026-10-02

### Added

- First-run context onboarding.
- Visible location, notification, weather, and Health Connect states.
- Health Connect privacy-rationale screen and modern Android permission declaration.
- Compose coverage for logging and optional context settings.

### Changed

- Refined two-column logging interface and settings presentation.
- Weather capture now requests a current location before falling back to cached data.

### Fixed

- Health Connect permission button silently closing because the required rationale activity was missing.
- Permission actions providing no feedback when already granted or blocked in Android settings.

## [0.2.0] - 2026-10-02

### Added

- Timestamp-first observation storage and hypothesis separation.
- Bounded rolling pre-event and post-event capture.
- Random control windows and event-level association analysis.
- Simulation mode covering the persistence, control, and analysis pipeline.
- JSON export, widget, Quick Settings tile, and foreground recorder.
- Garmin Epix Pro companion, Android bridge, replay identifiers, and bounded offline queue.
- Optional Health Connect historical context.
- Android CI, lint, unit tests, emulator smoke tests, and debug APK artifact.
