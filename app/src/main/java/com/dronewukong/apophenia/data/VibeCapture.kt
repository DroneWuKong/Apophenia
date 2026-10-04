package com.dronewukong.apophenia.data

object VibeCapture {
    fun request(
        grade: VibeGrade,
        timestampMs: Long,
        note: String = "",
        egress: Boolean = false,
        origin: ObservationOrigin = ObservationOrigin.ANDROID
    ): ObservationCaptureRequest {
        require(!egress || grade == VibeGrade.JANKY) { "Egress requires VIBE=5" }
        return ObservationCaptureRequest(
            timestampMs = timestampMs,
            kind = ObservationKind.VIBE,
            label = if (egress) VibeGrade.EGRESS_LABEL else grade.renderedLabel,
            note = note,
            origin = origin,
            vibeRating = grade.rating,
            egress = egress
        )
    }
}
