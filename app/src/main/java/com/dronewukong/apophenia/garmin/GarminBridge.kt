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
import java.util.concurrent.ConcurrentHashMap

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
    private val registeredDevices = ConcurrentHashMap<Long, IQDevice>()

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
            registeredDevices.clear()
            update("Pair the Epix Pro in Garmin Connect, then refresh", "No paired Connect IQ device found")
            return
        }
        registeredDevices.clear()
        devices.forEach { device ->
            runCatching { device.status = iq.getDeviceStatus(device) }
                .onFailure { recordDiagnostic("Could not read ${device.friendlyName} status: ${it.message ?: it.javaClass.simpleName}") }
            registeredDevices[device.deviceIdentifier] = device
            registerDeviceListener(iq, device)
            registerAppListener(iq, device)
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
            val connected = iq.connectedDevices?.firstOrNull()
            connected ?: GarminDeviceSelector.firstConnected(iq.knownDevices ?: emptyList(), iq::getDeviceStatus)
        } catch (error: Exception) {
            recordDiagnostic("Could not refresh Garmin connection: ${error.message ?: error.javaClass.simpleName}")
            null
        }
        if (device == null) {
            update(status = "Garmin Connect reports no live watch connection")
            return
        }
        device.status = IQDevice.IQDeviceStatus.CONNECTED
        registeredDevices[device.deviceIdentifier] = device
        registerDeviceListener(iq, device)
        registerAppListener(iq, device)
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
        val packets = GarminMessageDecoder.observationPackets(message)
        if (packets.isEmpty()) {
            recordDiagnostic("Ignored Garmin message without an event packet (top-level items=${message.size})")
            return
        }
        recordDiagnostic("Received ${packets.size} Garmin event${if (packets.size == 1) "" else "s"} from ${device.friendlyName}")
        packets.forEach { ingestor?.ingest(device.friendlyName, it) }
    }

    private fun registerDeviceListener(iq: ConnectIQ, device: IQDevice) {
        runCatching { iq.unregisterForDeviceEvents(device) }
        runCatching {
            iq.registerForDeviceEvents(device) { changed, status ->
                changed.status = status
                registeredDevices[changed.deviceIdentifier] = changed
                update(
                    status = if (status == IQDevice.IQDeviceStatus.CONNECTED)
                        "Listening for Apophenia watch events" else "Garmin device known but not connected",
                    device = "${changed.friendlyName}: ${status.name}"
                )
                if (status == IQDevice.IQDeviceStatus.CONNECTED) registerAppListener(iq, changed)
            }
        }.onFailure {
            recordDiagnostic("Could not watch ${device.friendlyName} connection: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun registerAppListener(iq: ConnectIQ, device: IQDevice) {
        val watchApp = IQApp(WATCH_APP_ID)
        runCatching { iq.unregisterForApplicationEvents(device, watchApp) }
        runCatching {
            iq.registerForAppEvents(device, watchApp) { commDevice, _, message, status ->
                if (status != ConnectIQ.IQMessageStatus.SUCCESS || message == null) {
                    recordDiagnostic("Garmin message failed: ${status.name}")
                    return@registerForAppEvents
                }
                update(device = "${commDevice.friendlyName}: message received")
                ingestMessage(commDevice, message)
            }
        }.onFailure {
            recordDiagnostic("Could not listen for ${device.friendlyName} app events: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    @Synchronized
    fun shutdown(context: Context) {
        val iq = connectIQ
        if (iq != null) {
            runCatching { iq.unregisterAllForEvents() }
            runCatching { iq.shutdown(context.applicationContext) }
        }
        connectIQ = null
        registeredDevices.clear()
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
