# Audio evidence ring

`LIVE_AUDIO_CAPTURE` is a deliberate Tier-2 gate. Authorizing the gate does not silently grant Android microphone access and does not start capture after reboot. The operator must arm the ring from the foreground. While armed, Android runs a microphone foreground service and Apophenia posts a persistent **Audio ring buffering live** indicator with a disarm action.

## Window contract

- mono 16 kHz PCM16 little-endian;
- 60 seconds of pre-event audio held in a bounded in-memory circular buffer;
- freeze begins at the timestamp supplied by the logging surface, before the observation waits for SQLite or WorkManager;
- a delayed external/watch timestamp more than five seconds from phone receive time is not mislabeled as current phone audio; no raw-audio artifact is created for that delayed event;
- 30 seconds of post-event audio per observation;
- multiple overlapping observations receive separate event artifacts;
- an encrypted pre-event checkpoint is written as soon as the observation receives its database ID, then atomically replaced by the complete pre/post artifact; and
- stopping the service finalizes attached events with whatever post window was actually obtained rather than inventing missing duration.

The service first requests Android's unprocessed microphone source and falls back to the normal microphone source when unsupported. That fallback is a platform capability difference, not an assertion that audio is acoustically unprocessed.

## Encryption and retention

Every event receives a distinct Android Keystore AES-256-GCM key alias. Plain PCM is never written to a file. App-private storage contains ciphertext plus a manifest with format, sample rate, pre/post byte counts, IV, key alias, SHA-256 of ciphertext, and retention deadline. The default is 14 days. Expired ciphertext, manifest, and Keystore entry are pruned when the audio subsystem starts or writes another artifact. Step 12 will add the user-facing retention ledger, keep-forever, scrub, and player controls.

## Derived context

After the post window finishes, the app writes permanent `audio_derived` context rows under one `capture_id`:

- one-second `audio_loudness_dbfs` curve;
- silence ratio;
- onset count;
- descriptive hum (40–120 Hz), voice-band (300–3400 Hz), high-frequency/RFI-candidate, and broadband energy in dBFS.

These features are descriptive receiver values, not speaker identification, transcription, a diagnosis, or proof of an RF source. Pre-event rows are `PRE`; post-event rows are `POST`, and the existing association engine excludes `POST` from predictor calculations.

## Validation boundary

The JVM suite proves circular-buffer order, timestamp-first freeze, exact pre/post separation, grouped phases, a deterministic spectral fixture, AES-GCM round trip, and authentication failure with the wrong key. The API 36 emulator proves UI/manifest integration only. It does not prove microphone fidelity, OEM foreground-service survival, real acoustic timing, power use, 90-second artifact completion under process death, or physical-device retention. Those remain dated phone tests.
