# Ground context and RF survey

Step 9 adds two independent, event-contingent channels. Neither converts software or simulator output into a claim about a physical sensor, antenna, receiver, field condition, or flight.

## Ground context

`LIVE_GROUND_CONTEXT_CAPTURE` is a standard gate. At event and control windows it records available Android barometer and magnetometer values, normalizes pressure to hPa, derives a bounded pressure trend from the preceding saved snapshot, and records magnetic-vector magnitude. With an authorized location fix it also records Android's platform geomagnetic declination and a locally computed solar-elevation phase.

Public space-weather lookup requires both the ground-context gate and the existing environment-lookup gate. The provider reads the latest Kp and 10.7 cm solar-radio-flux values from the official [NOAA SWPC Kp product](https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json) and [NOAA SWPC flux summary](https://services.swpc.noaa.gov/products/summary/10cm-flux.json), preserves each product's observation time, and caches a successful fetch for 15 minutes. A failed network lookup never blocks local capture.

Missing barometer, magnetometer, permission, location, or network data is omitted. No value is synthesized in LIVE mode.

## RF survey

`LIVE_RF_SURVEY_CAPTURE` is capability-conditional and needs a separate Tier-3 confirmation. The current Android transport connects to an `rtl_tcp`-compatible driver that the operator starts for an owned RTL-SDR/OTG receiver. The default endpoint is loopback (`127.0.0.1:1234`); host, port, center frequency, sample rate, window length, and retention are explicit settings.

For each event or control window the app:

1. verifies the `RTL0` protocol header;
2. sends center-frequency, sample-rate, and automatic-gain commands;
3. reads at most the configured 50–2000 ms window, capped at 4 MiB;
4. stores the interleaved unsigned 8-bit IQ in app-private storage;
5. inventories the file by opaque ID and SHA-256, never by an exported filesystem path;
6. records center/sample rate, byte count, RMS dBFS, peak dBFS, and peak frequency offset under one `capture_id`; and
7. prunes raw IQ older than the configured retention period whenever the channel runs.

The summary is receiver-relative dBFS, not calibrated field strength. It does not identify a transmitter or decode communications. Driver support, USB power, tuner limits, antenna response, RF calibration, and applicable law remain physical/operator constraints and must be recorded during hardware validation.

## Validation boundary

The unit suite proves gate bypass, pressure normalization/trend math, official-product parsing, deterministic ground fixtures, known-tone spectral offset, bounded capture metadata, SHA-256 inventory, app-private file handling, control/event grouping, and SIMULATION flow. It does not prove Android sensor accuracy, NOAA reachability on a field device, RTL-SDR compatibility, RF calibration, retention under OEM storage pressure, or lawful operation at a location.
