# Capture gates

`HardwareGates.kt` is the authorization boundary for live capture. A gate records an operator's authorization; it does not grant an Android permission or make absent/restricted hardware available.

## Consent tiers

- **Standard:** one explicit confirmation.
- **Deliberate:** the exact enum gate name or a press-and-hold of at least 1.5 seconds.
- **Capability-conditional:** explicit capability confirmation, followed by a fresh availability check at capture time.

Disabling any gate is immediate. All new v0.3 gates default off. The four pre-existing build-backed channels retain their prior defaults for compatibility. `SIMULATION` never reports a live channel as active.

Current gap reasons are `GATE_OFF`, `PERMISSION_DENIED`, `PLATFORM_RESTRICTED`, `HARDWARE_ABSENT`, `LOCKED_BY_STATUTE`, `BUILD_DISABLED`, and `SIMULATION_MODE`. Omniprobe will render these states in its implementation step.

## Tier 1 — standard

- `LIVE_SENSOR_CAPTURE`
- `LIVE_LOCATION_CAPTURE`
- `LIVE_ENVIRONMENT_LOOKUP`
- `LIVE_GARMIN_BRIDGE`
- `LIVE_BLUETOOTH_CAPTURE`
- `LIVE_WIFI_CAPTURE`
- `LIVE_NETWORK_STATE_CAPTURE`
- `LIVE_WIFI_P2P_CAPTURE`
- `LIVE_NFC_CAPTURE`
- `LIVE_AUDIO_METADATA_CAPTURE`
- `LIVE_DISPLAY_INTERACTION_CAPTURE`
- `LIVE_POWER_THERMAL_CAPTURE`
- `LIVE_TIME_CONTEXT_CAPTURE`
- `LIVE_VEHICLE_CAPTURE`
- `LIVE_CARPLAY_AUTOMOTIVE_CAPTURE`
- `LIVE_MAVLINK_CAPTURE`
- `LIVE_CRSF_GHST_CAPTURE`
- `LIVE_FIELD_KIT_CAPTURE`
- `LIVE_TAK_CAPTURE`
- `LIVE_GROUND_CONTEXT_CAPTURE`
- `LIVE_EXPORT_LAN`

## Tier 2 — deliberate

- `LIVE_NOTIFICATION_CONTENTS_CAPTURE`
- `LIVE_CALENDAR_CONTENTS_CAPTURE`
- `LIVE_CONTACTS_CONTENTS_CAPTURE`
- `LIVE_MESSAGE_METADATA_CAPTURE`
- `LIVE_AUDIO_CAPTURE`
- `LIVE_VIDEO_CAPTURE`
- `LIVE_VIDEO_SELFCAPTURE`
- `LIVE_MULTICAM_CAPTURE`
- `LIVE_SCREENRECORD_CAPTURE`

## Tier 3 — capability-conditional

- `LIVE_EV_CAPTURE`
- `LIVE_TAK_CAPTURE_FULL`
- `LIVE_RF_SURVEY_CAPTURE`
- `LIVE_CALL_AUDIO_CAPTURE`

Tier 3 does not hide a channel. It preserves the operator's authorization while reporting why capture is unavailable at that moment. Call audio can therefore report **locked by statute, not by Apophenia** without representing the channel as absent.
