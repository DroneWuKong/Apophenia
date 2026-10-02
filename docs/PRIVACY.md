# Privacy and collection boundaries

Apophenia is local-first.

Stored locally: manual observations, phone context, rolling black-box samples, random controls, and Garmin event context delivered through the paired-phone companion channel.

Optional Health Connect reads are permission-gated and fail-soft. When authorized, Apophenia may read recent heart rate, resting heart rate, sleep, steps, SpO2, and exercise duration. The app does not write Health Connect records and logging still works when Health Connect is missing, unsupported, denied, or empty.

The rolling buffer is bounded and pruned. It is not an unlimited surveillance log.

Network use:
- Open-Meteo only when location permission and the environment gate are enabled.
- Garmin communication through the paired-phone Connect IQ companion service.
- Health Connect through Android's local Health Connect provider when explicitly authorized.

Explicit phone-sensor, location, environment, Garmin, and rolling-recorder boundaries are identifiable in code. Simulation mode bypasses physical hardware while retaining storage and analysis behavior.

Not collected by default: microphone recordings, camera recordings, contacts, message contents, or browsing history.

JSON export and delete-all controls are available in Settings. Exported files leave the app's local boundary only when the user chooses a share destination.
