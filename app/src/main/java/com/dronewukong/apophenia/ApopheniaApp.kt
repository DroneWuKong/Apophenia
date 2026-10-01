package com.dronewukong.apophenia

import android.app.Application
import com.dronewukong.apophenia.garmin.GarminBridge
import com.dronewukong.apophenia.hardware.HardwareGates
import com.dronewukong.apophenia.work.ControlScheduler

class ApopheniaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HardwareGates.load(this)
        ControlScheduler.ensureScheduled(this)
        GarminBridge.initialize(this)
    }
}
