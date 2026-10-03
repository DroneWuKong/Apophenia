# Install and test Apophenia

This guide is for development-preview testers. Apophenia is not yet distributed through Google Play.

## Install remotely

The shortest path is to open the [latest development release](https://github.com/DroneWuKong/Apophenia/releases/tag/v0.3.0-preview.4) on the phone and download `app-debug.apk`. Open it and approve installation from that browser or file manager.

Alternatively, open the [Android Actions page](https://github.com/DroneWuKong/Apophenia/actions/workflows/android.yml), select the newest green run, and download/unzip the `apophenia-debug-apk` artifact before transferring it to the phone.

For a direct developer push over the local network, Android **Wireless debugging** must first be enabled and paired with the development computer. Merely sharing a Wi-Fi network does not authorize ADB access.

GitHub may require a signed-in account to download workflow artifacts. If Android reports that the package conflicts with an existing installation, the older APK was probably signed by a different debug key. Export any data you need, uninstall the older debug build, and install the new APK. Uninstalling deletes the app's local database.

## Five-minute acceptance test

### 1. First launch

- Expected: a **Choose your context** explanation appears.
- Choose **Review access** to open Settings or **Not now** to start with basic logging.
- Phone sensor access does not produce an Android runtime prompt. Location, notifications, and Health Connect are separate optional actions.

### 2. Timestamp-first logging

- Tap **THAT WAS WEIRD**.
- Open **Timeline**.
- Expected: a new **That was weird** entry appears immediately.

Also try **Observation**, **Headache**, **Light changed**, or **Other**. Custom observations should take no more than two taps plus text entry.

### 3. Context access

Open **Settings**.

- **Location + weather:** tap **Allow**, accept an Android location choice, then tap **Test**. Expected: a result or a specific network/location error, never a silent button.
- **Recorder notifications:** tap **Allow**. Expected on Android 13+: the native notification prompt appears.
- **Health Connect:** tap **Connect**. Expected on supported devices: Android's Health Connect permission UI appears and returns to a connected, partially connected, or denied state.
- **Bluetooth presence / Wi-Fi presence:** enable each gate, approve the Android Nearby/location prompts, then take a test snapshot. Expected: counts and RSSI or a specific permission/radio limitation. Raw addresses, names, SSIDs, and BSSIDs are never stored.
- **Network state:** enable the gate and test basic connectivity. The optional phone-state prompt adds modem signal/type where Android exposes it.
- **Device circumstances:** enable only the audio/display/power/time/Wi-Fi Direct/NFC rows you want. Foreground-app identity additionally requires Android Usage Access; without it the channel records an explicit platform gap.
- **Tier-2 contents:** enablement requires typing the exact enum gate name, then the corresponding Android access. Expected: the gate can remain visibly armed while denied access produces no content rows. A data-only export must contain no Tier-2 contents.
- **OBD-II drive session:** pair an ELM327-style adapter in Android first, enable the vehicle gate, choose the paired adapter, and confirm the persistent drive-capture notification. Do this parked and treat the first physical run as bench validation; simulator PID/DTC results are not a hardware claim.
- **Native Automotive properties:** on Automotive OS, enable the separate property gate and log a parked event. On a phone, Android Auto, or CarPlay projection-only host, expected behavior is an armed-but-unavailable gap—not fabricated vehicle values.
- **MAVLink flight session:** enable the gate, then deliberately start UDP, TCP, or an attached USB/SiK device. Expected: a persistent flight-capture notification appears immediately; a durable session begins only after a valid airframe heartbeat. SIMULATION verifies software flow only. Keep the aircraft disarmed for first physical transport/telemetry checks and record link/airframe testing separately from build evidence.
- **CRSF/GHST:** enable the control-link gate, select protocol/baud and the attached radio/transmitter USB device. Expected: a persistent indicator and CRC-valid link frames only. Compare values with the source display before trusting units.
- **Field-Kit:** enable the gate, configure the owned ESP32's UDP port, then use **Test window** while it broadcasts. Expected: a bounded snapshot or a specific no-datagram result; the listener is not continuous.
- **TAK:** enter the own CoT UID once (stored only as a hash), configure the multicast group/port, and test. Default results must be own-track only. The separate full-visible-traffic switch requires another confirmation and must label other tracks as visible on your connection.
- **Ground context:** enable the gate and test. A phone may lack a barometer; magnetic declination and solar phase require an authorized location. NOAA Kp/F10.7 also require the existing environment lookup gate and network access.
- **RF survey:** start an `rtl_tcp`-compatible Android driver for an owned OTG RTL-SDR, enter its endpoint/tuning/window settings, deliberately enable the Tier-3 gate, and test. First hardware work is receiver/USB bench validation only; dBFS is not calibrated RF power.
- **Audio ring:** type `LIVE_AUDIO_CAPTURE`, grant microphone and notification access, and confirm the persistent live indicator before logging an event. Wait 30 seconds for the full post window. First physical testing must compare the actual pre/post duration, encrypted artifact manifest, derived metrics, disarm action, screen-off survival, and battery use; emulator success is not microphone proof.
- **Camera rings:** authorize the desired main/front/multicam gates by exact name, grant camera access, arm, and confirm the persistent indicator. Compare the active stream/degradation display with the phone's actual lenses. Log an event and wait ten seconds; emulator frames do not prove physical multicam.
- **Screen ring:** authorize by exact name, arm, accept Android's MediaProjection dialog, and confirm the second persistent indicator. The consent is per arm. Verify app-switch/display-size behavior physically.
- **Call audio:** choose the applicable jurisdiction state and run the per-call capability check. The expected current result is either Android platform-restricted or **Locked by statute, not by Apophenia**; no audio should be fabricated.
- **Encrypted evidence media:** after an AV event finishes, open its event group in Settings. Play audio or review frames, mark the event keep forever, then return it to its original deadline. Scrub the event and confirm raw playback disappears while the purge ledger says derived metrics were kept. No plaintext playback file should appear in app storage.
- **Omniprobe:** log an event, then open **Settings → Omniprobe**. Expected: all planned gates are listed; captured values show source, phase, and `capture_id`; absent channels show a reason; live AV-ring state is visibly separate from the selected event; raw media shows a retention countdown or keep-forever state; and the export panel names the implemented manifest-preview tiers while stating that the audit ledger is not implemented yet. Do not treat a simulator gap classification as physical permission or hardware proof.
- **TOTAL_EVIDENCE + presets:** the master strip must remain visible on every tab and separately report armed gate count and real AV ring state. In Settings, a short preset tap must do nothing except explain the 1.5-second hold. Hold FIELD/DRIVE/HOME/EVERYTHING to arm, then verify Android permissions did not open and hardware services did not start. TOTAL_EVIDENCE must exclude `LIVE_EXPORT_LAN`; its audio ring should still say off until you explicitly start it. When that service starts in FIELD/DRIVE/EVERYTHING/TOTAL_EVIDENCE, verify the indicator says 120 seconds pre-event. Disarming the mode must preserve individual gates.
- **Demo mode:** enable it under Testing and wait for the fixture summary. Expected: the persistent strip says **DEMO DATA**, Timeline contains synthetic observations, Patterns exposes the fixture cohorts, and Omniprobe reads the demo event rows. Navigate between tabs and confirm the badge remains. Export must target live data only and must not contain fixture IDs. Reset demo fixtures, then disable the mode and confirm the original live timeline returns unchanged.

Basic observation logging must continue when every optional permission is denied.

### 4. Rolling recorder

- Turn on **Rolling black box**.
- Expected: Android shows the recorder notification and the app reports that capture is active.
- Leave it running, return later, and log an event.
- Expected: the app remains responsive and the timeline entry is created immediately.

Long-duration survival and OEM battery restrictions are physical-device validation items. Report the phone model and any battery-optimization setting that was active.

### 5. Simulation

- Enable **Simulation mode** in Settings.
- Log several observations and wait for control generation.
- Expected: the normal timeline/database/analysis path works without physical sensor, GPS, weather, Health Connect, or Garmin access.
- In **Patterns**, confirm **Egress · bailed** and **Bad vibes · stayed** appear as separate event classes when those fixtures exist. Every result must state how many features were tested and how many were eligible for Benjamini-Hochberg correction; weak corrected results must say **indistinguishable from noise**.
- Before opening a result, use **Hypothesis** on the Log screen to register a cohort, exact metric, direction, window, and expected association. Open that event class in **Patterns**: an eligible result must lock and report confirmed/not yet supported/refuted. Try registering the same exact feature after viewing it; the app must refuse to call it a pre-registration. Insufficient data must leave the earlier registration unlocked.
- With as little as one matched event/control pair, **Ambient differences to check** may appear. Every row must say it is descriptive and not adjusted evidence. With four or more pairs, result cards lead with plain language; binary presence reports percentages/relative frequency, small samples say **interesting, not yet established**, and corrected weak/refuted patterns use the controls-based good-news copy.

## Physical soak and watch delivery

For a real phone or Garmin watch, continue with [PHYSICAL_ACCEPTANCE.md](PHYSICAL_ACCEPTANCE.md). It covers the 48-hour foreground-recorder soak, screen-off/OEM battery behavior, pre/post separation, offline Garmin queue replay, duplicate prevention, and evidence to record without sharing private exports.

### 6. Export and delete

- Prepare **Data-only export**. Before choosing a route, verify the preview says raw AV **no** and Tier-2 **no**, lists `data/apophenia-data.json`, and shows file and final-ZIP SHA-256 values.
- Cancel once and confirm the preview explicitly deletes the prepared cache bundle.
- Prepare **Full evidence package** only with synthetic or disposable test content. Confirm the first warning, then verify the second preview says whether retained AV and Tier-2 rows are actually present. Purged media must not reappear.
- Test **Save as…** to an Android document folder and, where available, an attached USB/OTG provider. Reopen the ZIP and compare its manifest. Test **Share** only to a destination you control.
- The full package contains plaintext portable evidence. Do not attach it to a public issue. Device-bound Keystore keys are not exported.
- Test **Delete all local data** only after saving anything you want to keep.
- Expected: delete-all removes active encrypted AV files and their Keystore keys before clearing SQLite. This is destructive and is not a substitute for the later verified-backup/EJECT flow.

See [EXPORT.md](EXPORT.md) for the exact tier, manifest, route, and current implementation boundaries.

## Garmin acceptance test

Garmin testing requires a supported Epix Pro (Gen 2), Garmin Connect, and a matching watch build. Follow [GARMIN_EPIX_PRO.md](GARMIN_EPIX_PRO.md). Report Android-only results separately from watch/phone BLE results.

## What to include in a bug report

```text
App version/commit:
Phone model:
Android version:
LIVE or SIMULATION:
Recorder on or off:
Optional sources enabled:
Steps to reproduce:
Expected result:
Actual result:
Does basic one-tap logging still work?:
```

Redact notification content, timestamps, coordinates, health values, exported JSON, device identifiers, and Garmin identifiers before posting publicly. Use a private [security advisory](https://github.com/DroneWuKong/Apophenia/security/advisories/new) for a vulnerability or unintended data disclosure.
