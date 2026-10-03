# Native Automotive properties

`LIVE_CARPLAY_AUTOMOTIVE_CAPTURE` is the historical gate name for native head-unit/Automotive exposure. The implementation distinguishes three environments:

- **Android Automotive OS:** the app can ask the public `android.car` property service for values allowed by the vehicle image and granted car permissions.
- **Android Auto projection:** the phone projection API does not provide a general vehicle-property service.
- **Apple CarPlay projection:** Android has no CarPlay vehicle-property API.

The latter two remain visible as platform-restricted/host-absent states. Apophenia does not substitute phone GPS or inferred motion and call it native vehicle telemetry.

## Properties

The adapter probes public `VehiclePropertyIds` for:

- current cabin temperature, preserving every exposed HVAC area ID;
- outside temperature;
- vehicle speed;
- current gear code;
- fuel level in milliliters;
- EV battery level in watt-hours;
- odometer in kilometres.

Every scalar row stores the property name, area ID, Android Automotive source, and capture window. When an OBD drive session is active, native rows share its `session_id`; otherwise they remain event/control context without inventing a session.

## Portable implementation and degradation

The ordinary phone APK does not link an Automotive-only SDK library. A narrow reflection source loads the public `Car`, `VehiclePropertyIds`, `CarPropertyManager`, config, area, and `CarPropertyValue` methods only when the device declares `android.hardware.type.automotive`. Missing classes/methods, withheld properties, and per-property security failures return a capability gap and no value.

SIMULATION supplies typed fixtures for all seven metrics. Unit tests prove gate bypass, exact property/area provenance, complete fixture shape, and no invented rows for permission/projection gaps. They do not prove a vehicle image grants the declared car permissions or that any physical property is accurate. Representative Automotive OS hardware remains a dated physical-validation item.
