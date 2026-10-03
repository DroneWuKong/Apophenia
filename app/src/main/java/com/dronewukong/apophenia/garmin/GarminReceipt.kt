package com.dronewukong.apophenia.garmin

/** Wire payload sent only after the Android observation transaction succeeds. */
object GarminReceipt {
    fun payload(eventId: String): Map<String, Any> = mapOf(
        "v" to 1,
        "type" to "receipt",
        "event_ids" to listOf(eventId)
    )
}
