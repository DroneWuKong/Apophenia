# Radio context

Apophenia can optionally attach a privacy-reduced radio-environment snapshot to events and control captures.

## What the phone can observe

- visible Wi-Fi access-point count, 2.4/5/6 GHz band counts, and mean/strongest RSSI;
- visible cellular count, serving-cell count, radio-technology counts, and mean/strongest signal where Android exposes it.

The collector stores one aggregate capture, not one statistical observation per access point, BLE device, or cell. Event and random-control captures use the same provider.

## What is deliberately discarded

- SSIDs and BSSIDs;
- cell identifiers and carrier-specific identity fields;
- raw scan rows.

The Settings toggle is off by default. Android requires precise location for Wi-Fi/cellular scan results. Permission denial, disabled radios, missing telephony, scan throttling, and OEM background restrictions all fail soft.

Bluetooth now has its own explicit gate and persistence contract; see [BLUETOOTH_CONTEXT.md](BLUETOOTH_CONTEXT.md).

## Important limitation

This is a radio survey, not a spectrum analyzer. Android exposes observations made by the phone's Wi-Fi, Bluetooth, and cellular stacks; it does not provide raw arbitrary-frequency power spectra. Capturing other bands or waterfall/IQ data requires an explicitly supported external receiver such as an RTL-SDR or HackRF, ideally through a separately permissioned local observer node.

Wi-Fi and cellular APIs can return cached or rate-limited results, and Android limits background scans. These metrics are contextual signals, not calibrated RF measurements.
