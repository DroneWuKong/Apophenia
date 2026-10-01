package com.dronewukong.apophenia.data

enum class ObservationKind { OBSERVATION, COINCIDENCE, HYPOTHESIS_NOTE, WEIRD }

data class Observation(
    val id: Long = 0,
    val timestampMs: Long,
    val kind: ObservationKind,
    val label: String,
    val note: String = "",
    val severity: Int? = null,
    val confidence: Int = 3
)

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
    val captureId: String = ""
)

data class Hypothesis(
    val id: Long = 0,
    val createdAtMs: Long,
    val eventLabel: String,
    val metric: String,
    val direction: String = "ANY",
    val enabled: Boolean = true
)
