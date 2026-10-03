# Reddit launch kit

This is written to be copied, edited, and posted after the release checklist is complete.

## Recommended title

> I made an open-source Android + Garmin “black box” for moments you want to investigate later

Alternative titles:

- I made Apophenia: one-tap event logging with a rolling sensor window and random controls
- I built a local-first Android app to log weird moments now and test patterns later

## Copy-ready post

> I kept running into the same problem: I would notice a headache, a sudden light or sound change, a coincidence, or just a “that was weird” moment—and only afterward try to remember what the conditions were.
>
> So I made **Apophenia**, an open-source Android app with an optional Garmin Epix Pro companion.
>
> The core interaction is intentionally simple: tap **THAT WAS WEIRD** (or a specific category) and the app saves the exact timestamp immediately. Any slower sensor, device, location, weather, Health Connect, or Garmin enrichment happens afterward.
>
> If you explicitly enable the rolling recorder, it keeps a bounded 30-minute local context buffer. When you log something, the preceding window is copied into durable event context and the app continues collecting a labeled post-event window. It creates random controls through the same pipeline and matches them one-to-one by time block and weekday/weekend. Optional neutral check-ins let you explicitly record “nothing unusual” without turning an unanswered notification into data.
>
> A few boundaries mattered to me:
>
> - local-first, with no account or analytics;
> - no continuous microphone or camera recording;
> - observations and hypotheses are stored separately;
> - dense sensor samples are grouped by event/control window;
> - post-event data is not used to “predict” the event;
> - correlations are described cautiously, never as proof of causation, diagnosis, or anything paranormal.
>
> Current features include one-tap logging, custom observations, a widget, Quick Settings tile, JSON export, phone sensors/device state, optional weather, optional read-only Health Connect data, simulation mode, and an Epix Pro (Gen 2) watch logger with an offline queue.
>
> **Current validation:** Android unit tests, lint, APK build, and two API 36 emulator tests are passing in GitHub Actions. The Garmin app compiles for the 42/47/51 mm Epix Pro targets, and four native queue tests pass in Garmin's simulator. Physical phone/watch integration still needs broader testing, so this is a development preview—not a medical app or a finished consumer release.
>
> Source: https://github.com/DroneWuKong/Apophenia
>
> Download: https://github.com/DroneWuKong/Apophenia/releases/tag/v0.3.0-preview.2
>
> I’d especially appreciate feedback on the one-tap flow, permission onboarding, battery behavior on different Android vendors, and whether the analysis language feels appropriately cautious. Please don’t post real location or health exports in public issues.

## Screenshot set

These are real captures from the Android debug app running in an emulator:

1. `docs/images/apophenia-log.png` — lead image; the one-tap idea is visible immediately.
2. `docs/images/apophenia-timeline.png` — demo observations and the evidence/type labels.
3. `docs/images/apophenia-patterns.png` — the honest “insufficient data” state and post-event warning.
4. `docs/images/apophenia-settings.png` — explicit, optional context access.
5. `docs/images/apophenia-onboarding.png` — the first-run permission explanation.

Use the first three for a concise gallery. Caption timeline and pattern captures as **emulator demo data**. Add a physical-watch photo only after confirming that the pictured build actually runs on that watch.

Do not use a screenshot containing real locations, health values, device identifiers, notification content, or personal observation notes.

## Before posting

- [ ] Merge the release commit to `main`.
- [x] Publish a GitHub pre-release with the APK and SHA-256 checksum.
- [x] Replace the download placeholder above.
- [ ] Verify the link while signed out of GitHub.
- [ ] State exactly which physical devices were tested.
- [ ] Keep “development preview” in the post until physical acceptance is complete.
- [ ] Check the target subreddit's self-promotion, flair, and link rules.
- [ ] Be ready to answer why random controls and hypothesis separation matter.

## Short reply for “what makes this different from a diary?”

> The timestamp is only the start. When enabled, Apophenia preserves a bounded pre-event window, collects comparable post-event context, and generates random baseline windows through the same pipeline. The analysis compares event-level captures with controls instead of treating every sensor sample as independent evidence. It is still exploratory, but it is built to resist the easiest correlation mistakes.

## Short reply for “is this making medical claims?”

> No. It is an experimental personal observation tool, not a medical device. It stores what you noticed and available context, then reports cautious associations. It does not diagnose, treat, predict, or establish causation.

## Short reply for “where does my data go?”

> Observations and context are stored locally. Weather is optional and requires location permission; Health Connect is optional and read-only; Garmin data arrives through Garmin Connect's companion channel. There is no account or analytics SDK. Export only happens when the user chooses a share destination.
