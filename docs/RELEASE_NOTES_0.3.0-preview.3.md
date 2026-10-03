# Apophenia 0.3.0-preview.3

This development preview focuses on trustworthy delivery and visible context.

## Highlights

- The Epix Pro queue now waits for an Android SQLite receipt before deleting an event.
- The watch uses one vibration for capture and a distinct double pulse for confirmed phone storage.
- Timeline events expand into a **Context capsule** with source, pre/instant/post phase counts, and compact values.
- An optional Octopod observer attaches privacy-reduced Home Assistant/SmartThings and Wyze connectivity aggregates to events and controls.
- Garmin listener/device selection and nested batch decoding fixes from preview.2 remain included.

## Proven in software

- Android unit tests, lint, APK assembly, and emulator smoke tests.
- Connect IQ SDK 9.2.0 compilation for `epix2pro42mm`, `epix2pro47mm`, and `epix2pro51mm`.
- Six native Monkey C queue/receipt tests on the Epix Pro 47 mm simulator.

## Still requiring physical validation

- USB-disconnect processing and launch of the new 51 mm PRG.
- Watch event → Garmin Connect → Android SQLite → watch receipt.
- Offline/reconnect retry and receipt-loss deduplication.
- Octopod reachability from the physical phone and representative aggregate values.
