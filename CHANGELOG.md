# Changelog

Notable project changes are recorded here. The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project intends to use semantic versioning once public releases begin.

## [Unreleased]

### Added

- Public contribution, security, conduct, installation, architecture, release, and launch documentation.
- GitHub issue forms, pull-request template, and dependency-update configuration.
- Current Android UI screenshots and public repository metadata.

### Changed

- README reworked as a public project landing page.
- GitHub Actions upgraded to current supported action majors.

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
