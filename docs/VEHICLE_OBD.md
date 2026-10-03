# OBD-II drive sessions

`LIVE_VEHICLE_CAPTURE` is a standard, off-by-default gate. Enabling it does not connect to a car. The operator must start a drive session and select an ELM327-style Bluetooth adapter that was already paired in Android.

## Session boundary and identity

One live adapter connection creates one `DRIVE_SESSION`. The app stores a random session ID and a locally keyed hash of the adapter address; the raw address remains only in memory for the RFCOMM connection and is not written to SQLite or preferences. A clean operator stop marks the session completed. Service/process loss marks it interrupted. Whether the connection exactly matches an ignition cycle varies by adapter sleep behavior and requires per-vehicle physical validation.

While active, a foreground notification says that drive capture is live and provides an end-session action. The service records an OBD snapshot every ten seconds. Manually logged events and controls also poll immediately. All authorized phone context and cabin Bluetooth-presence rows collected during the connection receive the same session ID.

## Commands and coverage

The transport sends a conservative ELM327 initialization sequence (`ATZ`, echo/line/space/header off, automatic protocol), then polls:

- RPM `01 0C`, speed `01 0D`, engine load `01 04`;
- coolant `01 05`, intake `01 0F`, standard ambient temperature `01 46`;
- throttle `01 11`, fuel level `01 2F`;
- short/long bank-1 fuel trims `01 06` / `01 07`;
- control-module voltage `01 42`, with ELM `AT RV` fallback;
- stored DTCs mode `03` and pending DTCs mode `07`.

PID `01 70` is also probed for requested cabin/ambient coverage but labeled `manufacturer_or_vehicle_dependent`; unsupported or differently encoded responses are omitted, never reinterpreted as a standard value. Manufacturer PIDs are not universal. A physical checklist must record supported/unsupported commands for each adapter/vehicle pair.

## Evidence boundary

The software suite uses packed, spaced, and CAN-header response fixtures; validates PID formulas and P/C/B/U DTC decoding; proves gate-off bypass; and runs a simulated hashed-adapter session through schema-v6 storage. It does not prove Bluetooth compatibility, ignition-cycle boundaries, PID support, calibration, electrical safety, or behavior while driving. Perform first hardware validation parked with the vehicle secured and record adapter, vehicle, Android version, app commit, and every unsupported PID.
