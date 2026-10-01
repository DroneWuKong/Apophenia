# Privacy and collection boundaries

Apophenia is local-first.

Stored locally: manual observations, phone context, rolling black-box samples, random controls, and Garmin event context delivered through the paired-phone companion channel.

The rolling buffer is bounded and pruned. It is not an unlimited surveillance log.

Network use:
- Open-Meteo only when location permission and the environment gate are enabled.
- Garmin communication through the paired-phone Connect IQ companion service.

Explicit phone-sensor, location, environment, Garmin, and rolling-recorder boundaries are identifiable in code. Simulation mode bypasses physical hardware while retaining storage and analysis behavior.

Not collected by default: microphone recordings, camera recordings, contacts, message contents, or browsing history.
