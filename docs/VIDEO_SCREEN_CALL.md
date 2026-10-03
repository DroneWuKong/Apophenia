# Camera, screen, multicam, and call-audio capability

Step 11 adds deliberate main-camera, front-camera, multicam, and screen-record gates plus the capability-conditional call-audio gate. Authorization, Android permission/consent, and an armed foreground service remain separate states.

## Camera frame rings

The camera service captures a bounded two-frame-per-second JPEG stream for each active camera ID. Each stream has its own 15-second in-memory ring, lens tag, event artifact, Keystore AES-256-GCM key, ciphertext hash, and 14-day default retention deadline. Logging an observation freezes each pre-event ring at tap time and collects ten seconds of post-event frames. Delayed watch/import timestamps are not mislabeled as current phone video.

For `LIVE_MULTICAM_CAPTURE`, the app requests the largest concurrent camera set reported by Android's Camera2 capability API. Some phones expose only logical cameras, only a front/back pair, or no concurrent set. The app records the number of active streams and a degradation reason; it does not claim every physical lens is active when the platform withholds it. Every obtained stream remains a separate artifact.

This implementation is a low-rate MJPEG evidence stream, not high-frame-rate cinematic video. It favors a bounded cross-device ring over unsupported codec promises. Physical validation must measure capture cadence, exposure, thermal behavior, memory, and the exact lens set on every target phone.

## Screen ring

`LIVE_SCREENRECORD_CAPTURE` requires Android MediaProjection consent each time it is armed. A separate foreground service downsizes the display to at most 720 pixels wide, retains a two-frame-per-second 15-second ring, and uses the same ten-second post/event encryption and derivation pipeline. The consent token is used in memory and is not stored for reuse.

## Derived metrics

Permanent `video_derived` rows include per-frame mean brightness, normalized motion energy, frame-to-frame brightness/flicker delta, spatial row-banding score, and a scene-change flag. They are descriptive. At two frames per second the stream cannot estimate PWM frequency, so every capture records `video_pwm_frequency_observable=0`; the banding score is only a PWM-candidate cue. `POST` rows remain excluded from predictor calculations.

## Call audio

`LIVE_CALL_AUDIO_CAPTURE` exists as a Tier-3 gate with a per-call capability check and a user-configured consent-jurisdiction state. This build does not claim legal location detection. If the operator selects an all-party-consent jurisdiction, the UI says **Locked by statute, not by Apophenia** because no all-party attestation workflow exists. Otherwise it reports `PLATFORM_RESTRICTED`: ordinary modern Android apps do not receive the remote side of a cellular/VoIP call through a supported public capture API. The app never substitutes microphone audio and labels it call audio.

## Software validation boundary

The suite proves frame-ring bounds, per-stream pre/post separation, event grouping, delayed-timestamp rejection, motion/brightness/banding derivation, explicit PWM-frequency unavailability, and call-audio platform/statutory gap selection. The API 36 emulator validates settings and packaging, not camera concurrency, MediaProjection pixels, real frame cadence, lens identity, thermal behavior, microphone/call routing, or legal status.
