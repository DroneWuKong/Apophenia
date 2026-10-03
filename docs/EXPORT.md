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

It never reads `sensitive_context`, never decrypts raw AV, and does not include Tier-2 contents. Device identifiers remain the locally keyed hashes already stored in SQLite.

### Full evidence package

Full evidence requires two deliberate confirmations:

1. confirm that a plaintext evidence package should be built locally;
2. review its manifest, then choose **Share** or **Save as**.

In addition to the data-only payload, it contains every retained AV asset and Tier-2 record available at preparation time plus `inventories/omniprobe.json`, the per-event planned-channel/value/`capture_id`/gap accounting shown by Omniprobe. Audio is exported as mono PCM WAV. Video is exported as per-stream JPEG frames plus an index. Tier-2 records are decrypted into `tier2/contents.json`.

Omniprobe gap reasons reflect gate, permission, platform, and hardware state visible at export time. They explain the locally knowable gap but do not retroactively prove what physical hardware was present at the historical event.

The event Keystore keys are deliberately non-exportable Android keys. The full evidence package therefore contains the decrypted portable evidence, not unusable key aliases or extractable copies of those device-bound keys. Its ZIP is plaintext and must be handled as sensitive personal evidence. Purged or missing AV cannot be reconstructed and remains represented only by inventory, derived metrics, and purge history.

### Raw SQLite snapshot

The raw route creates `apophenia-sqlite-<timestamp>.db`. Before copying, the app runs `PRAGMA wal_checkpoint(FULL)`, refuses a busy checkpoint, copies the main database, reopens the copy read-only, requires `PRAGMA integrity_check=ok`, requires schema version 9, and checks the required table set. The preview shows schema version, row counts, byte size, and SHA-256 before Share or Save. No separate `-wal` file is needed because committed WAL pages were checkpointed into the copied database.

The `.db` contains ciphertext and metadata exactly as stored. It does not contain the app-private AV/RF files and is not, by itself, a complete restore bundle. The stable schema is documented in [SCHEMA.md](SCHEMA.md).

### Full restorable backup

The full backup is a verified outer ZIP containing:

- `database/apophenia.db`, checkpointed and integrity-checked;
- `database/schema.json`, declaring schema version 9 and the checkpoint contract;
- `portable/full-evidence.zip`, a separately verified full-evidence bundle used to recreate device-bound protected data;
- retained `rf/*.iq` windows present at backup time.

The outer manifest hashes every file. Because Android Keystore keys cannot be exported, retained AV and Tier-2 contents inside the nested portable evidence bundle are plaintext. Integrity hashes are not encryption.

Restore verifies the outer manifest, every nested manifest, SQLite integrity and required tables, schema version, protected-row counts, each active AV asset's portable sidecar/frame inventory, and RF file naming before isolating or mutating the live store. It then imports all application tables transactionally, re-encrypts portable AV and Tier-2 contents under fresh device-local keys, and restores RF files. A failed apply rolls back the prior database and AV/RF directories. Capture sessions must be disarmed before selection and are checked again at confirmation.

### Single-event dossier

A dossier scopes the evidence package to one selected live observation. It includes the observation and VIBE/egress fields, ordinary context, joined session events, event-specific Omniprobe inventory, context CSV, descriptive summary, derived-metric SVG, retained AV, Tier-2 contents, and retained RF IQ referenced by that event. RF bytes are checked against the SHA-256 recorded in context; expired/missing windows remain named in the RF inventory.

Because retained AV and Tier-2 contents are portable plaintext, preparation requires the deliberate evidence warning and routing still requires the manifest-preview confirmation. See [REPORTS.md](REPORTS.md).

### Selected-event report

Report mode accepts up to 100 selected live events and creates self-contained HTML plus a real Android-rendered PDF, machine-readable JSON, context CSV, and a derived-metric SVG. It carries the timestamped timeline, channel tables, Omniprobe gaps, honest result tiers, and stored pre-registration evaluations that apply to the selected cohorts. Tier-2 plaintext is redacted. Raw AV is excluded unless the operator explicitly requests retained pre-event stills; any included still is marked in the manifest.

Report selection is not a new causal analysis. When no eligible stored evaluation exists, the output says it is descriptive; small selections use **interesting, not yet established**. See [REPORTS.md](REPORTS.md).

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

## Not implemented in this step

The following remain separately reviewable work:

- sealed flags, durable export-audit ledger, and EJECT export-then-wipe (step 22).

Until those steps land, a manifest preview is proof of the prepared bundle's bytes—not proof that a destination retained them, not an export-audit record, and not an exactly-once-delivery claim.
