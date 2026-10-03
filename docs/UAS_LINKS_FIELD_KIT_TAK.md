# Control-link, Field-Kit, and TAK context

These channels are independent gates. They can share an active `FLIGHT_SESSION` timeline, but enabling one never enables another and none starts automatically merely because the app launches.

## CRSF / GHST control-link capture

`LIVE_CRSF_GHST_CAPTURE` is a standard off-by-default gate. The operator chooses an attached USB serial device, protocol, and baud, or starts the deterministic SIMULATION source. A persistent foreground notification is shown for the entire serial capture lifetime.

The decoders preserve the proven byte layouts used by the existing Command and IRONghost paths:

- CRSF frame: address/sync, length, type, payload, CRC-8/DVB-S2. Link Statistics type `0x14` supplies two uplink RSSI antennas, uplink LQ/SNR, active antenna, RF mode, transmitter-power enum, and downlink RSSI/LQ/SNR.
- GHST frame: address, length, type, payload, CRC-8/DVB-S2. Link Statistics type `0x50` (and the protocol-table `0x21` alias) supplies receiver-reported uplink RSSI/LQ/SNR, power, frame interval, latency, and RF mode.

Packet-loss percentages are explicitly derived as `100 - LQ`; they are not a raw RF packet counter. CRSF transmitter power remains an enum because the mapping is transmitter/firmware dependent. The GHST link-stat frame does not expose a distinct downlink tuple, so Apophenia records `control_downlink_available=0` rather than copying the uplink values into invented fields.

Each accepted link frame is a dense stream capture. An event/control snapshot copies the latest metrics plus telemetry age. When a MAVLink flight session is active, rows join that `FLIGHT_SESSION`; otherwise they remain valid ungrouped event/control context. The USB device ID is used only to open Android's selected device and is not persisted.

## Owned Field-Kit detector

`LIVE_FIELD_KIT_CAPTURE` listens only during the bounded event/control snapshot window on the configured UDP port (default `47500`). The ESP32/Field-Kit broadcasts a UTF-8 JSON object shaped like:

```json
{
  "device_id": "field-kit-01",
  "captured_at_ms": 1791043200000,
  "bands": [
    {"name": "915MHz", "rssi_dbm": -41.5, "threshold_dbm": -55.0, "crossed": true}
  ],
  "triggers": [
    {"type": "rssi_spike", "band": "915MHz"}
  ]
}
```

The raw device ID is hashed before any row is created. Durable rows include per-band RSSI, configured threshold, crossing flag, aggregate crossing/trigger counts, and sanitized trigger type/band labels. At most eight datagrams are accepted in one 650 ms window. No listener remains open outside those windows, and no command is sent back to the Field-Kit.

## TAK / CoT

`LIVE_TAK_CAPTURE` opens the configured CoT multicast group only during the bounded event/control window (default `239.2.3.1:6969`). It reads traffic visible on that connection; it does not authenticate to, query, or bypass a TAK Server.

The default filter keeps only the configured own-asset UID. The entered UID is HMAC-hashed before preferences are written; the raw UID is not stored. Received UIDs are hashed in memory for comparison and durable provenance. Callsign text is not persisted.

`LIVE_TAK_CAPTURE_FULL` is the separate capability-conditional gate. When deliberately confirmed, all well-formed CoT tracks visible in the same bounded window may be stored. Those rows are labeled `visible_on_your_connection`, retain a keyed UID hash and coarse CoT type/category, and still omit callsign text. The full gate has no effect if the base TAK gate is off.

Stored track metrics are latitude, longitude, height above ellipsoid, circular/linear error, course, speed, receive-relative track age, and callsign-presence boolean. XML packets are bounded to one UDP datagram; malformed or incomplete events fail closed.

## Validation boundary

The software suite covers split/noisy CRC framing, signed SNR, directional CRSF values, the GHST downlink gap, gate bypass, stream persistence, Field-Kit hashing/threshold/triggers, CoT own/full filtering, UID hashing, callsign omission, and deterministic simulation fixtures.

Still required: representative EdgeTX/ELRS and IRONghost serial mirrors, baud/driver behavior, source-display comparisons, owned ESP32 broadcast timing/schema, Android multicast behavior on the intended LAN, ATAK/TAK traffic comparison, disconnect/reconnect, and flight-timeline joins on physical equipment. Those are bench/field results, not implied by software tests.
