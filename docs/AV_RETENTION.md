# AV retention and encrypted evidence player

## Contract

Audio, camera, and screen freezes are raw evidence, not predictor rows. They stay encrypted in app-private storage and are registered in schema v8. The permanent analysis inputs are the derived audio/video `context_samples`; `POST` metrics remain excluded from predictors.

The default raw-media deadline is 14 days. Settings accepts 1–3650 days for new captures. Changing the default does not silently extend old evidence. **Keep forever** is per event and pauses deadline enforcement; switching it off restores the original deadline.

## Scrub and expiry

Both paths use `MediaRetentionManager`:

1. resolve only relative paths contained by the app files directory;
2. delete ciphertext and manifest;
3. delete the per-event Android Keystore entry;
4. mark the media row purged;
5. append a ledger row with reason, time, bytes deleted, and `derived_metrics_retained=true`.

Expiry runs at process start and when an AV capture service starts. A process-wide lock coordinates purge and artifact registration. Once an artifact ID is purged, a late post-window finalizer is refused and its newly written ciphertext/key are removed. This is at-most-local-state coordination, not an exactly-once-delivery claim.

**Scrub event** removes every active raw audio/video stream for that observation. It does not remove derived loudness, spectral, motion, brightness, flicker, banding, or scene-change metrics. **Delete all local data** first scrubs all active media and keys, then clears SQLite.

## Player

The Settings evidence library lists active media by event, stream, size, and deadline. Before playback it verifies the ciphertext SHA-256 against both SQLite and the encrypted artifact manifest. Audio PCM and MJPEG archives are decrypted only into memory. Audio uses an in-memory `AudioTrack`; video frames are decoded from the in-memory archive. The player never writes a plaintext media file.

## Software validation boundary

JVM tests cover schema migration, inventory state, expiry, keep-forever, scrub, purge-ledger persistence, preservation of derived rows, path containment, and late-finalizer anti-resurrection. Emulator UI checks cover Settings reachability and interaction structure. These do not prove physical microphone/camera quality, OEM process survival, Keystore durability across updates, storage pressure behavior, playback fidelity, thermal cost, or hardware-specific multicam behavior. Run the dated checklist in `PROJECT_HANDOFF.md` on representative phones.
