# Garmin Epix Pro (Gen 2) integration

## Architecture

```text
Epix Pro Connect IQ app
  └─ manual event
     ├─ exact watch timestamp
     ├─ latest SensorHistory values
     └─ Communications.transmit()
            ↓ BLE / Garmin Connect companion service
Android GarminBridge
  └─ ObservationRepository.log(timestamp from watch)
     ├─ freezes phone pre-event rolling buffer
     ├─ schedules phone/environment enrichment
     ├─ schedules phone post-event window
     └─ attaches Garmin metrics to the same observation
```

The watch is a fast input/context node. The Android phone remains the canonical history. A bounded 10-event queue preserves timestamps during transient phone disconnects. Protocol v3 includes a persisted installation ID and monotonic event sequence; Android uses that event ID to reject a replayed delivery without replacing the original watch timestamp.

Protocol v3.1 adds a durable receipt in the reverse direction. BLE transport completion changes the watch status to **Sent - awaiting phone receipt** but does not remove the event. Android sends a receipt containing the event ID only after the observation is present in SQLite and its Garmin context is attached. The watch then removes only acknowledged IDs and displays **Saved on phone**. If the receipt is lost, a manual retry is safe because Android acknowledges both a new insert and an already-stored duplicate.

Only one batch is transmitted at a time. New taps append behind the in-flight
batch; its completion removes only the sent prefix. A failed send keeps every
pending observation. Reopening the app or choosing **Retry queued events** retries
stored observations without recording a new event. At capacity, the app reports
**Queue full - NOT recorded** and does not evict an older observation. The status
appears beneath **THAT WAS WEIRD**.

The receipt protocol is at-least-once delivery with idempotent phone storage. It
does not claim distributed exactly-once delivery. Physical phone/watch acceptance
remains required.

## Queue regression tests

The `(:test)` functions in `source/PendingEvents.mc` execute in Garmin's Run No
Evil framework, not a reimplementation in another language. Compile with
`monkeyc -t` and launch the simulator, then run on Windows:

```powershell
monkeydo.bat path/to/queue-tests.prg epix2pro47mm /t
```

On 2026-10-03, all six tests passed in SDK 9.2.0's Epix Pro 47mm simulator:
preserving taps during a send, retaining failed batches, rejecting overflow
without eviction, empty/restored queue handling, retaining transport-complete
events until receipt, and selective receipt removal. All three Epix Pro targets
also compiled with `tools/build-garmin.ps1`. This tests queue logic and Monkey C/API compatibility, not BLE transport or Android persistence.

Targets: `epix2pro42mm`, `epix2pro47mm`, `epix2pro51mm`.

Watch permissions: `Communications`, `SensorHistory`.

Metrics may include heart rate, stress, Body Battery, Pulse Ox, pressure, temperature, and phone-connected state. Unsupported or unavailable channels are omitted rather than fabricated.

Android checks whether Garmin Connect is installed before starting the Connect IQ SDK. When it is missing, Settings reports that cleanly and no Garmin UI is opened. SIMULATION bypasses all Garmin SDK calls.

Connect IQ device objects can carry stale status fields. The Android bridge therefore uses the SDK's live connected-device list and refreshes known-device status before **Open logger** decides that no watch is connected. Incoming payload decoding walks both flat and nested lists because a Monkey C array of queued dictionaries may arrive as a nested Android list.

On 2026-10-02, the signed watch app was physically installed and opened on an Epix Pro 51 mm running firmware 27.18. On 2026-10-03, the v0.3 receipt-enabled PRG was copied to that watch over MTP; USB disconnect and an on-watch launch are still needed to prove that the watch processed the replacement. Watch-to-phone delivery remains pending until the `0.3.0-preview.3` phone/watch pair passes the steps below.

## Physical validation

1. Pair Epix Pro in Garmin Connect.
2. Install Android and matching Connect IQ apps.
3. Keep Android in LIVE mode.
4. Refresh Garmin status in Settings.
5. Open logger and log **THAT WAS WEIRD**.
6. Confirm the phone event preserves the watch timestamp and Garmin source.
7. Verify pressure normalization Pa → hPa.
8. Test phone-offline queuing and later delivery.
   - Reconnect and use **Retry queued events**, without adding another observation.
   - Tap while a batch is sending; confirm later observations are delivered too.
   - Fill ten offline slots; the eleventh must report **NOT recorded**, and the
     original ten must remain queued.
9. Switch to SIMULATION and confirm physical Garmin calls are bypassed.
