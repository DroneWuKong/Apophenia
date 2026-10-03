# TOTAL_EVIDENCE and session presets

The master capture strip is always visible above the four main tabs. It reports three facts separately:

- the active evidence mode (`NORMAL`, `FIELD`, `DRIVE`, `HOME`, `EVERYTHING`, or `TOTAL EVIDENCE`);
- the number of authorized capture gates;
- the actual audio/video ring state and active video-stream count.

An authorized gate is not displayed as a live ring or active hardware session. A live ring comes only from the foreground capture manager.

## Deliberate arming

All preset actions require a continuous 1.5-second hold. Short taps explain the requirement and change nothing. This hold is the deliberate confirmation for Tier-2 gates included in a preset. Capability-conditional gates still depend on platform, hardware, configured jurisdiction, and momentary availability.

Preset arming does only the following:

1. authorizes the preset's `HardwareGates` entries;
2. records the active preset and activation time locally;
3. selects a 60- or 120-second microphone pre-buffer for the next time the audio foreground service is started.

It does **not** request an Android permission, start OBD/MAVLink/CRSF/Field-Kit/TAK/RF hardware, accept MediaProjection consent, start an AV foreground service, configure a destination, or export data. Those momentary steps remain explicit in their existing Settings cards.

Disarming the mode clears the preset/master state and returns the future audio pre-buffer to 60 seconds. It does not revoke individual gate choices and does not silently stop a service the operator may have armed separately.

## Preset contents

| Preset | Gates armed | Audio pre-buffer |
|---|---|---:|
| `FIELD` | MAVLink, CRSF/GHST, Field-Kit, RF survey, ground context, mic, main/front/multicam, screen, Garmin | 120 s |
| `DRIVE` | OBD-II, Android Automotive, EV PIDs, Bluetooth cabin presence, mic, main/front/multicam | 120 s |
| `HOME` | phone sensors/location/weather, Bluetooth, Wi-Fi, network, audio metadata, display, power, time, ground context | 60 s |
| `EVERYTHING` | every capture gate | 120 s |

The existing Octopod endpoint is not a `HardwareGates` entry and remains separately configured. Presets do not invent a future home-automation gate.

`EVERYTHING` and `TOTAL_EVIDENCE` exclude `LIVE_EXPORT_LAN` and `LIVE_TASKER_EXPORT`. A capture preset cannot authorize data movement. LAN and automation export retain their own gate, preview, confirmation, and explicit route boundaries.

## TOTAL_EVIDENCE

`TOTAL_EVIDENCE` uses the `EVERYTHING` capture set and marks the master state with the operator-facing phrase:

> The next hours are the investigation.

It chooses the 120-second microphone pre-buffer. The ring still reads **off** until the microphone service actually starts, and screen capture still requires Android's per-arm MediaProjection consent.

## Validation boundary

JVM tests cover short-hold rejection, exact FIELD membership, maximum buffer selection, capture-gate completeness, export-gate exclusion, unchanged Android permission state, and disarm-without-revocation. The emulator smoke suite verifies the always-visible master strip and Settings surface.

These checks do not prove foreground-service survival, battery cost, physical camera concurrency, or any vehicle/UAS/RF/watch hardware path. Validate those independently under the dated checklist in `PROJECT_HANDOFF.md`.
