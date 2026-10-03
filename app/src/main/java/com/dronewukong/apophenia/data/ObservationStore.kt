package com.dronewukong.apophenia.data

import android.content.Context

/** Application-scoped access to the canonical repository/database pair. */
object ObservationStore {
    @Volatile private var instance: ObservationRepository? = null

    fun repository(context: Context): ObservationRepository =
        instance ?: synchronized(this) {
            instance ?: ObservationRepository(context.applicationContext).also { instance = it }
        }
}
