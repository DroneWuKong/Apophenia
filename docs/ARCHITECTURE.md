# Architecture

Apophenia is an Android-first, local-first system with an optional Garmin input companion. The Android database is the canonical history.

## Design invariants

1. Save the observation timestamp before slower enrichment.
2. Store observations as neutral evidence.
3. Store hypotheses separately from observations.
4. Compare event windows with equivalent random control windows.
5. Aggregate dense samples at the capture-window level.
6. Keep post-event data out of event predictors.
7. Make physical integrations optional and fail-soft.
8. Exercise the same repository and analysis pipeline in SIMULATION mode.

## Android data flow

```text
UI / widget / tile / external intent / Garmin
                      |
                      v
             ObservationRepository
                      |
             +--------+---------+
             |                  |
             v                  v
     immediate SQLite row   freeze rolling pre-window
             |                  |
             +--------+---------+
                      v
          asynchronous context enrichment
          phone | device | location | weather | Wi-Fi/network/presence
          Health Connect | Garmin metrics | optional Octopod | encrypted Tier-2 contents
                      |
                      v
            labeled context_samples rows
                      |
                      v
               AssociationEngine
          event captures versus controls
```

## Main Android components

| Component | Responsibility |
| --- | --- |
| `ObservationRepository` | Timestamp-first logging, pre-window copy, hypothesis routing, enrichment scheduling |
| `ObservationDb` | SQLite schema, migrations, observations, hypotheses, scalar context, session events, isolated encrypted contents, rolling buffer, controls |
| `RollingRecorderService` | User-enabled foreground lifecycle for bounded rolling capture |
| `RollingRecorder` | Samples and prunes the rolling scratch buffer |
| `EventEnrichmentWorker` | Fail-soft instant context enrichment after the observation exists |
| `PostEventWindowWorker` | Collects context labeled `POST` after an event |
| `ControlSampleWorker` | Generates random and user-confirmed prompted baseline captures with equivalent pre-window treatment |
| `PromptedCheckInWorker` | Optionally asks for a neutral “nothing unusual” response without fabricating a control from an unanswered prompt |
| `CaptureMatcher` | Selects one-to-one controls in the same local four-hour and weekday/weekend stratum |
| `AssociationEngine` | Matched event/control summaries, robust spread, effect interval, seeded permutation, persistence, tested-feature disclosure, and FDR-adjusted results |
| `HypothesisEvaluator` | Exact registration-to-feature mapping, corrected direction outcome, deterministic snapshot signature, and append-only evaluation creation |
| `ConfounderSurfacer` | Day-one descriptive event/control differences ranked separately from inference and correction |
| `HardwareGates` | Compile/runtime boundary for phone sensors, location, weather, and Garmin |
| `HealthConnectProvider` | Optional read-only historical wearable context |
| `HomeContextProvider` | Optional read-only aggregate Home Assistant/SmartThings/Wyze context through Octopod |
| `BluetoothContextProvider` | Gate-backed BLE snapshot with hashed device addresses and capture-level aggregates |
| `WifiContextProvider` | Gate-backed Wi-Fi snapshot with hashed BSSIDs, band/RSSI, and capture-level aggregates |
| `NetworkStateProvider` | Connectivity, carrier/network type, roaming, and available signal state |
| `PhoneMetadataProvider` | Independent audio, display/interaction, power/thermal, and time/solar scalar snapshots |
| `AuxiliaryPresenceProvider` | Off-default Wi-Fi Direct group state and NFC adapter-state snapshots |
| `SensitiveContextProvider` | Deliberately gated notification/calendar/contacts/message metadata encrypted before persistence |
| `DriveSessionManager` | Owns one in-memory ELM327 connection, hashed adapter identity, and durable session lifecycle |
| `DriveSessionService` | Persistent foreground indicator and ten-second active-drive sampling loop |
| `Elm327Client` | Transport-independent AT initialization, standard PID polling, DTC parsing, and honest unsupported-command tracking |
| `AutomotiveContextProvider` | Gate-backed Android Automotive property mapping with platform/permission degradation |
| `ReflectionAutomotivePropertySource` | Optional `android.car` boundary loaded only on Automotive OS so the phone APK remains portable |
| `MavlinkParser` / `MavlinkDecoder` | Streaming MAVLink 1/2 framing, CRC validation, and supported telemetry/event mapping |
| `MavlinkSessionManager` | First-heartbeat airframe binding, hashed sysid session lifecycle, stream persistence, sequence gaps, and event/control snapshots |
| `FlightSessionService` | Persistent foreground indicator and owner for UDP, TCP, USB/SiK, or simulated transport lifetime |
| `CrsfLinkParser` / `GhstLinkParser` | CRC-8/DVB-S2 serial framing and link-stat mapping from the existing proven protocol paths |
| `ControlLinkManager` / `ControlLinkService` | Latest-value snapshots, flight-session joins, and persistent USB capture ownership |
| `FieldKitContextProvider` | Bounded event/control UDP window and hashed detector snapshot mapping |
| `TakContextProvider` | Bounded CoT multicast window, keyed own-UID filtering, and separately gated full-visible traffic |
| `GroundContextProvider` | Gate-backed barometer/magnetic/solar snapshot plus cached NOAA Kp/F10.7 observations |
| `RfSurveyContextProvider` | Tier-3 bounded `rtl_tcp` IQ window, app-private artifact inventory, and receiver-relative spectral summary |
| `AudioRingCaptureService` | Deliberately armed microphone foreground capture and persistent live indicator |
| `AudioRingCaptureManager` | Timestamp-first circular freeze, overlapping post windows, encrypted checkpoint/finalization, and derived-row insertion |
| `AudioArtifactStore` | Per-event Android Keystore AES-GCM raw-audio artifact and schema-backed media registration |
| `CameraCaptureService` | Camera2 low-rate JPEG ring with Android-reported concurrent-set planning and explicit degradation |
| `ScreenCaptureService` | Per-arm MediaProjection screen ring with persistent foreground indication |
| `VideoRingCaptureManager` | Per-stream tap-time freeze, ten-second post windows, encrypted artifacts, and derived-row insertion |
| `CallAudioCapability` | Tier-3 jurisdiction/platform gap model; no capture substitution |
| `MediaRetentionManager` | Contained artifact/key deletion, fixed-deadline expiry, keep-forever, event scrub, anti-resurrection, and purge-ledger writes |
| `MediaEvidenceReader` | Ciphertext/hash verification plus memory-only PCM or MJPEG decryption for the in-app player |
| `OmniprobeInspector` | Per-event join across ordinary context, encrypted Tier-2 rows, media inventory, purge history, gate capability, and explicit gap accounting |
| `ObservationStore` | Application-scoped owner of the canonical repository/database pair used by UI and external ingest paths |
| `GarminBridge` | Connect IQ discovery, observable connection/diagnostic state, callback registration, and app launch |
| `GarminEventIngestor` | Pure, injectable packet-to-observation boundary with timestamp preservation, metric attachment, diagnostics, and replay handling |

## Rolling black box

The recorder never starts merely because the phone boots. The user enables it explicitly. While enabled, it keeps a pruned scratch buffer covering at least the preceding 30 minutes.

When an event is saved:

- the preceding rolling window is copied into durable context with phase `PRE`;
- available instant context is stored as `INSTANT`;
- scheduled follow-up context is stored as `POST`;
- random baselines copy the same rolling duration with phase `CONTROL`.

The scratch buffer can be deleted or pruned without altering already frozen event context.

## External boundaries

### Weather

Location permission and the live environment gate must both be enabled. Open-Meteo is called only during explicit enrichment work. Lookup failure never blocks observation creation.

### Health Connect

Health Connect is optional and read-only. Permission absence, provider absence, missing record types, and empty history all resolve to omitted context rather than logging failure.

### Garmin

The watch captures its timestamp and available watch context, then transmits through Garmin Connect's companion channel. Android preserves that watch timestamp and treats the phone receive time as transport timing, not event timing. A missing or invalid watch timestamp is explicitly diagnosed before the receive time is used as a fallback. Malformed messages and duplicate retries are also surfaced through the bridge's observable diagnostic state. See [GARMIN_EPIX_PRO.md](GARMIN_EPIX_PRO.md).

### Radio context

Radio context is disabled by default and requires explicit nearby-device and precise-location permission. One capture aggregates the phone-visible Wi-Fi, Bluetooth LE, and cellular environment into counts, band/technology counts, and RSSI summaries. SSIDs, BSSIDs, Bluetooth names/addresses, and cellular identifiers are discarded. The same provider runs for event and control captures, while Android scan throttling and unavailable hardware fail soft. This is not a wideband spectrum analyzer; arbitrary RF requires an external SDR adapter. See [RADIO_CONTEXT.md](RADIO_CONTEXT.md).

The v0.3 split providers supersede that preview aggregate path for event/control enrichment: Bluetooth and Wi-Fi persist only locally keyed identifier hashes, while network state remains non-identifying scalar context. See [BLUETOOTH_CONTEXT.md](BLUETOOTH_CONTEXT.md) and [PHONE_CONTEXT.md](PHONE_CONTEXT.md).

### Tier-2 contents

The app gate and Android permission are independent. When both are present, `SensitiveContextProvider` builds a bounded snapshot, encrypts it with an Android Keystore AES-GCM key, and returns only ciphertext-bearing rows for `sensitive_context`. The standard JSON exporter never queries this table. See [TIER2_CONTENTS.md](TIER2_CONTENTS.md).

### OBD-II drive sessions

The operator selects an already paired Bluetooth device. `BluetoothElm327Transport` opens the Serial Port Profile socket, while `Elm327Client` contains no Android dependency and is exercised with deterministic transports. An active foreground service polls on a bounded cadence and stamps OBD plus concurrent phone/Bluetooth event/control context with one `DRIVE_SESSION` ID. Process loss closes the software claim as interrupted. See [VEHICLE_OBD.md](VEHICLE_OBD.md).

### Native Automotive properties

The portable APK cannot directly link the optional `android.car` library on ordinary phones, so a narrow reflection adapter resolves only public Automotive class, property, config, and value methods when the device declares Automotive OS. Each property is permission-isolated and fail-soft. Projection-only Android Auto/CarPlay hosts return a visible platform gap rather than phone-derived substitutes. See [VEHICLE_AUTOMOTIVE.md](VEHICLE_AUTOMOTIVE.md).

### MAVLink flight sessions

An explicitly started foreground service owns one UDP listener, TCP client, USB bulk/CDC input, or deterministic simulation stream. The streaming parser accepts fragmented MAVLink 1/2 frames only after message-specific CRC validation. A durable session begins on the first valid nonzero-system heartbeat, whose raw sysid is hashed before persistence; frames from other systems are excluded. Scalar stream rows, exact `STATUSTEXT`, mode/failsafe transitions, component sequence gaps, and event/control telemetry-age snapshots share that flight-session timeline. See [MAVLINK.md](MAVLINK.md).

### Control link, Field-Kit, and TAK

CRSF/GHST is an operator-started foreground USB stream. Its latest link values and telemetry age can join an active flight session. Field-Kit and TAK are event/control-contingent UDP windows: their sockets exist only for a short bounded receive period. Field-Kit JSON maps owned detector thresholds/triggers after device-ID hashing. TAK CoT defaults to a keyed own-UID comparison; its Tier-3 full gate expands the filter only to traffic visible on the configured connection. See [UAS_LINKS_FIELD_KIT_TAK.md](UAS_LINKS_FIELD_KIT_TAK.md).

### AV retention and playback

Raw audio and video remain encrypted, app-private artifacts. Schema v8 registers every ciphertext, manifest, key alias, hash, size, event, stream, and fixed retention deadline. A process-wide coordinator serializes finalization with scrub/expiry so a late post-event write cannot resurrect a deliberately purged artifact. Startup and each AV capture service run the same retention engine. Keep-forever changes only the purge decision; returning to the deadline does not extend it. Scrub deletes ciphertext, manifest, and Keystore entry while retaining derived context, then appends a purge-ledger row. The player verifies the stored ciphertext hash and decrypts into memory only. See [AV_RETENTION.md](AV_RETENTION.md).

## Statistical boundary

The engine describes associations rather than causes. Its unit of comparison is an event/control capture, not every dense sensor row. Controls are used at most once and matched on local four-hour block plus weekday/weekend. Effect magnitude is kept separate from evidence strength so a large but uncertain estimate is not mislabeled as a strong finding. The engine records its random seed, permutation count and p-value resolution; reports a bootstrap 95% effect interval; and adjusts families of eligible metric comparisons with Benjamini-Hochberg FDR. Every result discloses the full feature count tested and eligible count. Corrected weak hits are labeled **indistinguishable from noise**. These labels remain exploratory and do not establish clinical or causal meaning.

VIBE egress and high-bad-vibe-without-egress are separate cohorts. BLE/Wi-Fi hashed identities become binary presence features per capture. A 0 is generated only when the corresponding channel emitted its aggregate count for that capture; a missing/gated/failed channel is omitted, not recoded as absence. See [ENGINE_CREDIBILITY.md](ENGINE_CREDIBILITY.md).

Schema v9 pre-registration binds a timestamp to cohort, exact metric, expected direction, and instant or ten-minute pre-event window. Opening an eligible Patterns feature records an analysis-view boundary. The same exact feature can no longer be backdated as a pre-registration. An existing earlier registration locks on its first eligible evaluation; later data may append a new signed analysis snapshot without altering the registered claim. See [HYPOTHESIS_PREREGISTRATION.md](HYPOTHESIS_PREREGISTRATION.md).

`ConfounderSurfacer` accepts the same one-to-one matched values but allows a single pair. It ranks descriptive normalized differences to suggest context worth checking; it never supplies p-values, corrected evidence, or causal labels. `AssociationEngine` separately emits a plain-language baseline comparison while retaining the technical seed, interval, p-value, resolution, and comparison disclosure. See [PLAIN_LANGUAGE_AND_CONFOUNDERS.md](PLAIN_LANGUAGE_AND_CONFOUNDERS.md).

The current matcher does not control for activity, location, sleep/wake state, or attention. Optional prompted neutral check-ins reduce reliance on passive random times but do not remove self-selection bias.

## Omniprobe accounting boundary

`OmniprobeInspector` reads one already-persisted observation and joins its `context_samples`, `sensitive_context`, `media_assets`, and `purge_ledger` rows. Channel matching never creates evidence. Stored values win over a later permission or gate-state change because they describe what existed at capture time; absent values use the current gate/capability state only to explain the gap. Unrecognized context rows are rendered separately instead of discarded. See [OMNIPROBE.md](OMNIPROBE.md).

The overlay separately collects the current process-wide AV ring state. It labels that state as live/current rather than attributing it to the historical event. It does not read or invent an export audit table before that schema exists.

## Software-only boundary

SIMULATION bypasses physical sensor, GPS, weather, Garmin, and Health Connect calls while exercising observation creation, SQLite persistence, rolling-window behavior, control creation, and analysis. Software validation cannot establish battery life, OEM background-process behavior, physical sensor accuracy, BLE delivery, or watch/phone compatibility.
