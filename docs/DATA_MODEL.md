# Data model

## Observation
A human timestamped report. The record is intentionally descriptive rather than interpretive.

Evidence kinds: `OBSERVATION`, `COINCIDENCE`, `WEIRD`, and `VIBE`. Hypothesis input is routed into the separate hypothesis table even though `HYPOTHESIS_NOTE` remains a compatible input kind.

`origin` identifies Android, widget, tile, external intent, Garmin, or simulation input. `external_event_id` is optional and uniquely deduplicates replayable external events within an origin.

A Garmin-originated observation keeps the watch timestamp, rather than replacing it with phone receive time.

### Vibe evidence

`VIBE` is an ordinal, descriptive state captured at tap time. Schema v4 adds:

- `vibe_rating`: required for `VIBE`, integer 1 through 5
- `egress`: boolean, valid only with `VIBE=5`

The stable presentation mapping is:

1. **Vibe good 🙂**
2. **Tolerable 😐**
3. **Bad 🙁**
4. **Fucked 😖**
5. **Fucky 😵‍💫**

**FUCK THIS, I'M OUT** records `VIBE=5` plus `egress=true`. Egress is intentionally distinct from rating 5 without departure so later analysis can compare moments the operator left with moments they stayed. Non-VIBE rows cannot contain either VIBE field.

Quick capture constructs the complete request at the initial tap/press. Repository scheduling, enrichment, widget delivery, and optional note entry never replace that timestamp with a later receive, sync, or save time.

## Context sample
A scalar measurement with timestamp, source, metric, value and unit. It may be attached to an observation or marked as a random control.

`capture_id` groups all values from the same control/event capture window so dense sampling cannot be mistaken for independent observations. `phase` is one of `INSTANT`, `PRE`, `POST`, or `CONTROL`.

Schema v5 adds scalar phone-context metrics for Wi-Fi presence, connectivity/network state, audio routing and volume, display/interaction state, power/thermal state, local time/solar phase, Wi-Fi Direct, and NFC adapter state. Identifying Wi-Fi values use only locally keyed BSSID hashes. Active playback package identity is marked `platform_restricted` because Android's public playback API exposes active configurations but not their owning UID/package.

Schema v6 adds nullable `session_id` to scalar context. When a drive session is active, OBD values and concurrently captured phone/Bluetooth context carry the same ID. Session-stream rows use a unique `capture_id` per polling instant; event/control rows retain their event/control capture identity. Dense stream rows therefore remain distinguishable from independent human observations.

MAVLink stream rows use source `mavlink` and the active `FLIGHT_SESSION`. Each row carries receive-time message/version provenance while raw system/component identifiers remain in memory only. Event/control snapshots store the latest received scalar with its original telemetry timestamp, age, and stale flag. `mavlink_telemetry_age_ms` uses a documented three-second staleness threshold. `mavlink_sequence_drops` counts receiver-observed forward sequence gaps; it is not an exactly-once or complete RF-loss measure.

## Capture session

`capture_sessions` stores a random session ID, type (`DRIVE_SESSION` or `FLIGHT_SESSION`), start/end timestamps, locally keyed equipment-identity hash, status (`ACTIVE`, `COMPLETED`, or `INTERRUPTED`), and non-identifying boundary metadata. A process restart marks an unclosed active drive or flight session interrupted rather than pretending it ended cleanly.

For an OBD drive session, one deliberate paired-adapter connection is the software boundary. Physical testing must determine how closely adapter connection lifetime matches one ignition cycle on a specific vehicle/adapter combination.

Native Automotive OS rows use the same scalar context table and active drive-session ID when one exists. Every row retains the public vehicle-property name and area ID. Fuel level is stored in milliliters, EV battery level in watt-hours, speed in metres per second, and odometer in kilometres as exposed by Android Automotive; the app does not relabel raw capacity units as percentages.

For MAVLink, one transport plus the first valid airframe `HEARTBEAT` defines the software flight-session boundary. The raw system ID is locally hashed before the session row is written. Schema v7 adds `session_events` for exact `STATUSTEXT`, mode/arm transitions, and failsafe-state transitions. Events retain receive time, type, optional severity, text, and message provenance. This boundary does not itself prove one physical flight.

CRSF/GHST rows use source `control_link`. Dense serial rows have per-frame capture IDs; event/control snapshots reuse the latest link values with telemetry age. When a flight session is active, both join its session ID. `control_*_packet_loss_pct` is derived from LQ rather than represented as an independent packet counter. An unavailable GHST downlink is an explicit boolean gap row.

Field-Kit rows use source `field_kit` and contain a locally keyed device hash, sanitized band/type labels, RSSI, configured threshold, crossing flags, and trigger/crossing counts. Raw ESP32 IDs are never durable fields.

TAK rows use `tak_own` or `tak_visible`. The configured own UID and every received UID are locally keyed hashes; callsign text is omitted. Full-gate rows carry `scope=visible_on_your_connection`, while default rows require the own-asset UID hash. CoT location, error, track, and receive-age values remain scalar context and join an active flight session when present.

Ground rows use `android_ground`, `local_solar`, or `noaa_swpc`. Pressure is normalized to hPa; trend is hPa/hour from a prior snapshot no more than 24 hours old. Magnetic axes/magnitude are microtesla, platform geomagnetic declination and local solar elevation are degrees, Kp is an index, and F10.7 is solar flux units. Public-source timestamps stay in metadata so fetch time is not misrepresented as observation time.

RF survey rows use source `rf_survey` and one event/control `capture_id`. Scalar rows include center frequency, sample rate, bounded IQ byte count, receiver-relative RMS/peak dBFS, and peak offset. Metadata inventories the app-private raw IQ by opaque ID, SHA-256, window, and retention; it does not persist a filesystem path or represent dBFS as calibrated field strength.

## Sensitive context

`sensitive_context` is deliberately separate from `context_samples`. A row contains timestamp, optional observation ID, control flag, source, content type, `capture_id`, AES-GCM ciphertext, IV, and a non-secret key alias. Plaintext is encrypted in memory before the database insert. The associated data binds source, content type, and capture ID, so moving ciphertext to a different capture or channel makes authentication fail.

The four current Tier-2 content types are:

- active notification contents visible through Android Notification Access;
- calendar events overlapping the bounded -12-hour/+36-hour capture window;
- a contacts phone/email snapshot;
- six hours of SMS metadata, excluding the message body by this channel's contract.

These rows are captured only when both the deliberate app gate and the corresponding Android access are enabled. The data-only JSON exporter has no query or output field for `sensitive_context`. A later full-evidence exporter must add an explicit, separately confirmed route rather than reusing the ordinary export path.

## Rolling sample
`rolling_samples` is a bounded scratch buffer, separate from durable event context. When an event occurs, the relevant window is copied into `context_samples`; old scratch samples are pruned.

- pre-event samples: durable and eligible as predictors
- post-event samples: durable for exploration, excluded from predictor calculations

## Hypothesis
Kept separate from observations so theories can change without rewriting evidence.

## Controls
Random control samples are scheduled at jittered intervals and assigned a capture ID. They copy the same 30-minute rolling window used for events and then collect the same instantaneous providers. They provide a baseline so common conditions are not mistaken for meaningful associations.

An optional neutral check-in notification can create a `prompted-control` when the user explicitly taps **Nothing unusual**. Merely displaying the prompt does not create data. Prompted controls use the same worker, database, rolling-window, and analysis pipeline as random controls.

Before analysis, event and control captures are matched one-to-one within a local four-hour time block and weekday/weekend stratum. A control is never reused, and events without an eligible control are excluded from that comparison. This reduces obvious calendar confounding but does not match activity, location, sleep/wake state, or attention.

Analysis aggregates dense values once per event or control capture. Post-event samples and hypothesis rows are excluded from predictor calculations. Results include means, medians, median absolute deviation, standardized effect size with a bootstrap 95% interval, a recorded permutation seed and attainable p-value resolution, split-half directional persistence, and Benjamini-Hochberg false-discovery-rate adjustment. Effect magnitude and strength of evidence are reported separately.

## Identifier hashes

Raw MAC addresses, Wi-Fi BSSIDs, adapter/system identifiers, Field-Kit device IDs, and TAK UIDs must not enter durable evidence rows. Providers pass a raw identifier directly to the Keystore-backed HMAC utility and persist only the returned `idhash:v<generation>:...` token. A channel namespace is included in the HMAC input. Formatting-equivalent MAC/BSSID values normalize to the same token, and rotating the local key advances the generation and intentionally invalidates future comparisons with older hashes.
