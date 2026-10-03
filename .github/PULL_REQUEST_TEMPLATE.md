## What changed

Describe the user-visible problem and the focused change.

## Validation

- [ ] `testDebugUnitTest`
- [ ] `lintDebug`
- [ ] `:app:assembleDebug`
- [ ] `connectedDebugAndroidTest` when UI behavior changed
- [ ] Garmin compile/simulator tests when Garmin behavior changed

Commands and results:

```text

```

## Boundaries

- [ ] Observation timestamps are still stored before slow enrichment.
- [ ] Hypotheses remain separate from observations.
- [ ] Post-event samples are not used as predictors.
- [ ] SIMULATION still bypasses physical/external dependencies.
- [ ] Unavailable metrics are omitted rather than fabricated.
- [ ] No secrets, signing keys, SDK paths, exports, or personal data are included.

## Evidence level

State what was actually validated: source review, unit test, emulator, Garmin simulator, physical phone, physical watch, or field use. List remaining validation explicitly.
