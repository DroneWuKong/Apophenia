# Physical acceptance and 48-hour soak

This checklist turns “it builds” into repeatable phone/watch evidence. It does not make Apophenia a medical device, and a completed emulator run is not a substitute for this checklist.

Record the phone model, Android version, OEM battery setting, app version, Garmin model/firmware, Garmin Connect version, start/end time, and any deviation. Do not attach a real JSON export, coordinates, health records, or private observation text to a public issue.

## Install and baseline

1. Download the APK from the tagged development pre-release or a successful GitHub Actions run.
2. Verify the published SHA-256 checksum, install the APK, and open it once.
3. Confirm basic logging works before granting optional access.
4. Exercise location, notifications, weather, and Health Connect through both denied and granted states where the phone supports them.
5. Confirm the app still logs after each denial and when airplane mode is enabled.

## Recorder acceptance

1. Enable **Rolling black box**. Confirm Android shows the ongoing foreground notification.
2. Keep the phone unplugged with its normal screen-off and battery settings for at least 35 minutes.
3. Reopen Settings. Confirm the recorder card shows a recent heartbeat and at least 30 minutes of buffered context.
4. Log a uniquely named test observation. Wait at least 31 minutes, then export locally and inspect it privately.
5. Confirm `PRE` samples precede the saved event timestamp and `POST` samples follow it. Confirm no `POST` sample is presented as a predictor.
6. Repeat after a reboot. The recorder must remain off after boot unless the user has separately chosen a supported boot behavior; the current app intentionally does not start it silently.

For the full soak, leave the explicitly enabled recorder running for 48 hours. Check the heartbeat after screen-off periods, overnight, after charging, after losing network, and after the OEM battery manager has had time to intervene. Record gaps rather than hiding them.

## Garmin acceptance

1. Pair an Epix Pro 42, 47, or 51 mm in Garmin Connect and install the matching signed watch app.
2. In Android Settings, confirm the named watch reports connected and tap **Open logger**.
3. Log one event with a memorable label on the watch. Confirm Android receives exactly one observation with the watch time, not the later phone receive time.
4. Disconnect the phone or enable airplane mode, log two different watch events, reconnect, and confirm both arrive once.
5. Reconnect again and confirm queued retries do not create duplicates.
6. Confirm unavailable watch metrics are absent rather than zero or fabricated.

## Controls and analysis

1. Enable neutral check-ins and respond **Nothing unusual** to at least one notification.
2. Confirm the prompted control and scheduled random controls appear through the same database/export path.
3. In Patterns, confirm comparisons use one-to-one matched controls and honestly report insufficient data when fewer than four matched pairs exist.
4. Confirm the report includes effect magnitude, evidence strength, a 95% interval, permutation seed/resolution, and FDR-adjusted probability where enough data exists.

## Pass boundary

A phone/watch combination is physically validated only when its completed record covers the relevant steps above. A pass on one device does not prove other OEM background policies, sensor availability, watch sizes, firmware versions, or Health Connect providers.

Use the privacy-safe report template in [INSTALL_AND_TEST.md](INSTALL_AND_TEST.md). Include screenshots of status surfaces and redacted timestamps if helpful; do not publish the database or export.
