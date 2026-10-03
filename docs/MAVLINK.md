# MAVLink flight sessions

`LIVE_MAVLINK_CAPTURE` is a standard, off-by-default gate for telemetry from the operator's own aircraft/link. Enabling the gate does not open a socket or USB device. The operator must start one transport, and a persistent Android foreground notification remains visible until the transport/session ends.

## Session boundary

A transport can contain modem traffic, ground-station traffic, or multiple MAVLink systems. Apophenia therefore waits for the first CRC-valid nonzero-system `HEARTBEAT` before creating a durable `FLIGHT_SESSION`. That system ID becomes the primary airframe for the connection; frames from other system IDs are ignored for that session. The raw system ID is passed directly into the locally keyed identifier hasher and is not stored in the session row. A process restart marks an unclosed flight session `INTERRUPTED`.

This is a software connection boundary, not proof of takeoff, landing, airworthiness, or one physical flight. `armed` and `landed_state` remain received telemetry values.

## Transports

| Transport | Current path | Boundary |
| --- | --- | --- |
| UDP | operator-started listener, default port 14550 | Listens on Android's available interfaces only while the foreground service is active. Datagram delivery is not exactly once. |
| TCP | operator-entered host and port | One client socket; EOF or failure interrupts the session. Stream framing is handled across arbitrary read chunks. |
| USB / SiK | Android USB host bulk input, default 57,600 baud | Works with class-compliant bulk/CDC devices after the Android USB prompt. Vendor-specific FTDI/CP210x/CH34x control protocols are not implemented by this adapter and remain a per-device hardware gap. |
| SIMULATION | deterministic MAVLink 2 frames | Exercises parser, repository, session, and UI behavior only; it is not radio or aircraft evidence. |

USB availability depends on phone USB-host support, OTG cabling, power budget, Android device permission, the adapter's USB class, and baud configuration. The app never describes a simulator or socket test as physical-link validation.

## Decoded evidence

Supported CRC-validated MAVLink 1/2 messages currently map to scalar context as follows:

- `HEARTBEAT`: custom/base mode, armed flag, vehicle/autopilot type, system status;
- `SYS_STATUS` and `BATTERY_STATUS`: battery voltage/current/remaining plus reported communication drop rate/errors;
- `GPS_RAW_INT`: fix type, satellites, HDOP, latitude, longitude, MSL altitude;
- `GLOBAL_POSITION_INT`, `ATTITUDE`, and `VFR_HUD`: position, relative altitude, velocity, roll/pitch/yaw, airspeed, groundspeed, climb;
- `EKF_STATUS_REPORT`: raw flags and variance values;
- `EXTENDED_SYS_STATE`: VTOL and landed-state enums;
- `RADIO_STATUS`: raw local/remote RSSI, raw link margins, transmit-buffer percentage, receive errors, and corrected packets;
- `STATUSTEXT`: stored verbatim in `session_events`, including the received severity.

Mode changes, arm/disarm changes, and transitions into a failsafe MAV state become timestamped session events. Raw custom-mode values are not relabeled without an autopilot-specific mode map. `RADIO_STATUS` RSSI/margin values remain `raw` because radios do not all use the same dBm conversion.

Every accepted stream metric is stored with the hashed-identity session ID, message/version provenance, observed receive time, and a dense stream `capture_id`. Raw MAVLink system/component identifiers are not durable fields. Event and control snapshots copy the most recent metric values and record each metric's receive timestamp and age. `mavlink_telemetry_age_ms` is marked stale after 3 seconds.

The app also counts forward MAVLink sequence gaps per component. That value is evidence of gaps observed by this receiver. Duplicates, reordering, UDP loss, upstream forwarding, and sender resets mean it is not an exactly-once-delivery counter or a complete RF packet-loss measurement.

MAVLink 2's optional 13-byte signature trailer is framed and retained as a signed-frame marker, but this step does not provision link signing keys or cryptographically verify that signature. CRC validation detects framing/corruption; it is not sender authentication.

## Validation boundary

The unit fixtures cover split/noisy streams, CRC rejection, MAVLink 1/2 parsing, primary-system binding, other-system filtering, sequence gaps, session persistence, exact `STATUSTEXT`, mode/failsafe event de-duplication, stale telemetry, gate bypass, and the SIMULATION path.

Still required on owned hardware: each intended USB/TCP/UDP route, USB power/driver behavior, physical radio loss/recovery, correct autopilot-specific mode interpretation, airframe identity continuity, and representative bench/field/flight testing. Those results must be recorded separately.
