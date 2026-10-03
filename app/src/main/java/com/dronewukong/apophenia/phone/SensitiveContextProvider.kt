package com.dronewukong.apophenia.phone

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.dronewukong.apophenia.data.SensitiveContextRecord
import com.dronewukong.apophenia.hardware.HardwareGates
import org.json.JSONArray
import org.json.JSONObject

/** Captures explicitly authorized Tier-2 contents and encrypts them before returning any row. */
class SensitiveContextProvider(
    private val context: Context,
    private val cipher: SensitiveContentCipher = SensitiveContentCipher()
) {
    fun collect(
        observationId: Long?,
        isControl: Boolean,
        captureId: String = observationId?.let { "event:$it:instant" }
            ?: "control:${System.currentTimeMillis()}"
    ): List<SensitiveContextRecord> {
        val now = System.currentTimeMillis()
        return sensitiveGates.mapNotNull { (gate, contentType) ->
            if (!HardwareGates.isAuthorized(context, gate)) return@mapNotNull null
            val content = if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) {
                simulationContent(contentType, now)
            } else {
                liveContent(gate, now)
            } ?: return@mapNotNull null
            encryptedRecord(now, observationId, isControl, captureId, contentType, content)
        }
    }

    private fun encryptedRecord(
        now: Long,
        observationId: Long?,
        isControl: Boolean,
        captureId: String,
        contentType: String,
        plaintext: String
    ): SensitiveContextRecord {
        val source = if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) {
            "simulation/tier2"
        } else {
            "android_tier2"
        }
        val aad = associatedData(source, contentType, captureId)
        val encrypted = cipher.encrypt(plaintext, aad)
        return SensitiveContextRecord(
            timestampMs = now,
            observationId = observationId,
            isControl = isControl,
            source = source,
            contentType = contentType,
            ciphertextBase64 = encrypted.ciphertextBase64,
            ivBase64 = encrypted.ivBase64,
            keyAlias = encrypted.keyAlias,
            captureId = captureId
        )
    }

    private fun liveContent(gate: HardwareGates.Gate, now: Long): String? = when (gate) {
        HardwareGates.Gate.LIVE_NOTIFICATION_CONTENTS_CAPTURE -> notificationContents(now)
        HardwareGates.Gate.LIVE_CALENDAR_CONTENTS_CAPTURE -> calendarContents(now)
        HardwareGates.Gate.LIVE_CONTACTS_CONTENTS_CAPTURE -> contactsContents(now)
        HardwareGates.Gate.LIVE_MESSAGE_METADATA_CAPTURE -> messageMetadata(now)
        else -> null
    }

    private fun notificationContents(now: Long): String? {
        val notifications = NotificationCaptureService.activeSnapshot() ?: return null
        val rows = JSONArray()
        notifications.forEach { status ->
            val extras = status.notification.extras
            rows.put(JSONObject().apply {
                put("package", status.packageName)
                put("id", status.id)
                put("tag", status.tag)
                put("postedAtMs", status.postTime)
                put("category", status.notification.category)
                put("channelId", status.notification.channelId)
                put("title", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
                put("text", extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
                put("bigText", extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
                put("subText", extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
                put("infoText", extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString())
            })
        }
        return envelope(now, "active_notifications", rows, "active_snapshot")
    }

    private fun calendarContents(now: Long): String? {
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) return null
        val rows = JSONArray()
        val start = now - 12L * 60L * 60L * 1_000L
        val end = now + 36L * 60L * 60L * 1_000L
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.ORGANIZER,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.AVAILABILITY
        )
        runCatching {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                "${CalendarContract.Events.DTSTART}<=? AND (${CalendarContract.Events.DTEND} IS NULL OR ${CalendarContract.Events.DTEND}>=?)",
                arrayOf(end.toString(), start.toString()),
                "${CalendarContract.Events.DTSTART} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) rows.put(JSONObject().apply {
                    put("id", cursor.getLong(0))
                    put("title", cursor.stringOrNull(1))
                    put("description", cursor.stringOrNull(2))
                    put("location", cursor.stringOrNull(3))
                    put("organizer", cursor.stringOrNull(4))
                    put("startMs", cursor.longOrNull(5))
                    put("endMs", cursor.longOrNull(6))
                    put("allDay", cursor.getInt(7) == 1)
                    put("availability", cursor.intOrNull(8))
                })
            }
        }.getOrElse { return null }
        return envelope(now, "calendar_events", rows, "-12h_to_+36h")
    }

    private fun contactsContents(now: Long): String? {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return null
        val rows = JSONArray()
        val phoneProjection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL
        )
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                phoneProjection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) rows.put(JSONObject().apply {
                    put("contactId", cursor.getLong(0))
                    put("displayName", cursor.stringOrNull(1))
                    put("phone", cursor.stringOrNull(2))
                    put("phoneType", cursor.intOrNull(3))
                    put("phoneLabel", cursor.stringOrNull(4))
                })
            }
            val emailProjection = arrayOf(
                ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Email.ADDRESS,
                ContactsContract.CommonDataKinds.Email.TYPE,
                ContactsContract.CommonDataKinds.Email.LABEL
            )
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                emailProjection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) rows.put(JSONObject().apply {
                    put("contactId", cursor.getLong(0))
                    put("displayName", cursor.stringOrNull(1))
                    put("email", cursor.stringOrNull(2))
                    put("emailType", cursor.intOrNull(3))
                    put("emailLabel", cursor.stringOrNull(4))
                })
            }
        }.getOrElse { return null }
        return envelope(now, "contacts", rows, "full_address_book_snapshot")
    }

    private fun messageMetadata(now: Long): String? {
        if (!hasPermission(Manifest.permission.READ_SMS)) return null
        val rows = JSONArray()
        val start = now - 6L * 60L * 60L * 1_000L
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
            Telephony.Sms.STATUS,
            Telephony.Sms.SUBJECT
        )
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                "${Telephony.Sms.DATE}>=?",
                arrayOf(start.toString()),
                "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext()) rows.put(JSONObject().apply {
                    put("id", cursor.getLong(0))
                    put("threadId", cursor.getLong(1))
                    put("address", cursor.stringOrNull(2))
                    put("dateMs", cursor.getLong(3))
                    put("type", cursor.getInt(4))
                    put("read", cursor.getInt(5) == 1)
                    put("status", cursor.getInt(6))
                    put("subject", cursor.stringOrNull(7))
                })
            }
        }.getOrElse { return null }
        return envelope(now, "message_metadata", rows, "previous_6h;body_excluded_by_channel_contract")
    }

    private fun simulationContent(contentType: String, now: Long): String = envelope(
        now,
        contentType,
        JSONArray().put(JSONObject().put("fixture", contentType).put("secret", "tier2-simulation")),
        "simulation"
    )

    private fun envelope(now: Long, kind: String, rows: JSONArray, window: String): String =
        JSONObject()
            .put("capturedAtMs", now)
            .put("kind", kind)
            .put("window", window)
            .put("rows", rows)
            .toString()

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun android.database.Cursor.stringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun android.database.Cursor.longOrNull(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private fun android.database.Cursor.intOrNull(index: Int): Int? =
        if (isNull(index)) null else getInt(index)

    companion object {
        private val sensitiveGates = linkedMapOf(
            HardwareGates.Gate.LIVE_NOTIFICATION_CONTENTS_CAPTURE to "notification_contents",
            HardwareGates.Gate.LIVE_CALENDAR_CONTENTS_CAPTURE to "calendar_contents",
            HardwareGates.Gate.LIVE_CONTACTS_CONTENTS_CAPTURE to "contacts_contents",
            HardwareGates.Gate.LIVE_MESSAGE_METADATA_CAPTURE to "message_metadata"
        )

        fun associatedData(source: String, contentType: String, captureId: String): String =
            "$source|$contentType|$captureId"
    }
}
