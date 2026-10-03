package com.dronewukong.apophenia

import android.app.Application
import com.dronewukong.apophenia.garmin.GarminBridge
import com.dronewukong.apophenia.hardware.HardwareGates
import com.dronewukong.apophenia.data.ObservationStore
import com.dronewukong.apophenia.work.ControlScheduler
import com.dronewukong.apophenia.work.PromptedCheckInScheduler
import com.dronewukong.apophenia.vehicle.DriveSessionManager

class ApopheniaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HardwareGates.load(this)
        ControlScheduler.ensureScheduled(this)
        PromptedCheckInScheduler.ensureScheduled(this)
        ObservationStore.repository(this)
        DriveSessionManager.reconcileProcessStart(this)
        GarminBridge.initialize(this)
    }
}
