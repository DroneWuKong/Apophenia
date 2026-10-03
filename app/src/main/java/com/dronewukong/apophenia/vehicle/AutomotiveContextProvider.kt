package com.dronewukong.apophenia.vehicle

import android.content.Context
import android.content.pm.PackageManager
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.hardware.HardwareGates

data class AutomotivePropertyReading(
    val metric: String,
    val value: Double,
    val unit: String,
    val propertyName: String,
    val areaId: Int = 0
)

data class AutomotiveReadResult(
    val readings: List<AutomotivePropertyReading>,
    val capabilityState: HardwareGates.CapabilityState,
    val detail: String
)

fun interface AutomotivePropertySource {
    fun read(): AutomotiveReadResult
}

class AutomotiveContextProvider(
    private val context: Context,
    private val source: AutomotivePropertySource = ReflectionAutomotivePropertySource(context)
) {
    fun collect(observationId: Long?, isControl: Boolean): List<ContextSample> {
        val gate = HardwareGates.Gate.LIVE_CARPLAY_AUTOMOTIVE_CAPTURE
        if (!HardwareGates.isAuthorized(context, gate)) return emptyList()
        val now = System.currentTimeMillis()
        val result = if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) {
            AutomotiveReadResult(simulationReadings, HardwareGates.CapabilityState.AVAILABLE, "simulation")
        } else {
            source.read()
        }
        if (result.readings.isEmpty()) return emptyList()
        val captureId = observationId?.let { "event:$it:instant" }.orEmpty()
        val sessionId = DriveSessionManager.activeId()
        return result.readings.map { reading ->
            ContextSample(
                timestampMs = now,
                observationId = observationId,
                isControl = isControl,
                source = if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) "simulation/automotive" else "android_automotive",
                metric = reading.metric,
                value = reading.value,
                unit = reading.unit,
                metadata = "property=${reading.propertyName};area_id=${reading.areaId};window=instant;platform=automotive_os",
                captureId = captureId,
                sessionId = sessionId
            )
        }
    }

    companion object {
        private val simulationReadings = listOf(
            AutomotivePropertyReading("automotive_cabin_temp_c", 21.5, "C", "HVAC_TEMPERATURE_CURRENT", 1),
            AutomotivePropertyReading("automotive_outside_temp_c", 12.0, "C", "ENV_OUTSIDE_TEMPERATURE"),
            AutomotivePropertyReading("automotive_speed_mps", 18.2, "m/s", "PERF_VEHICLE_SPEED"),
            AutomotivePropertyReading("automotive_gear_code", 4.0, "enum", "CURRENT_GEAR"),
            AutomotivePropertyReading("automotive_fuel_level_ml", 31_000.0, "mL", "FUEL_LEVEL"),
            AutomotivePropertyReading("automotive_ev_battery_level_wh", 44_000.0, "Wh", "EV_BATTERY_LEVEL"),
            AutomotivePropertyReading("automotive_odometer_km", 48_221.0, "km", "PERF_ODOMETER")
        )
    }
}

/**
 * Uses only public Android Automotive class/method names, loaded reflectively so the same APK
 * remains installable on phones where the optional android.car library does not exist.
 */
class ReflectionAutomotivePropertySource(private val context: Context) : AutomotivePropertySource {
    override fun read(): AutomotiveReadResult {
        if (!context.packageManager.hasSystemFeature(FEATURE_AUTOMOTIVE)) {
            return AutomotiveReadResult(emptyList(), HardwareGates.CapabilityState.HARDWARE_ABSENT, "not_an_automotive_os_device")
        }
        var car: Any? = null
        return try {
            val carClass = Class.forName("android.car.Car")
            val propertyIds = Class.forName("android.car.VehiclePropertyIds")
            val createCar = carClass.methods.firstOrNull { method ->
                method.name == "createCar" && method.parameterTypes.contentEquals(arrayOf(Context::class.java))
            } ?: return AutomotiveReadResult(emptyList(), HardwareGates.CapabilityState.PLATFORM_RESTRICTED, "Car.createCar(Context)_unavailable")
            car = createCar.invoke(null, context)
            val propertyManager = carClass.getMethod("getCarManager", String::class.java).invoke(car, "property")
                ?: return AutomotiveReadResult(emptyList(), HardwareGates.CapabilityState.PLATFORM_RESTRICTED, "property_manager_unavailable")
            val readings = mutableListOf<AutomotivePropertyReading>()
            var permissionDenied = false
            specs.forEach { spec ->
                val propertyId = runCatching { propertyIds.getField(spec.propertyName).getInt(null) }.getOrNull()
                    ?: return@forEach
                val areas = areaIds(propertyManager, propertyId)
                areas.forEach { areaId ->
                    val value = runCatching { readNumericProperty(propertyManager, spec.valueClass, propertyId, areaId) }
                        .onFailure { if (unwrap(it) is SecurityException) permissionDenied = true }
                        .getOrNull()
                    if (value != null && value.isFinite()) readings += AutomotivePropertyReading(
                        metric = spec.metric,
                        value = value,
                        unit = spec.unit,
                        propertyName = spec.propertyName,
                        areaId = areaId
                    )
                }
            }
            AutomotiveReadResult(
                readings,
                when {
                    readings.isNotEmpty() -> HardwareGates.CapabilityState.AVAILABLE
                    permissionDenied -> HardwareGates.CapabilityState.PERMISSION_DENIED
                    else -> HardwareGates.CapabilityState.PLATFORM_RESTRICTED
                },
                when {
                    readings.isNotEmpty() -> "${readings.size}_properties"
                    permissionDenied -> "automotive_property_permissions_denied"
                    else -> "properties_not_exposed_by_host"
                }
            )
        } catch (error: Throwable) {
            val cause = unwrap(error)
            AutomotiveReadResult(
                emptyList(),
                if (cause is SecurityException) HardwareGates.CapabilityState.PERMISSION_DENIED else HardwareGates.CapabilityState.PLATFORM_RESTRICTED,
                cause.javaClass.simpleName
            )
        } finally {
            if (car != null) runCatching { car.javaClass.getMethod("disconnect").invoke(car) }
        }
    }

    private fun areaIds(manager: Any, propertyId: Int): IntArray {
        val directConfig = manager.javaClass.methods.firstOrNull {
            it.name == "getCarPropertyConfig" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }?.let { runCatching { it.invoke(manager, propertyId) }.getOrNull() }
        val config = directConfig ?: manager.javaClass.methods.firstOrNull {
            it.name == "getPropertyList" && it.parameterCount == 0
        }?.let { method ->
            (runCatching { method.invoke(manager) }.getOrNull() as? Iterable<*>)?.firstOrNull { candidate ->
                runCatching { candidate?.javaClass?.getMethod("getPropertyId")?.invoke(candidate) as? Int }.getOrNull() == propertyId
            }
        }
        return (config?.let { runCatching { it.javaClass.getMethod("getAreaIds").invoke(it) as? IntArray }.getOrNull() })
            ?.takeIf { it.isNotEmpty() } ?: intArrayOf(0)
    }

    private fun readNumericProperty(manager: Any, valueClass: Class<*>, propertyId: Int, areaId: Int): Double? {
        val method = manager.javaClass.methods.firstOrNull {
            it.name == "getProperty" && it.parameterCount == 3 && it.parameterTypes[0] == Class::class.java
        } ?: return null
        val propertyValue = method.invoke(manager, valueClass, propertyId, areaId) ?: return null
        return (propertyValue.javaClass.getMethod("getValue").invoke(propertyValue) as? Number)?.toDouble()
    }

    private fun unwrap(error: Throwable): Throwable {
        var current = error
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current
    }

    private data class PropertySpec(
        val propertyName: String,
        val metric: String,
        val unit: String,
        val valueClass: Class<*>
    )

    companion object {
        private const val FEATURE_AUTOMOTIVE = "android.hardware.type.automotive"
        private val specs = listOf(
            PropertySpec("HVAC_TEMPERATURE_CURRENT", "automotive_cabin_temp_c", "C", java.lang.Float::class.java),
            PropertySpec("ENV_OUTSIDE_TEMPERATURE", "automotive_outside_temp_c", "C", java.lang.Float::class.java),
            PropertySpec("PERF_VEHICLE_SPEED", "automotive_speed_mps", "m/s", java.lang.Float::class.java),
            PropertySpec("CURRENT_GEAR", "automotive_gear_code", "enum", java.lang.Integer::class.java),
            PropertySpec("FUEL_LEVEL", "automotive_fuel_level_ml", "mL", java.lang.Float::class.java),
            PropertySpec("EV_BATTERY_LEVEL", "automotive_ev_battery_level_wh", "Wh", java.lang.Float::class.java),
            PropertySpec("PERF_ODOMETER", "automotive_odometer_km", "km", java.lang.Float::class.java)
        )
    }
}
