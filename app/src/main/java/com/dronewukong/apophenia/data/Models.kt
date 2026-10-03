package com.dronewukong.apophenia.data

enum class ObservationKind { OBSERVATION, COINCIDENCE, HYPOTHESIS_NOTE, WEIRD, VIBE }
enum class ObservationOrigin { ANDROID, WIDGET, TILE, EXTERNAL, GARMIN, SIMULATION }
enum class ContextPhase { INSTANT, PRE, POST, CONTROL }
enum class CaptureSessionType { DRIVE_SESSION, FLIGHT_SESSION }
enum class CaptureSessionStatus { ACTIVE, COMPLETED, INTERRUPTED }
enum class MediaType { AUDIO, VIDEO }
enum class MediaStatus { ACTIVE, PURGED, MISSING }

data class MediaAsset(
    val id: String,
    val observationId: Long,
    val mediaType: MediaType,
    val streamId: String,
    val createdAtMs: Long,
    val retentionUntilMs: Long,
    val keepForever: Boolean = false,
    val status: MediaStatus = MediaStatus.ACTIVE,
    val ciphertextRelativePath: String,
    val manifestRelativePath: String,
    val keyAlias: String,
    val ciphertextSha256: String,
    val sizeBytes: Long
)

data class PurgeLedgerEntry(
    val id: Long = 0,
    val mediaId: String,
    val observationId: Long,
    val mediaType: MediaType,
    val purgedAtMs: Long,
    val reason: String,
    val bytesDeleted: Long,
    val derivedMetricsRetained: Boolean = true
)

enum class VibeGrade(val rating: Int, val renderedLabel: String) {
    GOOD(1, "Vibe good 🙂"),
    TOLERABLE(2, "Tolerable 😐"),
    BAD(3, "Bad 🙁"),
    FUCKED(4, "Fucked 😖"),
    FUCKY(5, "Fucky 😵‍💫");

    companion object {
        const val EGRESS_LABEL = "FUCK THIS, I'M OUT"

        fun fromRating(rating: Int): VibeGrade = entries.firstOrNull { it.rating == rating }
            ?: throw IllegalArgumentException("Vibe rating must be between 1 and 5")
    }
}

data class ObservationCaptureRequest(
    val timestampMs: Long,
    val kind: ObservationKind,
    val label: String,
    val note: String = "",
    val severity: Int? = null,
    val confidence: Int = 3,
    val origin: ObservationOrigin = ObservationOrigin.ANDROID,
    val externalEventId: String? = null,
    val vibeRating: Int? = null,
    val egress: Boolean = false
) {
    init {
        validateVibe(kind, vibeRating, egress)
    }
}

data class Observation(
    val id: Long = 0,
    val timestampMs: Long,
    val kind: ObservationKind,
    val label: String,
    val note: String = "",
    val severity: Int? = null,
    val confidence: Int = 3,
    val origin: ObservationOrigin = ObservationOrigin.ANDROID,
    val externalEventId: String? = null,
    val vibeRating: Int? = null,
    val egress: Boolean = false
) {
    init {
        validateVibe(kind, vibeRating, egress)
    }
}

data class ContextSample(
    val id: Long = 0,
    val timestampMs: Long,
    val observationId: Long? = null,
    val isControl: Boolean = false,
    val source: String,
    val metric: String,
    val value: Double,
    val unit: String,
    val metadata: String = "",
    val captureId: String = "",
    val phase: ContextPhase = if (isControl) ContextPhase.CONTROL else ContextPhase.INSTANT,
    val sessionId: String? = null
)

data class CaptureSession(
    val id: String,
    val type: CaptureSessionType,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val identityHash: String,
    val status: CaptureSessionStatus = CaptureSessionStatus.ACTIVE,
    val metadata: String = ""
)

data class SessionEvent(
    val id: Long = 0,
    val timestampMs: Long,
    val sessionId: String,
    val eventType: String,
    val severity: Int? = null,
    val text: String,
    val metadata: String = ""
)

/**
 * Encrypted Tier-2 content. Ciphertext and IV are the only content-bearing fields persisted.
 * These rows deliberately live outside context_samples so the normal data export cannot include
 * them by accident.
 */
data class SensitiveContextRecord(
    val id: Long = 0,
    val timestampMs: Long,
    val observationId: Long? = null,
    val isControl: Boolean = false,
    val source: String,
    val contentType: String,
    val ciphertextBase64: String,
    val ivBase64: String,
    val keyAlias: String,
    val captureId: String
)

data class Hypothesis(
    val id: Long = 0,
    val createdAtMs: Long,
    val eventLabel: String,
    val metric: String,
    val direction: String = "ANY",
    val enabled: Boolean = true,
    val note: String = "",
    val source: ObservationOrigin = ObservationOrigin.ANDROID
)

private fun validateVibe(kind: ObservationKind, vibeRating: Int?, egress: Boolean) {
    if (kind == ObservationKind.VIBE) {
        require(vibeRating != null && vibeRating in 1..5) {
            "VIBE observations require vibeRating from 1 through 5"
        }
        require(!egress || vibeRating == 5) { "Egress is a first-class VIBE=5 event" }
    } else {
        require(vibeRating == null && !egress) { "Vibe fields are valid only for VIBE observations" }
    }
}
