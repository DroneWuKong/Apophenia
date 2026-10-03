# Encrypted Tier-2 contents

Notification, calendar, contacts, and message-metadata channels require two independent actions:

1. type the exact gate name in Apophenia;
2. grant the corresponding Android permission or special access.

An armed gate without Android access remains visible and captures no contents. Notification capture reads the active notifications visible to Android's Notification Access service. Calendar capture is bounded to events overlapping -12 hours through +36 hours around the capture. Contacts capture snapshots phone/email rows. Message capture stores six hours of SMS metadata (address, timestamps, thread/type/read/status/subject); the body is excluded because this gate is explicitly a metadata channel.

Plaintext is assembled in memory at an event or control window, immediately encrypted using AES-256-GCM with an Android Keystore key, and only ciphertext, IV, key alias, source, content type, and capture identity enter SQLite. AES-GCM associated data binds the source, content type, and `capture_id`; moving a ciphertext row to another channel or capture fails authentication.

`sensitive_context` is a separate schema-v5 table. Observation deletion cascades to its rows, and delete-all clears it explicitly. The data-only exporter never queries or serializes this table. Full-evidence export is a separate path with double confirmation and a manifest preview; it decrypts these rows into a flagged plaintext payload only for that reviewed package.

Software tests prove gate-off bypass, deliberate authorization, ciphertext/plaintext separation, successful decryption with the correct associated data, rejection with altered associated data, database isolation/cascade deletion, and data-only export exclusion. They do not prove Keystore/OEM behavior or physical Android permission flows; those remain on the dated physical checklist.
