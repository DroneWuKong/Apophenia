# Project handoff — 3 October 2026

## Goal

Apophenia is a personal observation and context recorder for things a person notices but cannot easily explain: symptoms, lights changing, sounds, device behavior, coincidences, environmental changes, or simply **THAT WAS WEIRD**.

The system should record evidence neutrally and test patterns instead of reinforcing them.

## Current Android implementation

- Kotlin / Jetpack Compose native Android app
- package: `com.dronewukong.apophenia`
- v0.3.0
- Android min SDK 26
- explicit hardware/simulation gates
- local SQLite database
- observation types: observation, coincidence, hypothesis note, weird, and schema-backed VIBE/egress evidence
- one-tap app and widget VIBE capture with the five exact ordinal labels and a distinct full-width egress action
- immediate timestamp-first logging
- widget and Quick Settings tile
- random control sampling
- one-to-one control matching by local four-hour block and weekday/weekend
- optional, explicitly enabled neutral check-in prompts using the same control pipeline
- phone sensors
- device context
- location/weather enrichment
- 30-minute rolling pre-event black box
- 30-minute post-event collection
- event-vs-control association engine
- manifest-previewed data-only and full-evidence ZIP export through Android sharesheet and SAF save-as
- event-scoped evidence dossiers and selected-event HTML/PDF reports with optional retained AV stills
- global/per-event evidence seals, scrubbed dossier copies, bounded route audit outcomes, and verified-route-only EJECT
- Android **Log to Apophenia** text/image ingress with receipt-time context freeze and app-private hash-verified attachments
- deliberate Tasker capture/export-intent gates; export automation can request a preview but cannot route or wipe
- optional, permission-gated Health Connect reads
- first-run context onboarding with visible location, notification, weather, and Health Connect status
- database schema v11 with observation origin, external-event deduplication, explicit context phase/session identity, VIBE rating, egress, capture-session lifecycle/events, an isolated encrypted Tier-2 content table, AV media/purge inventory, inbound attachment inventory, immutable hypothesis registration/evaluation history, evidence seals, and export audit
- application-scoped observation repository shared by UI, widget, tile, external intent, and Garmin ingest
- optional privacy-reduced Octopod home context shared by event and control captures
- optional privacy-reduced Wi-Fi/cellular aggregate snapshots shared by event and control captures
- gate-backed Bluetooth LE presence rows with locally keyed address hashes plus capture-level count/strongest-RSSI metrics
- gate-backed Wi-Fi rows with locally keyed BSSID hashes plus band/RSSI and capture-level count/strongest metrics
- network state plus independent audio, display/interaction, power/thermal, time/solar, Wi-Fi Direct, and NFC event/control snapshots
- deliberate notification/calendar/contacts/message-metadata capture encrypted with Android Keystore AES-GCM before SQLite persistence and excluded from data-only export
- paired ELM327 OBD-II drive sessions with hashed adapter identity, standard PID/DTC decoding, ten-second foreground sampling, and same-session phone/Bluetooth joins
- optional Android Automotive OS property snapshots with per-property/area provenance and fail-soft permission/projection gaps
- MAVLink 1/2 flight sessions over UDP, TCP, or class-compliant USB/SiK with hashed sysid, CRC validation, exact STATUSTEXT, received mode/GPS/EKF/battery/link context, observed sequence gaps, and telemetry-age staleness
- CRC-validated CRSF/GHST USB link-stat streams, owned Field-Kit threshold/trigger windows, and TAK own-track-by-default snapshots with a separate full-visible-traffic gate
- expandable timeline context capsules with explicit pre/instant/post phases
- schema-backed encrypted AV retention with configurable new-capture deadlines, per-event keep-forever/scrub, startup/service expiry, anti-resurrection, purge ledger, and memory-only evidence playback
- explicit egress/stayed analysis cohorts, per-hashed-device BLE/Wi-Fi presence features, and mandatory tested/eligible feature counts with corrected noise labeling
- exact cohort/metric/direction/window hypothesis pre-registration, analysis-view anti-backdating, and append-only confirmed/not-yet-supported/refuted evaluations
- descriptive day-one confounder surfacing, baseline-relative plain-language results, small-n honesty, and refuted-as-win UI copy

## Garmin Epix Pro (Gen 2)

`garmin-epix-pro/` contains the Connect IQ companion for:
- epix2pro42mm
- epix2pro47mm
- epix2pro51mm

The watch sends:
- original watch event timestamp
- heart rate
- stress
- Body Battery
- Pulse Ox
- pressure
- temperature
- phone-connected state

A bounded watch-side pending queue preserves events during temporary disconnects.

Watch protocol v3 adds a persistent installation/sequence event ID. Android uses the Garmin origin plus this ID to suppress replay duplicates while retaining the original watch timestamp. Legacy v2 packets remain readable but cannot provide the same replay guarantee.

Protocol v3.1 keeps an event queued after BLE transport completion and removes it only after Android confirms durable SQLite storage. A lost receipt yields a safe replay that the phone deduplicates and acknowledges. Distinct watch haptics identify local capture and confirmed phone storage.

The bridge exposes connection and diagnostic state as a `StateFlow`, and Compose observes it directly. It refreshes device status before opening the watch app and accepts both flat and nested Connect IQ batch payloads. Packet parsing/ingest is separated from the Garmin SDK callback behind an injectable store, with unit coverage for timestamp preservation, metric attachment, malformed packets, nested batches, stale device status, and disconnected/replayed event deduplication.

The Android Garmin bridge uses app id:
`4f4d0f7b3d6f4b36b3e88b91129c70a2`

## Statistical rules

- Observations are evidence, not conclusions.
- Hypotheses are stored separately from observations.
- Random controls are required for comparison.
- Dense rolling samples are aggregated at event/control level so they are not treated as independent observations.
- Post-event samples are preserved for exploration but excluded from predictor calculations.
- Correlation does not establish causation.
- Effect magnitude and evidence strength are separate outputs.
- Permutation seeds and attainable p-value resolution are recorded; metric families use Benjamini-Hochberg FDR adjustment.

## Hardware boundary

`HardwareGates.kt` is the explicit boundary. The v0.3 foundation enumerates every planned live channel, persists authorization, and enforces three consent tiers. Authorization is separate from runtime capability: permission denial, platform restriction, absent hardware, and statutory lock remain explicit gap reasons.

Build gates:
- `LIVE_SENSOR_CAPTURE`
- `LIVE_LOCATION_CAPTURE`
- `LIVE_ENVIRONMENT_LOOKUP`
- `LIVE_GARMIN_BRIDGE`

Runtime `SIMULATION` bypasses physical adapters while preserving storage and analysis behavior.

Raw MAC addresses, BSSIDs, and adapter/system IDs must pass through `DeviceIdentifierHasher` before persistence. The Android implementation uses a generation-versioned Keystore HMAC and deletes the prior local key on rotation. Pure-key unit tests cover stability, namespace separation, normalization, and rotation invalidation.

See [CAPTURE_GATES.md](CAPTURE_GATES.md) for the complete inventory and confirmation contract.

## Build

CI workflow: `.github/workflows/android.yml`

Expected command:

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug
```

Expected APK:

`app/build/outputs/apk/debug/app-debug.apk`

The emulator UI check is `./gradlew connectedDebugAndroidTest`. CI runs it on API 36 and uploads the debug APK.

Public project material is indexed from `README.md`. Installation, architecture, release, contribution, security, and Reddit launch documents are present. Current UI screenshots live under `docs/images/` and contain emulator/demo state only.

Garmin compilation requires Connect IQ SDK / Monkey C and a developer signing key outside the repository:

```powershell
./tools/build-garmin.ps1 -KeyPath C:/path/to/developer_key.der
```

## Validation status — 2026-10-03

- `./gradlew.bat testDebugUnitTest lintDebug :app:assembleDebug`: passed locally on JDK 17.
- `./gradlew.bat testDebugUnitTest`: 160 tests passed. Coverage includes observation timestamp preservation and deduplication, v2-to-v11 migration, inbound share parsing/receipt timestamps/attachment integrity, Tasker contract bounds, rolling pre/post separation, control/session grouping and matching, egress/stayed cohorts, per-device presence/missing-channel handling, prompted neutral controls, hypothesis separation/pre-registration/locking/anti-backdating/evaluation outcomes, descriptive confounder surfacing, plain-language binary/continuous results, small-n/refutation copy, data/full export tier isolation, event-dossier scoping, report redaction/honest tiers, evidence-seal scope, scrubbed dossier verification, EJECT window/completeness/route/wipe refusal, manifest/hash verification and corruption refusal, raw SQLite checkpoint/inspection, attachment-aware backup restore and pre-mutation refusal, LAN local-address/gate/credential/HTTP fixtures, batch-sharesheet intent construction, byte-exact document writes, AES-GCM/AAD integrity, Tier-2 isolation, ELM327 response/PID/DTC/session behavior, Automotive property provenance/gap behavior, MAVLink 1/2 framing/CRC/session/filtering/STATUSTEXT/staleness behavior, CRSF/GHST CRC/link layouts, Field-Kit hashing/triggers, TAK own/full filtering, AV retention/keep/scrub/path-containment/anti-resurrection behavior, multiple-comparison disclosures, association statistics, Garmin packet parsing/ingest/retry behavior, and the full simulated enrichment/database path.
- `./gradlew.bat connectedDebugAndroidTest`: eleven tests passed on an API 36.1 Android emulator; they cover logging, VIBE/egress, settings, pre-registration, Omniprobe, demo isolation/badge state, sealed-release interception before sharesheet, the data-only manifest preview, raw-SQLite checkpoint preview, generation of manifest-listed HTML/PDF report payloads through Android `PdfDocument`, and `ACTION_SEND` text-to-external-observation/private-attachment ingress.
- Native Android location and notification permission prompts were exercised on the emulator. Weather returned a live Open-Meteo result after location approval.
- Health Connect's permission controller was exercised end-to-end on the emulator, including the required privacy-rationale declaration, six read permissions, and optional background access. The app returned to a connected state.
- APK produced at `app/build/outputs/apk/debug/app-debug.apk`.
- Development pre-release published at `https://github.com/DroneWuKong/Apophenia/releases/tag/v0.3.0-preview.3` from tested commit `b8e712864e16ebf8ac442c319177670ee2062b1e`; attached APK SHA-256 is `EDF8ACF3C151F12137BCF83E4C2F15ABC087E529C6D0D6F37E5FFCDF254D846D` and the Epix Pro bundle SHA-256 is `575E8B6B267CA77DEF0C2B9676D1202DB9C9A3751B4A2032E2F27D55FADB6D65`.
- Connect IQ SDK 9.2.0: all three Epix Pro targets compiled.
- Garmin Run No Evil: all six pending-queue/receipt tests passed on the Epix Pro 47 mm simulator.
- A freshly signed Connect IQ build was physically sideloaded and opened on an Epix Pro 51 mm running firmware 27.18. This proves installation and launch only.
- The receipt-enabled 51 mm PRG was copied into `GARMIN/Apps` on the physically attached watch. USB disconnect and launch are still required before claiming the replacement was processed.
- Watch-to-phone event delivery has not yet passed physical acceptance. The first physical attempt exposed stale Android device selection and nested-batch decoding defects; preview.3 also adds durable phone-storage receipts.
- Octopod parsing and simulation are tested; cluster reachability from the physical phone is not yet proven.

## Next work

The Total Circumstances implementation is deliberately split into the 23 review steps in the v0.3 master plan. Steps 1–23 now cover gates/hashing/VIBE, quick capture, Bluetooth, phone/Tier-2 context, OBD-II drive sessions, native Automotive properties, MAVLink flight sessions, CRSF/GHST, Field-Kit, TAK, ground context, bounded RTL-SDR survey windows, audio, camera/multicam, screen rings, derived AV metrics, the call-audio capability stub, durable AV retention/player controls, engine-credibility disclosures, hypothesis pre-registration, confounder surfacing, honest plain-language results, the per-event Omniprobe inventory, deliberate TOTAL_EVIDENCE/session presets with a persistent master strip, an isolated six-story demo corpus, verified manifest-preview export tiers over sharesheet/SAF, checkpointed raw SQLite snapshots, nested verified backups with rollback restore, explicit private-address/document-provider LAN push, event-scoped dossiers, selected-event HTML/PDF reports, evidence seals, scrub-before-share, bounded route audit, verified-route-only EJECT, inbound share-to-log attachments, and deliberate Tasker/intent hooks. These are software and emulator results, not physical validation, NAS durability, recipient-read proof, or destination-retention proof.

### Next physical-validation checklist — 3 October 2026

Run each item only after its implementation PR and software fixtures pass. Record device/firmware, Android version, adapter identity hash, app commit, start/end time, and observed gaps. Simulator evidence never satisfies these items.

- Exercise mic, main/front camera, supported concurrent camera combinations, screen capture, encrypted pre/post freeze, persistent indicator, retention, and scrub on representative phones.
- Exercise BLE, Wi-Fi, Wi-Fi Direct, NFC, cellular/network state, notification/calendar/contact/message gates, and OEM/API throttling behavior on representative Android versions.
- Exercise an owned ELM327-compatible adapter through a real ignition cycle, including supported PIDs, unsupported manufacturer PIDs, voltage, and DTC behavior.
- Exercise MAVLink over each available owned transport, confirm `FLIGHT_SESSION` identity, telemetry-age staleness, STATUSTEXT fidelity, and link-loss recovery without claiming flight validation.
- Exercise CRSF/GHST and Field-Kit decode paths against representative owned hardware and compare stored link values with the source display/log.
- Exercise an owned RTL-SDR/OTG receiver, prove raw-IQ window bounds and purge behavior, and record Android USB/power limits.
- Re-test Garmin/watch delivery, durable receipt behavior, physiology timestamps, disconnect/replay, and one representative physical event-to-phone capture.
- On representative Android versions, compare each Omniprobe permission/platform/hardware gap against the actual OS setting and attached device; verify that empty event windows remain `NO_SAMPLE_IN_WINDOW` or `NO_ACTIVE_SESSION` rather than being promoted into hardware claims.
- Verify every preset on-device: the hold duration, no permission-dialog side effect, no unintended service start, exact gate membership, persistent master strip, 120-second audio pre-buffer where specified, and `LIVE_EXPORT_LAN`/`LIVE_TASKER_EXPORT` exclusion.
- Verify data-only/full-evidence previews and hashes on a representative phone, save to local and USB/OTG document providers, and confirm sharesheet recipients can read the granted ZIP without gaining unrelated app files.
- Verify raw SQLite checkpoint/import tooling, full-backup restore and rollback with retained AV/Tier-2/RF evidence, a real SMB/NFS DocumentsProvider, and a controlled local HTTP(S) endpoint. Record provider/NAS versions and compare received SHA-256; do not promote a 2xx response into a durability or exactly-once claim.
- With disposable evidence, verify global/event seals, scrubbed dossier contents, route audit wording, and EJECT through representative SAF, document-provider, and local HTTP routes. Confirm chooser handoff never authorizes a wipe and interrupted/partial writes leave the evidence store intact.
- From representative apps, share text and images into **Log to Apophenia**; compare tap time, frozen context, copied bytes/hash, 25 MiB refusal, source-URI absence, scrub behavior, backup restore, delete-all, and EJECT cleanup.
- With a representative Tasker release, verify gate-off refusal and gate-on logging/export-preview actions while foreground, background, screen-off, and after process restart. Confirm no caller timestamp is accepted and no export action selects a route, releases a seal, or wipes evidence.
- Only after the preceding gates independently pass, run bounded drive/field/flight sessions and document those results separately from software and bench evidence.

1. Disconnect USB, open Apophenia on the watch, and confirm the v0.3 replacement launches.
2. Install the `0.3.0-preview.3` debug APK on the physical phone.
3. Validate watch event → Garmin Connect → phone observation → attached Garmin metrics → **Saved on phone** receipt.
4. Validate 30-minute pre-event/post-event capture, foreground-service survival, widget, and Quick Settings tile on a real Android phone.
5. Validate each available physical sensor and confirm unavailable values are omitted.
6. Validate Health Connect permission and data behavior on supported physical devices.
7. Validate weather enrichment with real permission/network/location conditions.
8. Merge the accepted release/documentation commits to `main` after review; the development pre-release already points to the tested branch commit.
9. Test the optional Octopod observer on the home Wi-Fi; keep future ESP32 sources separately scoped.
10. Validate Wi-Fi/BLE/cellular aggregate snapshots on representative phones; Android scan throttling and OEM behavior remain hardware gates.
