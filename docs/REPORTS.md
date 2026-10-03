# Event dossiers and selected-event reports

Apophenia produces both artifacts locally, verifies their manifests, and shows the exact files, sizes, hashes, and sensitive-content flags before a route becomes available. Neither preparation nor preview sends data anywhere.

## Single-event dossier

A dossier is the complete portable reconstruction of one live observation. Its verified ZIP contains:

- the observation, VIBE/egress fields, ordinary context rows, session events, and raw-media inventory;
- an event-scoped Omniprobe inventory with values, `capture_id` values, and explicit gaps;
- a plain-language evidence-boundary summary, context CSV, and permanent pre/instant derived-metric SVG;
- every retained encrypted AV asset materialized into portable WAV/JPEG evidence;
- every associated Tier-2 content row materialized as plaintext; and
- retained RF IQ windows referenced by the event, after their bytes are checked against the SHA-256 stored in context. Missing or expired windows remain explicit in the RF inventory.

Dossiers therefore require a deliberate preparation confirmation and the ordinary manifest-preview route confirmation. The ZIP is plaintext and can contain highly sensitive evidence.

## Selected-event report

Report mode accepts up to 100 selected live observations and produces one verified ZIP containing self-contained HTML and a real Android-rendered PDF, plus machine-readable event JSON, context CSV, and an SVG of permanent audio/video-derived metrics. Both human formats include:

- a timestamped event timeline;
- VIBE/egress state where present;
- per-event channel coverage, stored values, and Omniprobe gaps/reasons;
- descriptive derived-metric charts; and
- stored hypothesis-evaluation wording and evidence tiers when a relevant pre-registration exists.

If no eligible stored evaluation applies, the report says that the selection is descriptive only; selections under ten events are labeled **interesting, not yet established**. Selection itself is never presented as a new causal analysis.

Tier-2 plaintext is intentionally redacted from report mode. The report states only that protected values exist. Raw AV is omitted by default; the operator can deliberately include one retained pre-event still per selected event. Those stills are marked as raw AV in the manifest. Full protected evidence belongs in a dossier or full-evidence package.

## Evidence boundary

Post-event rows remain useful for reconstruction and remain in event tables, but never enter the derived predictor chart. Correlation does not establish causation. A gap reason records what the app could establish at report time and does not retroactively prove physical hardware state.

Demo rows cannot enter either route. Both managers reject the demo database, and the Settings surface reads only the canonical live store.
