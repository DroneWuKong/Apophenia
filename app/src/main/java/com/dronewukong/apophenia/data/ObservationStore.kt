package com.dronewukong.apophenia.data

import android.content.Context
import com.dronewukong.apophenia.demo.DemoModeManager

/** Application-scoped access to the canonical repository/database pair. */
object ObservationStore {
    @Volatile private var liveInstance: ObservationRepository? = null
    @Volatile private var demoInstance: ObservationRepository? = null

    fun repository(context: Context, demo: Boolean = DemoModeManager.state.value.active): ObservationRepository =
        if (demo) demoRepository(context) else liveRepository(context)

    fun liveRepository(context: Context): ObservationRepository = liveInstance ?: synchronized(this) {
        liveInstance ?: ObservationRepository(context.applicationContext).also { liveInstance = it }
    }

    fun demoRepository(context: Context): ObservationRepository = demoInstance ?: synchronized(this) {
        demoInstance ?: ObservationRepository(context.applicationContext, DemoModeManager.DATABASE_NAME).also { demoInstance = it }
    }

    /** Test-only lifecycle hook for Robolectric process reuse. */
    @Synchronized
    internal fun resetForTests() {
        ObservationRepository.awaitIdleForTests()
        liveInstance?.db()?.close()
        demoInstance?.db()?.close()
        liveInstance = null
        demoInstance = null
    }
}
