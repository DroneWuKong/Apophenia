# SQLite schema contract

Apophenia's live database is `apophenia.db`. The current `PRAGMA user_version` is **11** and is also exposed as `ObservationDb.SCHEMA_VERSION`. Raw snapshots contain one checkpointed SQLite file; committed WAL pages are folded into it with `PRAGMA wal_checkpoint(FULL)` before copying.

## Tables

| Table | Purpose and stable identity |
| --- | --- |
| `observations` | Human event rows, including `kind`, timestamp-first text fields, origin/replay ID, nullable `vibe_rating`, and `egress`. Integer `id` is the event join key. |
| `context_samples` | Durable scalar evidence with observation/control identity, `source`, `metric`, value/unit, metadata, `capture_id`, `phase`, and optional `session_id`. Dense rows sharing `capture_id` are one evidence window. |
| `rolling_samples` | Bounded scratch values used to freeze pre-event/control windows. These are not independent observations. |
| `hypotheses` | Timestamped notes and pre-registrations: cohort, feature, direction, window, enablement, and result-lock time. |
| `hypothesis_evaluations` | Append-only results keyed by hypothesis and analysis signature, including adjusted p-value, counts, delta, comparison count, outcome, and summary. |
| `analysis_views` | First-view ledger for cohort/feature pairs, preventing retroactive pre-registration. |
| `sensitive_context` | Tier-2 ciphertext, IV, non-secret key alias, source/type, event/control identity, and `capture_id`. Plaintext is never stored here. |
| `capture_sessions` | `DRIVE_SESSION` or `FLIGHT_SESSION` boundary, hashed equipment identity, timestamps, status, and metadata. |
| `session_events` | Exact session transitions/status text with timestamp, type, severity, text, and metadata. |
| `media_assets` | Inventory for encrypted audio/video artifacts: owner event, stream/type, retention, status, app-private paths, key alias, ciphertext hash, and size. |
| `purge_ledger` | Durable deletion receipt for raw media; records reason, time, bytes, and that derived metrics remain. |
| `evidence_seals` | Current global/per-event deliberate-release flags with timestamp and event join where applicable. A seal changes confirmation, not capability. |
| `export_audit_log` | Append-only route outcomes: tier, route, observable outcome, bundle SHA-256/name, payload counts/flags, scope, and bounded detail. |
| `observation_attachments` | Inbound shared-text/image attachment inventory: owning event, receipt timestamp, MIME/name, app-private relative path, SHA-256, and byte count. The incoming content URI is never persisted. |

SQLite's internal `sqlite_sequence` is copied during verified restore so autoincrement identities continue beyond restored rows.

## Version history

- v2: `capture_id` and rolling samples.
- v3: origins/replay IDs and context phases.
- v4: VIBE rating and egress.
- v5: encrypted Tier-2 context.
- v6: drive/flight sessions and context joins.
- v7: session events.
- v8: media inventory and purge ledger.
- v9: pre-registration cohort/window/lock fields, evaluations, and analysis-view ledger.
- v10: global/per-event evidence seals and durable export/EJECT route audit.
- v11: app-private inbound share attachments joined to observations.

## Backup and restore compatibility

Restore accepts schema version 11 only. It refuses missing tables, failed SQLite integrity checks, corrupted or undeclared ZIP entries, mismatched protected-evidence/attachment counts, incomplete active AV or attachment payloads, and unexpected RF payloads before changing the live store. This strictness prevents a superficially valid but incomplete backup from becoming the new authority.

The schema documents stored software state. Rows produced by SIMULATION are not physical-device, deployment, field, or flight proof, and live demo fixtures reside in a different database that cannot enter live exports.
