package com.dronewukong.apophenia.hardware

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.dronewukong.apophenia.data.ContextSample
import java.util.concurrent.ConcurrentHashMap

class SensorSnapshotCollector(private val context: Context) {
    data class Spec(val type: Int, val names: List<String>, val units: List<String>)

    private val specs = listOf(
        Spec(Sensor.TYPE_PRESSURE, listOf("pressure_hpa"), listOf("hPa")),
        Spec(Sensor.TYPE_LIGHT, listOf("ambient_light_lux"), listOf("lux")),
        Spec(Sensor.TYPE_PROXIMITY, listOf("proximity_cm"), listOf("cm")),
        Spec(Sensor.TYPE_AMBIENT_TEMPERATURE, listOf("ambient_temperature_c"), listOf("C")),
        Spec(Sensor.TYPE_RELATIVE_HUMIDITY, listOf("relative_humidity_pct"), listOf("%")),
        Spec(Sensor.TYPE_ACCELEROMETER, listOf("accel_x","accel_y","accel_z"), listOf("m/s2","m/s2","m/s2")),
        Spec(Sensor.TYPE_GYROSCOPE, listOf("gyro_x","gyro_y","gyro_z"), listOf("rad/s","rad/s","rad/s")),
        Spec(Sensor.TYPE_MAGNETIC_FIELD, listOf("mag_x","mag_y","mag_z"), listOf("uT","uT","uT"))
    )

    fun collect(observationId: Long?, isControl: Boolean, windowMs: Long = 900): List<ContextSample> {
        if (!HardwareGates.sensorsEnabled) return SimulationContext.sensorSamples(observationId, isControl)
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val latest = ConcurrentHashMap<Int, FloatArray>()
        val thread = HandlerThread("apophenia-sensor-snapshot").apply { start() }
        val handler = Handler(thread.looper)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) { latest[event.sensor.type] = event.values.copyOf() }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        try {
            specs.forEach { spec -> sm.getDefaultSensor(spec.type)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, handler) } }
            Thread.sleep(windowMs)
        } finally {
            sm.unregisterListener(listener)
            thread.quitSafely()
        }
        val now = System.currentTimeMillis()
        return specs.flatMap { spec ->
            val values = latest[spec.type] ?: return@flatMap emptyList()
            spec.names.mapIndexedNotNull { i, name -> values.getOrNull(i)?.toDouble()?.let { value ->
                ContextSample(timestampMs=now, observationId=observationId, isControl=isControl, source="android_sensor", metric=name, value=value, unit=spec.units[i])
            }}
        }
    }
}

object SimulationContext {
    fun sensorSamples(observationId: Long?, isControl: Boolean): List<ContextSample> {
        val t = System.currentTimeMillis()
        val phase = ((t / 60000L) % 60).toDouble()
        return listOf(
            ContextSample(timestampMs=t, observationId=observationId, isControl=isControl, source="simulation", metric="pressure_hpa", value=1008.0 + phase/20.0, unit="hPa"),
            ContextSample(timestampMs=t, observationId=observationId, isControl=isControl, source="simulation", metric="ambient_light_lux", value=120.0 + phase*3, unit="lux"),
            ContextSample(timestampMs=t, observationId=observationId, isControl=isControl, source="simulation", metric="accel_magnitude", value=9.81, unit="m/s2")
        )
    }
}
