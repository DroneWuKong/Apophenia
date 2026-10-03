# Bluetooth presence channel

`LIVE_BLUETOOTH_CAPTURE` is a standard, off-by-default gate. When authorized, the same bounded BLE scan feeds event windows and jittered controls. Gate off means no scan and no Bluetooth rows, including in simulation.

## Stored rows

Each visible address produces one `bt_device_rssi_dbm` row per capture with:

- a generation-versioned, locally keyed HMAC of the address;
- RSSI in dBm;
- a coarse class derived from advertised services, manufacturer-data presence, and connectability;
- a coarse name category such as `wearable`, `audio`, `vehicle`, `home_iot`, `personal_computer`, `other_named`, or `unnamed`.

The raw address and advertised name exist only in scan memory and never enter `context_samples`, logs, or exports. The capture also stores `bt_nearby_count` and `bt_rssi_max`. All rows share the event/control `capture_id`; devices within one window are not independent observations.

## Android capability boundaries

- Android 8–11: BLE scan results require location permission and device location may need to be enabled.
- Android 12+: `BLUETOOTH_SCAN` (Nearby devices) is required. Apophenia also requests location because device presence is intentionally captured as physical context.
- The manifest intentionally does not assert `neverForLocation`. Android documents that assertion for apps that never derive physical location and may filter some BLE advertisements when it is used. Presence association is an intended Total Circumstances signal, so hiding those results would be misleading.
- Scan throttling, radio-off state, OEM background limits, and chipset filtering can reduce or empty a snapshot. This is a phone-visible BLE inventory, not a calibrated spectrum measurement.

The live event scan is bounded to 1.5 seconds by default (hard-capped at 10 seconds). SIMULATION uses two synthetic advertisers but passes them through the production hash/category/sample encoder. Simulator results prove software behavior only.
