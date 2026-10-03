package com.dronewukong.apophenia.omniprobe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.data.MediaAsset
import com.dronewukong.apophenia.data.MediaType
import com.dronewukong.apophenia.data.Observation
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.data.PurgeLedgerEntry
import com.dronewukong.apophenia.data.SensitiveContextRecord
import com.dronewukong.apophenia.hardware.HardwareGates
import com.dronewukong.apophenia.phone.EncryptedContent
import com.dronewukong.apophenia.phone.SensitiveContentCipher
import com.dronewukong.apophenia.phone.SensitiveContextProvider
import com.dronewukong.apophenia.video.CallAudioCapability
import java.util.Locale

data class OmniprobeValue(
    val timestampMs: Long,
    val source: String,
    val metric: String,
    val renderedValue: String,
    val unit: String,
    val captureId: String,
    val phase: String,
    val metadata: String = ""
)

data class OmniprobeChannel(
    val gate: HardwareGates.Gate,
    val title: String,
    val values: List<OmniprobeValue>,
    val gapReason: HardwareGates.GapReason?,
    val gapDetail: String
) {
    val observed: Boolean get() = values.isNotEmpty()
}

data class OmniprobeSnapshot(
    val observation: Observation,
    val channels: List<OmniprobeChannel>,
    val unmatchedValues: List<OmniprobeValue>,
    val mediaAssets: List<MediaAsset>,
    val purgeEntries: List<PurgeLedgerEntry>,
    val exportState: String
) {
    val observedChannelCount: Int get() = channels.count(OmniprobeChannel::observed)
}

/**
 * Builds an evidence inventory from persisted rows. A missing row is never promoted into a
 * hardware claim: Omniprobe reports the narrowest gap that can be established locally.
 */
class OmniprobeInspector(
    private val context: Context,
    private val db: ObservationDb,
    private val cipher: SensitiveContentCipher = SensitiveContentCipher(),
    private val capabilityOverride: ((HardwareGates.Gate) -> HardwareGates.CapabilityState?)? = null
) {
    fun inspect(observation: Observation): OmniprobeSnapshot {
        val samples = db.contextForObservation(observation.id)
        val sensitive = db.sensitiveContextForObservation(observation.id)
        val media = db.mediaAssets(observation.id, includePurged = true)
        val claimedSamples = mutableSetOf<Long>()
        val channels = channelSpecs.map { spec ->
            val ordinary = samples.filter(spec.matches).also { rows -> claimedSamples += rows.map { it.id } }
                .map(::ordinaryValue)
            val protected = sensitive.filter { it.contentType in spec.sensitiveTypes }.map(::sensitiveValue)
            val mediaValues = media.filter(spec.matchesMedia).map(::mediaValue)
            val values = (ordinary + protected + mediaValues).sortedBy { it.timestampMs }
            val gap = if (values.isNotEmpty()) null else resolveGap(spec.gate)
            OmniprobeChannel(spec.gate, spec.title, values, gap, gap?.detail(spec.gate).orEmpty())
        }
        val unmatched = samples.filter { it.id !in claimedSamples }.map(::ordinaryValue)
        return OmniprobeSnapshot(
            observation = observation,
            channels = channels,
            unmatchedValues = unmatched,
            mediaAssets = media,
            purgeEntries = db.purgeLedger().filter { it.observationId == observation.id },
            exportState = "Data-only, full-evidence, and restorable-backup ZIPs require verified manifest previews before sharesheet, SAF, or gated LAN release. Raw SQLite snapshots are checkpointed and hash-previewed. Verified restore exists; the durable export-audit ledger remains step 22."
        )
    }

    private fun resolveGap(gate: HardwareGates.Gate): HardwareGates.GapReason {
        val declared = HardwareGates.status(
            context,
            gate,
            capabilityOverride?.invoke(gate) ?: capability(gate)
        ).gapReason
        if (declared == HardwareGates.GapReason.GATE_OFF) return declared
        if (gate == HardwareGates.Gate.LIVE_EXPORT_LAN) return HardwareGates.GapReason.BUILD_DISABLED
        if (declared != null) return declared
        return if (gate in sessionGates) HardwareGates.GapReason.NO_ACTIVE_SESSION
        else HardwareGates.GapReason.NO_SAMPLE_IN_WINDOW
    }

    private fun capability(gate: HardwareGates.Gate): HardwareGates.CapabilityState {
        if (gate == HardwareGates.Gate.LIVE_CALL_AUDIO_CAPTURE) return CallAudioCapability.capability(context)
        requiredPermission(gate)?.let { permission ->
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                return HardwareGates.CapabilityState.PERMISSION_DENIED
            }
        }
        val features = context.packageManager
        return when (gate) {
            HardwareGates.Gate.LIVE_BLUETOOTH_CAPTURE -> if (!features.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) HardwareGates.CapabilityState.HARDWARE_ABSENT else HardwareGates.CapabilityState.AVAILABLE
            HardwareGates.Gate.LIVE_NFC_CAPTURE -> if (!features.hasSystemFeature(PackageManager.FEATURE_NFC)) HardwareGates.CapabilityState.HARDWARE_ABSENT else HardwareGates.CapabilityState.AVAILABLE
            HardwareGates.Gate.LIVE_VIDEO_CAPTURE,
            HardwareGates.Gate.LIVE_VIDEO_SELFCAPTURE,
            HardwareGates.Gate.LIVE_MULTICAM_CAPTURE -> if (!features.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) HardwareGates.CapabilityState.HARDWARE_ABSENT else HardwareGates.CapabilityState.AVAILABLE
            HardwareGates.Gate.LIVE_CARPLAY_AUTOMOTIVE_CAPTURE -> if (!features.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) HardwareGates.CapabilityState.PLATFORM_RESTRICTED else HardwareGates.CapabilityState.AVAILABLE
            else -> HardwareGates.CapabilityState.AVAILABLE
        }
    }

    private fun requiredPermission(gate: HardwareGates.Gate): String? = when (gate) {
        HardwareGates.Gate.LIVE_LOCATION_CAPTURE -> Manifest.permission.ACCESS_COARSE_LOCATION
        HardwareGates.Gate.LIVE_BLUETOOTH_CAPTURE -> if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
        HardwareGates.Gate.LIVE_WIFI_CAPTURE -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        HardwareGates.Gate.LIVE_CALENDAR_CONTENTS_CAPTURE -> Manifest.permission.READ_CALENDAR
        HardwareGates.Gate.LIVE_CONTACTS_CONTENTS_CAPTURE -> Manifest.permission.READ_CONTACTS
        HardwareGates.Gate.LIVE_MESSAGE_METADATA_CAPTURE -> Manifest.permission.READ_SMS
        HardwareGates.Gate.LIVE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
        HardwareGates.Gate.LIVE_VIDEO_CAPTURE,
        HardwareGates.Gate.LIVE_VIDEO_SELFCAPTURE,
        HardwareGates.Gate.LIVE_MULTICAM_CAPTURE -> Manifest.permission.CAMERA
        else -> null
    }

    private fun ordinaryValue(sample: ContextSample) = OmniprobeValue(
        timestampMs = sample.timestampMs,
        source = sample.source,
        metric = sample.metric,
        renderedValue = if (sample.value % 1.0 == 0.0) sample.value.toLong().toString() else String.format(Locale.US, "%.3f", sample.value).trimEnd('0').trimEnd('.'),
        unit = sample.unit,
        captureId = sample.captureId.ifBlank { "unassigned" },
        phase = sample.phase.name,
        metadata = sample.metadata
    )

    private fun sensitiveValue(row: SensitiveContextRecord): OmniprobeValue {
        val rendered = runCatching {
            cipher.decrypt(
                EncryptedContent(row.ciphertextBase64, row.ivBase64, row.keyAlias),
                SensitiveContextProvider.associatedData(row.source, row.contentType, row.captureId)
            )
        }.getOrElse { "Encrypted content present; key unavailable" }
        return OmniprobeValue(
            row.timestampMs, row.source, row.contentType, rendered, "encrypted JSON",
            row.captureId.ifBlank { "unassigned" }, if (row.isControl) "CONTROL" else "INSTANT",
            "AES-GCM · ${row.keyAlias}"
        )
    }

    private fun mediaValue(asset: MediaAsset) = OmniprobeValue(
        timestampMs = asset.createdAtMs,
        source = "encrypted_media",
        metric = "${asset.mediaType.name.lowercase()}_${asset.streamId}",
        renderedValue = "${asset.status.name.lowercase()} · ${asset.sizeBytes} bytes",
        unit = "SHA-256 ${asset.ciphertextSha256.take(12)}…",
        captureId = "event:${asset.observationId}:media",
        phase = "PRE+POST",
        metadata = "retention_until_ms=${asset.retentionUntilMs};keep_forever=${asset.keepForever}"
    )

    private data class ChannelSpec(
        val gate: HardwareGates.Gate,
        val title: String,
        val matches: (ContextSample) -> Boolean = { false },
        val sensitiveTypes: Set<String> = emptySet(),
        val matchesMedia: (MediaAsset) -> Boolean = { false }
    )

    companion object {
        private fun ContextSample.sourceHas(token: String) = source.contains(token, ignoreCase = true)
        private fun ContextSample.metricStarts(token: String) = metric.startsWith(token, ignoreCase = true)
        private fun spec(gate: HardwareGates.Gate, title: String, match: (ContextSample) -> Boolean) = ChannelSpec(gate, title, match)

        private val channelSpecs = listOf(
            spec(HardwareGates.Gate.LIVE_SENSOR_CAPTURE, "Phone sensors") { it.sourceHas("sensor") && !it.metricStarts("ground_") },
            spec(HardwareGates.Gate.LIVE_LOCATION_CAPTURE, "Location") { it.sourceHas("location") || it.metric in setOf("latitude", "longitude", "location_accuracy_m") },
            spec(HardwareGates.Gate.LIVE_ENVIRONMENT_LOOKUP, "Weather lookup") { it.sourceHas("open_meteo") || it.metricStarts("weather_") },
            spec(HardwareGates.Gate.LIVE_GARMIN_BRIDGE, "Garmin bridge") { it.sourceHas("garmin") },
            spec(HardwareGates.Gate.LIVE_BLUETOOTH_CAPTURE, "Bluetooth presence") { it.metricStarts("bt_") || it.sourceHas("bluetooth") },
            spec(HardwareGates.Gate.LIVE_WIFI_CAPTURE, "Wi-Fi presence") { it.metricStarts("wifi_") && !it.metricStarts("wifi_p2p") },
            spec(HardwareGates.Gate.LIVE_NETWORK_STATE_CAPTURE, "Network state") { it.metricStarts("network_") || it.sourceHas("network") },
            spec(HardwareGates.Gate.LIVE_WIFI_P2P_CAPTURE, "Wi-Fi P2P") { it.metricStarts("wifi_p2p") },
            spec(HardwareGates.Gate.LIVE_NFC_CAPTURE, "NFC presence") { it.metricStarts("nfc_") },
            spec(HardwareGates.Gate.LIVE_AUDIO_METADATA_CAPTURE, "Audio metadata") { it.metricStarts("audio_") && !it.sourceHas("audio_derived") },
            spec(HardwareGates.Gate.LIVE_DISPLAY_INTERACTION_CAPTURE, "Display and interaction") { it.sourceHas("display") },
            ChannelSpec(HardwareGates.Gate.LIVE_NOTIFICATION_CONTENTS_CAPTURE, "Notification contents", sensitiveTypes = setOf("notification_contents")),
            ChannelSpec(HardwareGates.Gate.LIVE_CALENDAR_CONTENTS_CAPTURE, "Calendar contents", sensitiveTypes = setOf("calendar_contents")),
            ChannelSpec(HardwareGates.Gate.LIVE_CONTACTS_CONTENTS_CAPTURE, "Contacts contents", sensitiveTypes = setOf("contacts_contents")),
            ChannelSpec(HardwareGates.Gate.LIVE_MESSAGE_METADATA_CAPTURE, "Message metadata", sensitiveTypes = setOf("message_metadata")),
            spec(HardwareGates.Gate.LIVE_POWER_THERMAL_CAPTURE, "Power and thermal") { it.sourceHas("power") || it.metricStarts("battery_") || it.metricStarts("thermal_") || it.metricStarts("ram_") || it.metricStarts("cpu_") },
            spec(HardwareGates.Gate.LIVE_TIME_CONTEXT_CAPTURE, "Time context") { it.sourceHas("time") },
            spec(HardwareGates.Gate.LIVE_VEHICLE_CAPTURE, "OBD-II vehicle") { it.sourceHas("obd_elm327") || it.metricStarts("obd_") },
            spec(HardwareGates.Gate.LIVE_CARPLAY_AUTOMOTIVE_CAPTURE, "Android Automotive") { it.sourceHas("automotive") || it.metricStarts("automotive_") },
            spec(HardwareGates.Gate.LIVE_EV_CAPTURE, "EV adapter PIDs") { it.metricStarts("ev_") },
            spec(HardwareGates.Gate.LIVE_MAVLINK_CAPTURE, "MAVLink") { it.sourceHas("mavlink") || it.metricStarts("mavlink_") },
            spec(HardwareGates.Gate.LIVE_CRSF_GHST_CAPTURE, "CRSF / GHST") { it.sourceHas("control_link") || it.metricStarts("crsf_") || it.metricStarts("ghst_") },
            spec(HardwareGates.Gate.LIVE_FIELD_KIT_CAPTURE, "Field-Kit") { it.sourceHas("field_kit") || it.metricStarts("field_kit_") },
            spec(HardwareGates.Gate.LIVE_TAK_CAPTURE, "TAK own asset") { it.source == "tak_own" },
            spec(HardwareGates.Gate.LIVE_TAK_CAPTURE_FULL, "TAK visible traffic") { it.source == "tak_visible" },
            spec(HardwareGates.Gate.LIVE_GROUND_CONTEXT_CAPTURE, "Ground context") { it.metricStarts("ground_") || it.metricStarts("space_weather_") },
            spec(HardwareGates.Gate.LIVE_RF_SURVEY_CAPTURE, "RF survey") { it.sourceHas("rf_survey") || it.metricStarts("rf_") },
            ChannelSpec(HardwareGates.Gate.LIVE_AUDIO_CAPTURE, "Microphone evidence", matches = { it.sourceHas("audio_derived") }, matchesMedia = { it.mediaType == MediaType.AUDIO }),
            ChannelSpec(HardwareGates.Gate.LIVE_VIDEO_CAPTURE, "Main-camera evidence", matches = { it.sourceHas("video_derived") && it.metadata.contains("stream=camera_back") }, matchesMedia = { it.mediaType == MediaType.VIDEO && it.streamId.startsWith("camera_back") }),
            ChannelSpec(HardwareGates.Gate.LIVE_VIDEO_SELFCAPTURE, "Front-camera evidence", matches = { it.sourceHas("video_derived") && it.metadata.contains("stream=camera_front") }, matchesMedia = { it.mediaType == MediaType.VIDEO && it.streamId.startsWith("camera_front") }),
            ChannelSpec(HardwareGates.Gate.LIVE_MULTICAM_CAPTURE, "Multicam evidence"),
            ChannelSpec(HardwareGates.Gate.LIVE_SCREENRECORD_CAPTURE, "Screen evidence", matches = { it.sourceHas("video_derived") && it.metadata.contains("stream=screen") }, matchesMedia = { it.mediaType == MediaType.VIDEO && it.streamId.startsWith("screen") }),
            ChannelSpec(HardwareGates.Gate.LIVE_CALL_AUDIO_CAPTURE, "Call audio"),
            ChannelSpec(HardwareGates.Gate.LIVE_EXPORT_LAN, "LAN export")
        )

        private val sessionGates = setOf(
            HardwareGates.Gate.LIVE_VEHICLE_CAPTURE,
            HardwareGates.Gate.LIVE_EV_CAPTURE,
            HardwareGates.Gate.LIVE_MAVLINK_CAPTURE,
            HardwareGates.Gate.LIVE_CRSF_GHST_CAPTURE,
            HardwareGates.Gate.LIVE_FIELD_KIT_CAPTURE,
            HardwareGates.Gate.LIVE_TAK_CAPTURE,
            HardwareGates.Gate.LIVE_TAK_CAPTURE_FULL,
            HardwareGates.Gate.LIVE_RF_SURVEY_CAPTURE
        )

        private fun HardwareGates.GapReason.detail(gate: HardwareGates.Gate): String = when (this) {
            HardwareGates.GapReason.GATE_OFF -> "${gate.name} was not authorized."
            HardwareGates.GapReason.PERMISSION_DENIED -> "The required Android permission was not granted."
            HardwareGates.GapReason.PLATFORM_RESTRICTED -> "Android does not expose this capability on the current platform."
            HardwareGates.GapReason.HARDWARE_ABSENT -> "The current device reports that the required hardware is absent."
            HardwareGates.GapReason.LOCKED_BY_STATUTE -> "Locked by statute, not by Apophenia."
            HardwareGates.GapReason.BUILD_DISABLED -> "This build disables the live adapter."
            HardwareGates.GapReason.SIMULATION_MODE -> "No fixture produced a value for this channel in simulation."
            HardwareGates.GapReason.NO_SAMPLE_IN_WINDOW -> "The gate was available, but no value was captured in this event window."
            HardwareGates.GapReason.NO_ACTIVE_SESSION -> "No active device/session contributed a value to this event window."
        }
    }
}
