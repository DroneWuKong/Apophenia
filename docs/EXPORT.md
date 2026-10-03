# Export bundles

Apophenia never chooses an export destination on its own. Export preparation happens in app-private cache, the exact payload is hash-verified, and a manifest preview is shown before Android is allowed to release anything.

## Current routes

- **Android sharesheet:** one verified ZIP uses `ACTION_SEND`; the route helper also supports `ACTION_SEND_MULTIPLE` for later multi-artifact exports. Read access is granted only through the app `FileProvider` URI.
- **Save as:** Android's Storage Access Framework `ACTION_CREATE_DOCUMENT` writes the reviewed ZIP to the document destination chosen by the operator. That chooser can expose local folders, removable USB/OTG storage, or installed document providers; availability belongs to Android and the installed provider.

Neither route runs in the background. Building or opening a preview does not invoke either route.

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

- raw SQLite/WAL-checkpoint backup, verified restore/import, and LAN push (step 20);
- single-event dossier and HTML/PDF report mode (step 21);
- sealed flags, durable export-audit ledger, and EJECT export-then-wipe (step 22).

Until those steps land, a manifest preview is proof of the prepared bundle's bytes—not proof that a destination retained them, not an export-audit record, and not an exactly-once-delivery claim.
