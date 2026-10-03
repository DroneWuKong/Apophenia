# Apophenia 0.3.0-preview.4

This development preview adds an opt-in, privacy-reduced radio environment snapshot.

## New

- Wi-Fi 2.4/5/6 GHz access-point counts and RSSI summaries.
- Bluetooth LE advertiser counts and RSSI summaries.
- Cellular radio-technology, serving-cell, and signal summaries where Android exposes them.
- A Settings toggle, permission flow, and one-tap test snapshot.
- The same aggregate collector in event, control, and simulation paths.

No SSID, BSSID, Bluetooth name/address, cell ID, or raw scan row is persisted.

## Limits

- A phone is not a wideband spectrum analyzer. Arbitrary RF bands require an external SDR.
- Android may return cached Wi-Fi/cellular results and throttle background scans.
- Physical phone behavior remains to be validated across vendors.

The Garmin durable-receipt, context-capsule, and Octopod features from preview.3 remain included.
