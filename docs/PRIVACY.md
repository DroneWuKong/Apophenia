# Privacy and collection boundaries

Apophenia is local-first.

Stored locally: manual observations, phone context, rolling black-box samples, random controls, Garmin event context delivered through the paired-phone companion channel, optional aggregate home-state counts, and explicitly enabled radio/presence metrics. Deliberately enabled notification, calendar, contacts, and message-metadata contents are encrypted before entering their separate SQLite table.

When the vehicle gate is enabled and the operator starts a session, Apophenia connects only to the paired Bluetooth adapter selected in the app. The raw Bluetooth address exists in memory for that connection but is never written to SQLite or preferences; the session stores a locally keyed hash. Standard vehicle telemetry and diagnostic trouble codes are local context. A foreground notification remains visible while session sampling is active.

The separate native-Automotive gate reads only properties the Android Automotive host and vehicle permissions expose. Android Auto or Apple CarPlay projection does not automatically expose vehicle properties; on a projection-only host the app stores no invented substitute. Native rows contain property/area provenance and join the active drive session when present.

The MAVLink gate opens no transport until the operator starts USB, UDP, or TCP capture. The foreground flight-session indicator remains visible while capture is armed. The first valid airframe system ID is locally hashed before persistence; exact `STATUSTEXT` and received position/telemetry values are local evidence and can be sensitive. UDP/TCP traffic stays within the operator-selected transport path, and no telemetry is uploaded by Apophenia. Simulator coverage is not hardware, link, field, or flight validation.

The CRSF/GHST gate opens only the USB device the operator selects and shows a foreground capture notification. Raw Android USB identity is not durable context. The Field-Kit gate opens a UDP listener only for the bounded event/control window and hashes the reported ESP32 identity. The TAK gate similarly joins the configured multicast group only for that bounded window. Own-track mode keeps only the configured keyed UID; the full gate may retain all locations visible on that connection, but labels that scope, hashes UIDs, and omits callsign text. These streams can contain precise position and link evidence.

Optional Health Connect reads are permission-gated and fail-soft. When authorized, Apophenia may read recent heart rate, resting heart rate, sleep, steps, SpO2, and exercise duration. The app does not write Health Connect records and logging still works when Health Connect is missing, unsupported, denied, or empty.

The rolling buffer is bounded and pruned. It is not an unlimited surveillance log.

Network use:
- Open-Meteo only when location permission and the environment gate are enabled. A weather request necessarily sends approximate latitude/longitude and time to that service; the app does not attach an account identity.
- NOAA SWPC Kp/F10.7 only when both ground-context and environment-lookup gates are enabled. The request contains no device identifier or location. Raw RTL-SDR IQ stays in app-private storage under the configured retention period and leaves only through a later explicit evidence-export route.
- Garmin communication through the paired-phone Connect IQ companion service.
- Optional Octopod requests to the user-configured local cluster. Apophenia stores aggregate counts and average temperature only—not entity/person names, camera images, raw audio/video, or Home Assistant/SmartThings/Wyze credentials.
- Optional on-device radio and presence snapshots. Wi-Fi stores a locally keyed BSSID hash, band/frequency, and RSSI while discarding SSIDs and raw BSSIDs. Bluetooth stores a locally keyed address hash, coarse advertised class/name category, and RSSI while discarding raw addresses and names. Network state stores connectivity, carrier/network type, roaming, and signal where Android exposes them; no cell ID is persisted.
- Health Connect through Android's local Health Connect provider when explicitly authorized.
- Operator-started MAVLink UDP/TCP traffic to or from the explicitly selected endpoint; no background connection is opened merely because the gate is enabled.
- Operator-started CRSF/GHST USB serial input and bounded Field-Kit/TAK UDP receive windows; no remote upload is added by these channels.

Android cloud backup excludes the observation database. Android device-to-device transfer may copy the local database to a replacement device through the operating system's protected transfer mechanism. A user-selected JSON export is outside the app's local boundary once it is handed to another app or destination.

Explicit phone-sensor, location, environment, Garmin, and rolling-recorder boundaries are identifiable in code. Simulation mode bypasses physical hardware while retaining storage and analysis behavior.

Not collected by default: radio/presence channels, microphone recordings, camera recordings, notifications, calendar, contacts, message metadata, or browsing history. Tier-2 gates additionally require typing the exact gate name and granting the separate Android permission or access screen.

JSON export and delete-all controls are available in Settings. Exported files leave the app's local boundary only when the user chooses a share destination.

The current data-only JSON exporter excludes the encrypted `sensitive_context` table by construction. A gate being enabled does not silently add Tier-2 contents to that share. The full-evidence, manifest-preview, and export-audit paths remain future PR steps.

Do not attach an unredacted export to a public issue. Use GitHub's private security-advisory channel for an unintended disclosure or permission bypass.

## Total-capture posture

> **This is a personal total-capture instrument.** It records what you authorize, which can include audio, video from every camera, screen contents, messages, notifications, calendar, location, physiology, your vehicle, your aircraft, and the RF spectrum your own receivers can observe. It has no opinions about what you point it at. Nothing leaves your device automatically: exports are explicit, previewed, and tiered; a local export log records every export; an EJECT action exports everything and optionally wipes the store. Recording indicators show when capture is live. Where a capability is restricted, the app discloses the restriction rather than pretending the channel doesn't exist. You are the operator, owner, and only data subject.

That paragraph states the v0.3 target posture. Until the corresponding channel and export steps are merged, the current implementation remains bounded by the capability list and validation evidence in `PROJECT_HANDOFF.md`. Gate presence is not evidence that a capture adapter, Android permission flow, physical device, or export route has been validated.

Device identifiers use a locally keyed, versioned HMAC. Raw MAC addresses, BSSIDs, and adapter/system identifiers are not durable fields. Key rotation deliberately breaks future linkage to older hashes.
