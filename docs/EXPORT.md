# Export bundles

Apophenia never chooses an export destination on its own. Export preparation happens in app-private cache, the exact payload is hash-verified, and a manifest preview is shown before Android is allowed to release anything.

## Current routes

- **Android sharesheet:** one verified ZIP uses `ACTION_SEND`; the route helper also supports `ACTION_SEND_MULTIPLE` for later multi-artifact exports. Read access is granted only through the app `FileProvider` URI.
- **Save as:** Android's Storage Access Framework `ACTION_CREATE_DOCUMENT` writes the reviewed ZIP to the document destination chosen by the operator. That chooser can expose local folders, removable USB/OTG storage, or installed document providers; availability belongs to Android and the installed provider.
- **Explicit LAN push:** `LIVE_EXPORT_LAN` is a separate standard gate and is never armed by TOTAL_EVIDENCE or a preset. After the same manifest preview, **Push LAN** performs one foreground transfer to either a persisted Android document tree or a configured HTTP(S) endpoint. SMB/NFS support comes from an installed Android DocumentsProvider selected by the operator. Direct HTTP(S) accepts literal loopback, RFC1918, link-local, or IPv6 unique-local addresses only; DNS names, public addresses, redirects, and credentials embedded in URLs are rejected. Optional HTTP Basic credentials are AES-GCM encrypted under an Android Keystore key.

Plain HTTP is supported for operator-owned local endpoints but provides no transport encryption; HTTP Basic credentials and bundle bytes are visible to that local network. Prefer HTTPS when the destination can present a certificate Android trusts. Keystore protection applies to credentials at rest inside Apophenia, not while Basic credentials traverse plain HTTP.

Neither route runs in the background. Building or opening a preview does not invoke either route.

The LAN route also does not retry in the background. A 2xx response says that the endpoint answered that request; it is not proof of durable retention or exactly-once delivery.

## Content tiers

### Data-only (default)

The bundle contains `data/apophenia-data.json` with:

- observations and their context;
- the complete ordinary `context_samples` export, including controls, capture IDs, phases, session IDs, and permanent derived metrics;
- hypotheses and immutable evaluation snapshots;
- drive/flight session metadata and session events;
- raw-media inventory and the purge ledger.
- inbound share-attachment inventory (name, MIME, size, SHA-256), never the attachment bytes.

It never reads `sensitive_context`, never decrypts raw AV, and does not include Tier-2 contents. Device identifiers remain the locally keyed hashes already stored in SQLite.

### Full evidence package

Full evidence requires two deliberate confirmations:

1. confirm that a plaintext evidence package should be built locally;
2. review its manifest, then choose **Share** or **Save as**.

In addition to the data-only payload, it contains every retained AV asset, Tier-2 record, and inbound text/image attachment available at preparation time plus `inventories/omniprobe.json`, the per-event planned-channel/value/`capture_id`/gap accounting shown by Omniprobe. Audio is exported as mono PCM WAV. Video is exported as per-stream JPEG frames plus an index. Tier-2 records are decrypted into `tier2/contents.json`. Attachment bytes are hash-checked and indexed under `attachments/`; source content URIs are never exported because they are never stored.

Omniprobe gap reasons reflect gate, permission, platform, and hardware state visible at export time. They explain the locally knowable gap but do not retroactively prove what physical hardware was present at the historical event.

The event Keystore keys are deliberately non-exportable Android keys. The full evidence package therefore contains the decrypted portable evidence, not unusable key aliases or extractable copies of those device-bound keys. Its ZIP is plaintext and must be handled as sensitive personal evidence. Purged or missing AV cannot be reconstructed and remains represented only by inventory, derived metrics, and purge history.

### Raw SQLite snapshot

The raw route creates `apophenia-sqlite-<timestamp>.db`. Before copying, the app runs `PRAGMA wal_checkpoint(FULL)`, refuses a busy checkpoint, copies the main database, reopens the copy read-only, requires `PRAGMA integrity_check=ok`, requires schema version 11, and checks the required table set. The preview shows schema version, row counts, byte size, and SHA-256 before Share or Save. No separate `-wal` file is needed because committed WAL pages were checkpointed into the copied database.

The `.db` contains ciphertext and metadata exactly as stored. It does not contain the app-private AV/RF files and is not, by itself, a complete restore bundle. The stable schema is documented in [SCHEMA.md](SCHEMA.md).

### Full restorable backup

The full backup is a verified outer ZIP containing:

- `database/apophenia.db`, checkpointed and integrity-checked;
- `database/schema.json`, declaring schema version 11 and the checkpoint contract;
- `portable/full-evidence.zip`, a separately verified full-evidence bundle used to recreate device-bound protected data;
- retained `rf/*.iq` windows present at backup time.

The outer manifest hashes every file. Because Android Keystore keys cannot be exported, retained AV and Tier-2 contents inside the nested portable evidence bundle are plaintext. Integrity hashes are not encryption.

Restore verifies the outer manifest, every nested manifest, SQLite integrity and required tables, schema version, protected-row/attachment counts, each active AV asset's portable sidecar/frame inventory, every attachment hash, and RF file naming before isolating or mutating the live store. It then imports all application tables transactionally, re-encrypts portable AV and Tier-2 contents under fresh device-local keys, and restores attachment/RF files. A failed apply rolls back the prior database and AV/RF/attachment directories. Capture sessions must be disarmed before selection and are checked again at confirmation.

### Single-event dossier

A dossier scopes the evidence package to one selected live observation. It includes the observation and VIBE/egress fields, ordinary context, joined session events, event-specific Omniprobe inventory, context CSV, descriptive summary, derived-metric SVG, inbound share attachments, retained AV, Tier-2 contents, and retained RF IQ referenced by that event. Attachment and RF bytes are checked against stored SHA-256 values; expired/missing RF windows remain named in the RF inventory.

Because retained AV and Tier-2 contents are portable plaintext, preparation requires the deliberate evidence warning and routing still requires the manifest-preview confirmation. See [REPORTS.md](REPORTS.md).

### Selected-event report

Report mode accepts up to 100 selected live events and creates self-contained HTML plus a real Android-rendered PDF, machine-readable JSON, context CSV, and a derived-metric SVG. It carries the timestamped timeline, channel tables, Omniprobe gaps, honest result tiers, and stored pre-registration evaluations that apply to the selected cohorts. Tier-2 plaintext is redacted. Raw AV is excluded unless the operator explicitly requests retained pre-event stills; any included still is marked in the manifest.

Report selection is not a new causal analysis. When no eligible stored evaluation exists, the output says it is descriptive; small selections use **interesting, not yet established**. See [REPORTS.md](REPORTS.md).

## Evidence seals and scrub-before-share

A global seal applies to every export. A per-event seal applies to dossiers/reports containing that event; all-data bundles and EJECT also honor every event seal. Routing sealed evidence requires typing **RELEASE SEALED EVIDENCE** for that one route. The seal remains active afterward. Removing a seal separately requires typing **UNSEAL**.

An event dossier with raw AV, Tier-2 content, or inbound attachments offers **Scrub copy before share**. The verified replacement removes raw AV, plaintext Tier-2 payloads, and attachment bytes; redacts protected values from the Omniprobe inventory while retaining counts/gaps; gets a fresh manifest/hash; and leaves the original local evidence untouched.

## Durable export audit

Every route records what the app can honestly observe: time, tier, route, bundle SHA-256/name, payload count/bytes, AV/Tier-2 flags, scope, and outcome.

- sharesheet: `HANDOFF_TO_CHOOSER`; Android does not prove which recipient read or retained the URI;
- SAF/document provider: `WRITE_COMPLETED` only after the output stream returns the exact source byte count;
- direct HTTP LAN: `ENDPOINT_ACKNOWLEDGED` only after the exact request bytes and a 2xx response;
- EJECT: the route outcome plus a separate `WIPE_COMPLETED` receipt.

None of these outcomes claims destination durability or exactly-once delivery. Omniprobe and Settings show the local log.

## EJECT

EJECT builds a verified full-evidence package for **last hour**, **last 6 hours**, **last 24 hours**, or **all local evidence**. It includes selected observations/context/controls/sessions/hypotheses, Omniprobe inventories, protected contents, inbound attachments, retained AV, referenced hash-checked RF IQ, prior purge receipts, seals, and the export log as it existed at preparation time.

Confirmation 1 builds and previews the package. Confirmation 2 requires typing **EJECT AND WIPE** before selecting SAF or using configured LAN. Sharesheet is unavailable because opening a chooser is not proof of a completed transfer. Capture sessions must remain disarmed. Immediately before deletion, the app re-verifies the bundle and then requires every retained AV artifact/key purge plus attachment/RF-directory deletion to succeed before clearing observations, context, protected contents, hypotheses, sessions, inventories, and seals. Export and purge receipts remain locally.

Choosing a partial EJECT window still wipes the **entire** local evidence store; the UI states this before preparation and again before routing. The audit entry added after a route cannot be inside the already-transferred package, but remains in the local receipt-only store.

## Manifest contract

Every ZIP contains `manifest.json` using `apophenia.export.manifest.v1`. It declares:

- tier and creation timestamp;
- SQLite/data schema version;
- every payload path;
- exact uncompressed byte size and SHA-256 for every payload;
- per-file and bundle-level raw-AV and Tier-2 flags.

Before the preview opens, the app reopens the completed ZIP, rejects duplicate/unsafe/undeclared paths, recomputes every payload hash, and compares the recovered manifest with the in-memory plan. The preview shows every payload, size, SHA-256, content flags, and the SHA-256 of the final ZIP.

`manifest.json` does not list or hash itself; doing so would create a recursive digest. The final ZIP digest covers it.

## Cache behavior

Cancelling a preview deletes the prepared ZIP. A successful SAF copy deletes the app-cache copy. A sharesheet handoff keeps the cache file available long enough for the selected target to read its granted URI; Android may later evict cache files. The recipient becomes responsible for the exported copy.

Demo fixtures are structurally excluded: `ExportManager` refuses `apophenia-demo.db`, and the Settings export surface always reads the canonical live database even while the UI is in demo mode.

Inbound share-to-log and deliberate Tasker/intent hooks are implemented as step 23. See [AUTOMATION.md](AUTOMATION.md). A manifest preview proves only the prepared bytes; the audit uses bounded outcome names and never upgrades a handoff/acknowledgement into destination-retention proof.
