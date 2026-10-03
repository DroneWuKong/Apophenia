# Phone context channels

Step 4 splits phone-visible circumstances into independent, off-by-default gates. Every provider runs only for an event or control worker and returns no row when its gate is off, including in SIMULATION.

## Wi-Fi and network

`LIVE_WIFI_CAPTURE` stores one row per visible access point with a locally keyed BSSID hash, RSSI, frequency, and 2.4/5/6 GHz band, plus `wifi_visible_count` and `wifi_rssi_max`. SSID and raw BSSID are never passed to SQLite. Android may return cached scan results, throttle scans, or withhold them when location/Nearby Wi-Fi access or device location is off; the snapshot timestamp is not a claim of fresh RF airtime.

`LIVE_NETWORK_STATE_CAPTURE` stores connected/validated/metered state and, when exposed, carrier, transport/network type, roaming, and signal dBm. `READ_PHONE_STATE` is optional: basic connectivity still works without it. No cell identifier is persisted.

## Phone metadata

- `LIVE_AUDIO_METADATA_CAPTURE`: output device types, ringer mode, music volume, and active playback count. Android's public API withholds the owning app UID/package and third-party focus-event stream, so those gaps are labeled `platform_restricted`.
- `LIVE_DISPLAY_INTERACTION_CAPTURE`: interactive state, brightness, night-light state where the OEM exposes the secure setting, notification count (all visible notifications with Notification Access, otherwise Apophenia only), keyboard state in the app, and the foreground package only when the user separately grants Usage Access. Counting never stores notification text through this Tier-1 gate.
- `LIVE_POWER_THERMAL_CAPTURE`: battery level/current/rate, charging, thermal status where supported, one-minute CPU load, and available RAM.
- `LIVE_TIME_CONTEXT_CAPTURE`: timezone offset/name, ISO weekday, weekend, day-part bucket, and locally computed solar elevation/phase when a last location is available.

## Auxiliary presence

`LIVE_WIFI_P2P_CAPTURE` records Wi-Fi Direct hardware and current group/owner state when Android returns it. `LIVE_NFC_CAPTURE` records NFC hardware and adapter state. Both default off. Apophenia does not poll NFC tags in the background; nearby-tag presence is therefore labeled as foreground-only rather than invented.

All metrics carry the event or control `capture_id`. Simulation exercises the same metric contracts but is not hardware validation.
