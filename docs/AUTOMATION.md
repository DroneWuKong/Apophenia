# Inbound shares and automation intents

## Log to Apophenia

Android exposes **Log to Apophenia** for `ACTION_SEND` text and images. Choosing it is the explicit user action. The receiving activity records the receipt time and invokes the normal live observation repository immediately, so the AV/rolling pre-event window freezes before attachment copying or later enrichment.

Shared text or one shared image is copied into app-private `files/attachments`. The source content URI is not persisted. The row stores a sanitized display name, MIME type, exact byte count, and SHA-256; payloads larger than 25 MiB are refused while the already-created observation remains and reports the attachment failure. Data-only export contains inventory only. Full evidence, the owning event dossier, EJECT, and full backup include verified bytes. Scrub-before-share removes attachments from the scrubbed copy.

## Tasker observation action

`LIVE_TASKER_CAPTURE` is a default-off deliberate gate. After typing that exact gate name, Tasker or another local automation tool may send:

```text
action: com.dronewukong.apophenia.TASKER_LOG_OBSERVATION
optional string extras: label, note, kind, external_event_id
```

`kind` is limited to `OBSERVATION`, `WEIRD`, or `COINCIDENCE`. Label/note/event-ID lengths are bounded. Apophenia ignores caller-supplied timestamps and assigns receipt time, then uses the ordinary repository, rolling freeze, AV freeze, context enrichment, and post-event pipeline. When the gate is off, the broadcast creates no record. The older `com.dronewukong.apophenia.LOG_OBSERVATION` receiver remains signature-protected for same-signature integrations.

## Tasker export actions

`LIVE_TASKER_EXPORT` is a separate default-off deliberate gate. An automation may launch the exported main activity with one of these actions:

```text
com.dronewukong.apophenia.AUTOMATION_EXPORT_DATA
com.dronewukong.apophenia.AUTOMATION_EXPORT_FULL
```

The data action opens and builds the normal data-only preview. The full action stops at the normal first warning; the operator must still confirm local materialization, inspect the manifest, satisfy any seal release phrase, and choose a route. These actions cannot share, save, push LAN, release a seal, invoke EJECT, or wipe data in the background. When the gate is off, the app opens with an explicit blocked message and prepares nothing.

Android/OEM background-activity and broadcast behavior varies. These hooks are software-tested; representative Tasker versions, phone firmware, screen-off behavior, and provider grants remain physical acceptance items.
