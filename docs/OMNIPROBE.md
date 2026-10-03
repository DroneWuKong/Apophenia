# Omniprobe

Omniprobe is the per-event accounting surface at **Settings → Omniprobe**. It answers two separate questions without blurring them:

1. What evidence is actually persisted for this event?
2. Why is a planned channel absent?

It inventories every `HardwareGates.Gate`, including channels whose later export or hardware work is not implemented. A gate declaration is not displayed as proof of a capture.

## Event inventory

For each gate, Omniprobe shows:

- the consent tier and exact gate name;
- every matched ordinary `context_samples` value, unit, source, phase, metadata, and `capture_id`;
- the decrypted value of a matching Tier-2 `sensitive_context` row when its on-device Keystore key is available;
- encrypted raw-AV inventory values for matching audio, camera, and screen streams;
- an explicit gap when no persisted value exists.

Rows that no current gate matcher recognizes appear in **Recorded outside the current gate map**. This is deliberate forward-compatibility: adding a provider cannot cause its persisted evidence to vanish merely because Omniprobe's taxonomy has not caught up.

## Gap vocabulary

The gate framework retains the capability gaps `GATE_OFF`, `PERMISSION_DENIED`, `PLATFORM_RESTRICTED`, `HARDWARE_ABSENT`, `LOCKED_BY_STATUTE`, `BUILD_DISABLED`, and `SIMULATION_MODE`. Omniprobe adds two non-error absence states:

- `NO_SAMPLE_IN_WINDOW`: the gate was available, but no matching value was persisted for this event window.
- `NO_ACTIVE_SESSION`: an adapter/session-backed gate was available, but no active drive, flight, link, detector, TAK, or RF-survey session contributed a value.

These states prevent an empty window from being mislabeled as absent hardware. Hardware and permission gaps are reported only when Android exposes enough local evidence to make that narrower statement. Call audio uses the configured jurisdiction plus the public-API capability stub, so **Locked by statute, not by Apophenia** remains distinct from an Android platform restriction.

## Live state, retention, and export boundary

The header shows the current audio/video ring state and pending freeze count. This is present-tense device state, clearly separated from the selected event's persisted rows.

The raw-evidence section shows registered media, status, stream, and a live retention countdown or `keep forever`. Event-specific purge-ledger rows remain visible after raw media is deleted and state whether derived metrics survived.

The export section is intentionally bounded to the implementation state. Data-only/full-evidence manifests and sharesheet/SAF routes exist, while raw-database restore, LAN push, and the durable audit ledger remain later steps. Omniprobe states that boundary instead of fabricating an empty audit history.

## Security boundary

Tier-2 values are decrypted only for the on-screen local inspection using the same AES-GCM associated data used at capture. Decryption failure is displayed as an unavailable key, never as empty content. Omniprobe does not export, upload, or create a plaintext file. Raw AV is inventoried; playback remains in the existing memory-only evidence player.

## Validation boundary

JVM fixtures cover stored-value/capture-group rendering, gate-off gaps, available-but-unsampled gaps, session gaps, unmatched-row preservation, and the statutory call-audio lock. The Compose smoke test opens the overlay and verifies its no-event state.

Those checks establish software behavior only. Permission classification, OEM feature reporting, physical AV/radio/vehicle/UAS hardware, and retention timing still require the dated physical-validation checklist in `PROJECT_HANDOFF.md`.
