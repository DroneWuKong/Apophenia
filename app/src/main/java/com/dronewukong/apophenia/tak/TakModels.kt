package com.dronewukong.apophenia.tak

internal data class CotTrack(
    val uid: String,
    val type: String,
    val eventTimeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val haeM: Double,
    val circularErrorM: Double,
    val linearErrorM: Double,
    val courseDeg: Double?,
    val speedMps: Double?,
    val callsignPresent: Boolean
)
