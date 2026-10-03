# Apophenia 0.3.0 — Total Circumstances Mode

Apophenia 0.3 completes the 23-step Total Circumstances software implementation: **you record the event; the software records the circumstances.** It remains local-first and evidence-bounded. No export route runs without an explicit operator action, and simulator results are not hardware, field, flight, recipient-read, or destination-retention proof.

Highlights include graded VIBE/egress capture; independently gated phone, vehicle, UAS, RF, audio, video, screen, and protected-content channels; capture/session identity; bounded encrypted AV rings and retention; controls-aware association correction; immutable pre-registration; honest small-sample/refutation language; Omniprobe gap accounting; TOTAL_EVIDENCE presets; isolated demo stories; verified data/full/dossier/report/backup/EJECT exports; seals and route audit; Android share-to-log attachments; and deliberate Tasker capture/export-preview hooks.

Schema v11 adds `observation_attachments`. Shared text/images are copied to app-private storage with byte count and SHA-256; source URIs are not persisted. Data-only exports include metadata only. Full evidence, dossiers, EJECT, and backups include verified bytes; scrub-before-share removes them.

Tasker logging and export preparation are separate default-off Tier-2 gates. Capture uses receipt time and the normal context pipeline. Export intents can only open existing preview/confirmation flows and cannot choose a route, release a seal, invoke EJECT, or wipe in the background.

The current local software suite, lint, debug assembly, and emulator UI suite are recorded in [PROJECT_HANDOFF.md](PROJECT_HANDOFF.md). The dated physical-validation checklist remains mandatory for every phone, camera combination, radio, OBD adapter, MAVLink/control link, SDR, watch bridge, document provider, NAS, and controlled export destination.
