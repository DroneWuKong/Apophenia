# Optional Octopod home context

Apophenia can attach privacy-reduced home context from the existing Octopod cluster to both event and control captures. This is disabled by default.

## Data path

```text
Home Assistant / SmartThings ─┐
                              ├─ Octopod on the home cluster ─ GET /api/home
Wyze Bridge ──────────────────┘                              └ GET /api/cameras
                                                                    ↓
                                                     aggregate ContextSample rows
```

The phone defaults to `http://octopod.home`. Settings → **Octopod observer** lets the user enable, change, and test the endpoint. Cleartext networking is allowed only for the `octopod.home` local domain; a different address should use HTTPS.

## Stored values

- counts for open contacts, active motion, wet leak sensors, unlocked locks, lights on, presence, and low batteries;
- average home temperature;
- counts of connected/enabled Wyze cameras.

Names, entity IDs, person labels, camera names, images, video, audio, and cluster/service tokens are intentionally discarded. Octopod keeps upstream credentials on the cluster; the phone receives only its normalized read-only response.

The provider fails soft. No cluster, DNS, Wi-Fi, or source response can block observation logging. Simulation mode exercises the same provider/parser with synthetic aggregates. Physical cluster reachability and representative event/control captures remain unvalidated.
