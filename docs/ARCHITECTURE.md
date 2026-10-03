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
          phone | device | location | weather
          Health Connect | Garmin-delivered metrics
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
| `ObservationDb` | SQLite schema, migrations, observations, hypotheses, context, rolling buffer, controls |
| `RollingRecorderService` | User-enabled foreground lifecycle for bounded rolling capture |
| `RollingRecorder` | Samples and prunes the rolling scratch buffer |
| `EventEnrichmentWorker` | Fail-soft instant context enrichment after the observation exists |
| `PostEventWindowWorker` | Collects context labeled `POST` after an event |
| `ControlSampleWorker` | Generates random and user-confirmed prompted baseline captures with equivalent pre-window treatment |
| `PromptedCheckInWorker` | Optionally asks for a neutral “nothing unusual” response without fabricating a control from an unanswered prompt |
| `CaptureMatcher` | Selects one-to-one controls in the same local four-hour and weekday/weekend stratum |
| `AssociationEngine` | Matched event/control summaries, robust spread, effect interval, seeded permutation, persistence, and FDR-adjusted results |
| `HardwareGates` | Compile/runtime boundary for phone sensors, location, weather, and Garmin |
| `HealthConnectProvider` | Optional read-only historical wearable context |
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

## Statistical boundary

The engine describes associations rather than causes. Its unit of comparison is an event/control capture, not every dense sensor row. Controls are used at most once and matched on local four-hour block plus weekday/weekend. Effect magnitude is kept separate from evidence strength so a large but uncertain estimate is not mislabeled as a strong finding. The engine records its random seed, permutation count and p-value resolution; reports a bootstrap 95% effect interval; and adjusts families of metric comparisons with Benjamini-Hochberg FDR. It reports insufficient data, not enough evidence, possible association, or a repeatable association worth investigating. These labels remain exploratory and do not establish clinical or causal meaning.

The current matcher does not control for activity, location, sleep/wake state, or attention. Optional prompted neutral check-ins reduce reliance on passive random times but do not remove self-selection bias.

## Software-only boundary

SIMULATION bypasses physical sensor, GPS, weather, Garmin, and Health Connect calls while exercising observation creation, SQLite persistence, rolling-window behavior, control creation, and analysis. Software validation cannot establish battery life, OEM background-process behavior, physical sensor accuracy, BLE delivery, or watch/phone compatibility.
