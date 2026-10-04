# Privacy and collection boundaries

Apophenia is a local-first personal total-capture instrument. It is capable of being **maximum invasive** when the operator deliberately authorizes and arms its full capability set. That can include audio, every camera Android exposes, screen contents, protected message/notification/calendar/contact contents, location, physiology, vehicle and aircraft telemetry, nearby radios, and bounded RF-receiver evidence. The phrase describes the capability ceiling, not the default state.

Basic logging needs none of those invasive channels. Named gates, deliberate confirmations, Android permissions, screen-capture consent, and live session starts remain separate controls. A gate records authorization; it is not proof that its hardware exists or that capture is active. Persistent indicators show active AV rings and drive, flight, or control-link sessions.

## Follow local recording laws

Recording, privacy, wiretap, workplace, traffic, and RF rules vary by place and situation. Some places require every person being recorded to consent, often described as two-party or all-party consent. Apophenia cannot determine the operator's jurisdiction, decide whether a particular recording is lawful, or create legal authority. The operator must obtain any required consent and use each capture channel lawfully.

All retained evidence is local unless the operator builds an export, reviews its manifest, and deliberately chooses a route. Apophenia has no account, advertising SDK, analytics SDK, automatic cloud sync, background uploader, or background export retry worker.

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
- Microphone audio only after exact-name Tier-2 authorization, Android permission, and a foreground arm action. A persistent indicator remains while buffering is live. Raw PCM is never written unencrypted during capture; per-event AES-GCM ciphertext stays app-private under its retention deadline and is excluded from data-only export. A double-confirmed full-evidence export can deliberately materialize it as portable WAV.
- Main/front/multicam camera frames and screen contents only after their own exact-name gates, camera permission or per-arm MediaProjection consent, and foreground arm actions. Persistent indicators remain live. Each obtained stream is encrypted separately; unavailable cameras and call audio are reported as gaps rather than replaced with another channel.
- Encrypted AV artifacts have a configurable deadline (14 days by default). Keep-forever is per event. Scrub and expiry delete ciphertext, manifest, and the per-event Keystore key while preserving non-reconstructive derived metrics; a local ledger records the deletion. In-app playback verifies hashes and decrypts only in memory.
- Garmin communication through the paired-phone Connect IQ companion service.
- Optional Octopod requests to the user-configured local cluster. Apophenia stores aggregate counts and average temperature only—not entity/person names, camera images, raw audio/video, or Home Assistant/SmartThings/Wyze credentials.
- Optional on-device radio and presence snapshots. Wi-Fi stores a locally keyed BSSID hash, band/frequency, and RSSI while discarding SSIDs and raw BSSIDs. Bluetooth stores a locally keyed address hash, coarse advertised class/name category, and RSSI while discarding raw addresses and names. Network state stores connectivity, carrier/network type, roaming, and signal where Android exposes them; no cell ID is persisted.
- Health Connect through Android's local Health Connect provider when explicitly authorized.
- Operator-started MAVLink UDP/TCP traffic to or from the explicitly selected endpoint; no background connection is opened merely because the gate is enabled.
- Operator-started CRSF/GHST USB serial input and bounded Field-Kit/TAK UDP receive windows; no remote upload is added by these channels.

Android cloud backup excludes every Apophenia database, app-private file, preference, attachment, and AV/RF artifact. On Android 12+, a user-started device-to-device transfer may copy the local SQLite database to a replacement device through the operating system's protected transfer mechanism; device-bound AV/Tier-2 keys are not transferable, so the verified in-app backup is the complete authority-preserving route. Pre-Android-12 backup/transfer is excluded entirely because the platform cannot express those boundaries separately. A user-selected export is outside the app's local boundary once it is handed to another app or document destination.

Explicit phone-sensor, location, environment, Garmin, and rolling-recorder boundaries are identifiable in code. Simulation mode bypasses physical hardware while retaining storage and analysis behavior.

Not collected by default: radio/presence channels, microphone recordings, camera recordings, notifications, calendar, contacts, message metadata, or browsing history. Tier-2 gates additionally require typing the exact gate name and granting the separate Android permission or access screen.

Tiered export, raw SQLite snapshot, verified backup/restore, and delete-all controls are available in Settings. Preparing and previewing an artifact stays app-local. Exported files leave the app's local boundary only when the user chooses the Android sharesheet, Storage Access Framework save-as destination, or the separately gated **Push LAN** action. The LAN gate accepts a persisted document provider or a literal private/link-local HTTP(S) address; it has no background uploader or retry worker. Optional HTTP credentials are Keystore-encrypted at rest. Plain HTTP does not encrypt the bundle or Basic credentials on the local network; use HTTPS when available.

The default data-only bundle excludes the encrypted `sensitive_context` table, inbound attachment bytes, and raw AV by construction. A gate being enabled does not silently add those classes. A full-evidence package requires one confirmation before local preparation and a second confirmation after a manifest preview; it contains portable plaintext attachments, AV, and Tier-2 contents and must be handled accordingly. A full backup also contains that portable plaintext inside its nested evidence ZIP because Android Keystore keys are device-bound and cannot be exported. Hash verification provides integrity, not confidentiality. Restore verifies both manifest layers and content completeness before replacing live state, then re-encrypts protected content under fresh local keys.

A single-event dossier is deliberately complete for that event and can contain plaintext AV, Tier-2 contents, and retained RF IQ. Selected-event report mode redacts Tier-2 plaintext and omits raw AV by default; optional retained pre-event stills are explicit and manifest-labeled. Both routes remain app-local through preparation and preview, reject demo data, and disclose gaps rather than inventing missing evidence.

Omniprobe is a local inspection surface, not an export route. It may decrypt an event's authorized Tier-2 rows for on-screen review with the existing Keystore key, but it creates no plaintext file and sends nothing. Its raw-AV section is inventory only; playback remains in the memory-only evidence player. Its export section reports active seal and durable audit counts without presenting chooser handoff or endpoint acknowledgement as recipient retention.

Global and per-event seals add an exact-phrase release step; they do not disable the operator's export capability. Scrub-before-share builds a separately verified dossier copy with attachments, raw AV, and Tier-2 plaintext removed. EJECT is deliberately destructive: it accepts only a byte-complete SAF write or acknowledged configured LAN route, re-verifies the package, purges attachment/AV/RF files and AV keys, and then clears the local evidence tables. A partial EJECT export still wipes the entire local evidence store and is labeled accordingly. Export and deletion receipts remain; evidence content does not.

TOTAL_EVIDENCE and the session presets are capture-only controls. Even EVERYTHING excludes the LAN export gate. Arming a preset requests no Android permission, starts no connection or recording service, and sends no data; live services and screen consent remain separate operator actions. The persistent status strip distinguishes gate count from actual AV ring state.

Demo fixtures live in a separate SQLite database and are labeled **DEMO DATA** across the app. Every export entry point refuses that database; while demo mode is active, export still reads the canonical live store. Synthetic rows therefore cannot be mistaken for or bundled with live evidence.

Selecting **Log to Apophenia** in Android's sharesheet is an explicit inbound action. Shared text or one shared image becomes an observation attachment while the ordinary receipt-time context window freezes. The app copies the bytes into app-private storage, records a SHA-256 and sanitized name, and does not persist the provider URI. Attachment bytes are absent from data-only export and present only in full evidence, event dossiers, EJECT, and full backup; scrub-before-share removes them from its new dossier copy.

Tasker/intent integration is off by default behind two exact-name deliberate gates. The capture hook accepts only bounded labels/notes and assigns its own receipt timestamp. The export hook can only open the existing preview flow; it cannot route data in the background, skip the full-evidence warning, release seals, or invoke EJECT.

Do not attach an unredacted export to a public issue. Use GitHub's private security-advisory channel for an unintended disclosure or permission bypass.

## Total-capture posture

> **This is a personal total-capture instrument.** It records what you authorize, which can include audio, video from every camera, screen contents, messages, notifications, calendar, location, physiology, your vehicle, your aircraft, and the RF spectrum your own receivers can observe. It has no opinions about what you point it at. Nothing leaves your device automatically: exports are explicit, previewed, and tiered; a local export log records every export; an EJECT action exports everything and optionally wipes the store. Recording indicators show when capture is live. Where a capability is restricted, the app discloses the restriction rather than pretending the channel doesn't exist. You are the operator, owner, and only data subject.

That paragraph states the implemented v0.3 software posture. Physical-device, hardware, field, flight, provider, recipient-read, and destination-retention claims remain bounded by the validation evidence in `PROJECT_HANDOFF.md`. Gate presence and simulator results do not satisfy those claims.

Device identifiers use a locally keyed, versioned HMAC. Raw MAC addresses, BSSIDs, and adapter/system identifiers are not durable fields. Key rotation deliberately breaks future linkage to older hashes.
