# Association-engine credibility

## Event classes

Patterns never rewrites observations to create an analysis story. It derives explicit cohorts at query time:

- **Egress · bailed**: `VIBE=5` with `egress=true`;
- **Bad vibes · stayed**: VIBE 3–5 with `egress=false`;
- ordinary descriptive labels, excluding egress rows so the special class is not silently duplicated.

Each cohort is compared with one-to-one controls matched by local four-hour block and weekday/weekend. The two VIBE cohorts remain separate, so leaving is not collapsed into feeling bad and staying.

## Per-device presence

The app already stores locally keyed BLE address and Wi-Fi BSSID hashes. The engine derives one binary feature for every stored hash:

- 1 when the hash appeared in that capture;
- 0 only when the corresponding Bluetooth/Wi-Fi aggregate row proves that channel ran and the hash did not appear;
- no value when the gate was off, permission was denied, the platform withheld a scan, or the channel otherwise produced no snapshot.

Raw MAC addresses, BSSIDs, names, and SSIDs are not reconstructed. Presence features retain the stored keyed hash so the same locally observed device can be compared across captures until key rotation.

## Multiple comparisons

One Patterns run can test base metrics, before-event deltas, lag windows, and device-presence features. Every result therefore states:

- total features tested, including insufficient-data features;
- features with enough matched event/control captures for a permutation p-value;
- the Benjamini-Hochberg false-discovery-rate method applied to eligible p-values;
- whether the corrected result is **indistinguishable from noise**.

Effect size remains separate from evidence strength. A large difference with weak corrected evidence is not promoted to a finding. Post-event values and hypothesis-note rows remain excluded, dense samples remain grouped by capture, and no result establishes causation.
