package com.dronewukong.apophenia.garmin

import android.content.Context
import android.util.Log
import com.dronewukong.apophenia.data.ObservationStore
import com.dronewukong.apophenia.hardware.HardwareGates
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.garmin.android.connectiq.exception.InvalidStateException
import com.garmin.android.connectiq.exception.ServiceUnavailableException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GarminBridgeState(
    val statusText: String = "Not initialized",
    val deviceText: String = "No Garmin device seen",
    val lastDiagnostic: String? = null
)

object GarminBridge {
    const val WATCH_APP_ID = "4f4d0f7b3d6f4b36b3e88b91129c70a2"
    private const val GARMIN_CONNECT_PACKAGE = "com.garmin.android.apps.connectmobile"
    private const val TAG = "GarminBridge"

    private val mutableState = MutableStateFlow(GarminBridgeState())
    val state: StateFlow<GarminBridgeState> = mutableState.asStateFlow()
    val statusText: String get() = state.value.statusText
    val deviceText: String get() = state.value.deviceText

    @Volatile private var initialized = false
    @Volatile private var initializing = false
    private var connectIQ: ConnectIQ? = null
    private var ingestor: GarminEventIngestor? = null

    @Synchronized
    fun initialize(context: Context) {
        val app = context.applicationContext
        ingestor = GarminEventIngestor(
            RepositoryGarminEventStore(ObservationStore.repository(app)),
            WATCH_APP_ID,
            ::recordDiagnostic
        )
        if (!HardwareGates.garminEnabled) {
            update(
                status = if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION)
                    "Garmin bridge bypassed in simulation mode" else "Garmin bridge disabled by build gate",
                device = "Garmin integration inactive"
            )
            initialized = false
            return
        }
        if (!isGarminConnectInstalled(app)) {
            update("Garmin Connect is not installed", "Garmin integration unavailable")
            initialized = false
            initializing = false
            return
        }
        if (initialized || initializing) return
        val iq = runCatching { ConnectIQ.getInstance(app, ConnectIQ.IQConnectType.WIRELESS) }.getOrElse {
            recordDiagnostic("Garmin Connect service unavailable: ${it.message ?: it.javaClass.simpleName}")
            update(status = "Garmin Connect service unavailable")
            initialized = false
            initializing = false
            return
        }
        connectIQ = iq
        update(status = "Connecting to Garmin Connect…")
        initializing = true
        iq.initialize(app, true, object : ConnectIQ.ConnectIQListener {
            override fun onInitializeError(errStatus: ConnectIQ.IQSdkErrorStatus) {
                update(status = "Garmin SDK initialization failed: ${errStatus.name}")
                initialized = false
                initializing = false
            }

            override fun onSdkReady() {
                initialized = true
                initializing = false
                update(status = "Garmin bridge ready")
                registerKnownDevices()
            }

            override fun onSdkShutDown() {
                initialized = false
                initializing = false
                update(status = "Garmin bridge stopped")
            }
        })
    }

    @Synchronized
    fun refresh(context: Context) {
        if (!HardwareGates.garminEnabled) {
            update(status = "Garmin bridge bypassed")
            return
        }
        if (!initialized && !initializing) initialize(context) else if (initialized) registerKnownDevices()
    }

    private fun registerKnownDevices() {
        val iq = connectIQ ?: return
        val devices = try {
            iq.knownDevices ?: emptyList()
        } catch (_: InvalidStateException) {
            update(status = "Garmin SDK is not ready")
            return
        } catch (_: ServiceUnavailableException) {
            update(status = "Garmin Connect service unavailable")
            return
        }
        if (devices.isEmpty()) {
            update("Pair the Epix Pro in Garmin Connect, then refresh", "No paired Connect IQ device found")
            return
        }
        devices.forEach { device ->
            runCatching {
                device.status = iq.getDeviceStatus(device)
                iq.unregisterForDeviceEvents(device)
                iq.registerForDeviceEvents(device) { changed, status ->
                    update(device = "${changed.friendlyName}: ${status.name}")
                }
                iq.unregisterForApplicationEvents(device, IQApp(WATCH_APP_ID))
                iq.registerForAppEvents(device, IQApp(WATCH_APP_ID)) { commDevice, _, message, _ ->
                    update(device = "${commDevice.friendlyName}: message received")
                    ingestMessage(commDevice, message)
                }
            }.onFailure {
                recordDiagnostic("Could not register ${device.friendlyName}: ${it.message ?: it.javaClass.simpleName}")
            }
        }
        val connected = devices.firstOrNull { it.status == IQDevice.IQDeviceStatus.CONNECTED }
        val best = connected ?: devices.first()
        update(
            status = if (connected != null) "Listening for Apophenia watch events" else "Garmin device known but not connected",
            device = "${best.friendlyName}: ${best.status?.name ?: "UNKNOWN"}"
        )
    }

    fun openWatchLogger(context: Context) {
        refresh(context)
        val iq = connectIQ ?: return
        val device = try {
            iq.knownDevices?.firstOrNull { it.status == IQDevice.IQDeviceStatus.CONNECTED }
        } catch (_: Exception) {
            null
        }
        if (device == null) {
            update(status = "No connected Garmin device")
            return
        }
        try {
            iq.openApplication(device, IQApp(WATCH_APP_ID)) { _, _, result ->
                update(status = when (result) {
                    ConnectIQ.IQOpenApplicationStatus.PROMPT_SHOWN_ON_DEVICE -> "Open prompt sent to ${device.friendlyName}"
                    ConnectIQ.IQOpenApplicationStatus.APP_IS_ALREADY_RUNNING -> "Apophenia is already open on ${device.friendlyName}"
                    else -> "Could not open watch app: ${result.name}"
                })
            }
        } catch (_: InvalidStateException) {
            update(status = "Garmin SDK is not ready")
        } catch (_: ServiceUnavailableException) {
            update(status = "Garmin Connect service unavailable")
        }
    }

    private fun ingestMessage(device: IQDevice, message: List<Any>) {
        val packets = message.filterIsInstance<Map<*, *>>()
        if (packets.isEmpty()) {
            recordDiagnostic("Ignored Garmin message without an event packet")
            return
        }
        packets.forEach { ingestor?.ingest(device.friendlyName, it) }
    }

    @Synchronized
    fun shutdown(context: Context) {
        val iq = connectIQ
        if (iq != null) {
            runCatching { iq.unregisterAllForEvents() }
            runCatching { iq.shutdown(context.applicationContext) }
        }
        connectIQ = null
        initialized = false
        initializing = false
        update(status = "Garmin bridge stopped", device = "Garmin integration inactive")
    }

    private fun update(status: String? = null, device: String? = null, diagnostic: String? = null) {
        val current = mutableState.value
        mutableState.value = current.copy(
            statusText = status ?: current.statusText,
            deviceText = device ?: current.deviceText,
            lastDiagnostic = diagnostic ?: current.lastDiagnostic
        )
    }

    private fun recordDiagnostic(message: String) {
        Log.w(TAG, message)
        update(diagnostic = message)
    }

    @Suppress("DEPRECATION")
    private fun isGarminConnectInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(GARMIN_CONNECT_PACKAGE, 0)
    }.isSuccess
}
