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

The watch is a fast input/context node. The Android phone remains the canonical history. A bounded 10-event queue preserves timestamps during transient phone disconnects.

Targets: `epix2pro42mm`, `epix2pro47mm`, `epix2pro51mm`.

Watch permissions: `Communications`, `SensorHistory`.

Metrics may include heart rate, stress, Body Battery, Pulse Ox, pressure, temperature, and phone-connected state. Unsupported or unavailable channels are omitted rather than fabricated.

## Physical validation

1. Pair Epix Pro in Garmin Connect.
2. Install Android and matching Connect IQ apps.
3. Keep Android in LIVE mode.
4. Refresh Garmin status in Settings.
5. Open logger and log **THAT WAS WEIRD**.
6. Confirm the phone event preserves the watch timestamp and Garmin source.
7. Verify pressure normalization Pa → hPa.
8. Test phone-offline queuing and later delivery.
9. Switch to SIMULATION and confirm physical Garmin calls are bypassed.
