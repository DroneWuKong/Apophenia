package com.dronewukong.apophenia.vehicle

import android.content.Context
import com.dronewukong.apophenia.data.ContextSample

class ObdContextProvider(private val context: Context) {
    fun collect(observationId: Long?, isControl: Boolean): List<ContextSample> =
        DriveSessionManager.collect(context, observationId, isControl)
}
