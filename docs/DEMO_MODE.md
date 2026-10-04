# Demo mode

Demo mode is a real, deterministic run through the observation, SQLite, matching, association, pre-registration, session, media-inventory, retention-ledger, Omniprobe, and UI paths. It is not live evidence.

## Isolation

Synthetic rows live in the separate `apophenia-demo.db` database. Turning demo mode on switches the application repository to that database and forces `SIMULATION`. Turning it off restores the previous runtime mode and the canonical `apophenia.db` repository. The live database is never cleared, copied into the demo database, or mixed into demo analyses.

The master strip carries **DEMO DATA** on every tab while the mode is active. Every fixture observation also uses origin `SIMULATION`, a versioned external ID, a `demo_fixture=true` context marker, and `demo:` capture IDs.

Every exporter refuses a demo database. While demo mode is active, the Settings export controls still target the canonical live database and identify that boundary. The live delete action is disabled. This is structural isolation, not a warning that depends on operator memory.

## Fixture corpus

`total-circumstances-v1` covers roughly 60 days and contains:

- 45 synthetic events;
- 120 jittered control capture groups;
- 60 current rolling-buffer samples;
- one immutable-style registered hypothesis created before the results;
- one completed own-airframe `FLIGHT_SESSION` with session events;
- one audio asset inventoried and then purged under retention, with its derived rows preserved.

The source-controlled manifest is `app/src/main/assets/demo/manifest.json`. `DemoFixtureInstaller` is the executable authority and can reset the demo database to the same deterministic corpus.

## Six stories

1. **Confirmed BLE presence:** the same synthetic keyed device is present at 14 of 16 `That was weird` events and 3 of 40 Bluetooth-capable control windows. The normal one-to-one matcher is still applied for analysis; the corrected device feature survives a family of ten decoy comparisons.
2. **Refuted as a win:** a timestamped registration expects lower sleep before bad-vibe/stayed events. The fixtures deliberately point the other way strongly enough to refute that registered direction, producing the controls-based good-news copy.
3. **Inconclusive weather front:** nine `Weather front weird` events have a 2× synthetic front-strength value. The engine retains its small-sample **Interesting, not yet established** language.
4. **Egress:** exactly two `NOPE, I'M OUT` events are valid `VIBE=5`, `egress=true` rows. Their nearby-device counts are higher and cell signal is lower than the bad-vibe/stayed fixtures.
5. **Flight session:** a bad vibe is timestamped 90 seconds before a mid-session event with marginal HDOP, low link margin, a Field-Kit threshold crossing, and elevated synthetic watch stress. The session has a hashed demo sysid and verbatim synthetic STATUSTEXT.
6. **AV transient:** pre-event audio-derived rows contain a high broadband ratio and loudness transient. The synthetic raw asset is recorded in the media inventory as retained, then purged; its purge ledger says derived metrics survived. No playable fake ciphertext is presented as raw evidence.

## Validation boundary

Unit tests verify corpus counts/span, raw BLE ratios, corrected association, registered refutation, small-sample weather wording, egress invariants, flight/vibe timing, AV purge survival, rolling samples, export refusal, and runtime restoration. The Compose test enables demo mode, waits for fixture installation, and verifies the badge survives tab navigation.

All values are synthetic. Demo success is not phone, permission, hardware, network, drive, field, or flight validation.
