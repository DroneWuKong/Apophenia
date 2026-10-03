package com.dronewukong.apophenia.vehicle

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.dronewukong.apophenia.data.CaptureSession
import com.dronewukong.apophenia.data.CaptureSessionStatus
import com.dronewukong.apophenia.data.CaptureSessionType
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.data.ObservationStore
import com.dronewukong.apophenia.hardware.AndroidIdentifierHashKeyStore
import com.dronewukong.apophenia.hardware.DeviceIdentifierHasher
import com.dronewukong.apophenia.hardware.DeviceIdentifierKind
import com.dronewukong.apophenia.hardware.HardwareGates
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PairedObdAdapter(val displayName: String, val address: String)

data class DriveSessionState(
    val sessionId: String? = null,
    val active: Boolean = false,
    val adapterLabel: String = "No adapter selected",
    val startedAtMs: Long? = null,
    val sampleCount: Long = 0,
    val lastError: String? = null
)

object DriveSessionManager {
    private val mutableState = MutableStateFlow(DriveSessionState())
    val state: StateFlow<DriveSessionState> = mutableState.asStateFlow()

    private var client: Elm327Client? = null
    @Volatile private var activeSessionId: String? = null

    @Synchronized
    fun start(context: Context, adapter: PairedObdAdapter): Result<DriveSessionState> {
        var candidate: Elm327Client? = null
        return runCatching {
        check(HardwareGates.isAuthorized(context, HardwareGates.Gate.LIVE_VEHICLE_CAPTURE)) {
            "LIVE_VEHICLE_CAPTURE is off"
        }
        check(activeSessionId == null) { "A drive session is already active" }
        val app = context.applicationContext
        val runtimeClient = if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) {
            Elm327Client(SimulationObdTransport())
        } else {
            Elm327Client(BluetoothElm327Transport(app).also { it.connect(adapter.address) })
        }
        candidate = runtimeClient
        runtimeClient.initialize()
        val initial = runtimeClient.readSnapshot()
        check(initial.values.isNotEmpty()) { "ELM327 connected but returned no supported vehicle PIDs" }
        val sessionId = "drive:${UUID.randomUUID()}"
        val hasher = if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) {
            DeviceIdentifierHasher.withFixedKey(ByteArray(32) { (it * 7 + 3).toByte() })
        } else {
            DeviceIdentifierHasher(AndroidIdentifierHashKeyStore(app))
        }
        val adapterHash = hasher.hash(DeviceIdentifierKind.MAC_ADDRESS, adapter.address)
        val now = System.currentTimeMillis()
        ObservationStore.repository(app).db().insertSession(
            CaptureSession(
                id = sessionId,
                type = CaptureSessionType.DRIVE_SESSION,
                startedAtMs = now,
                identityHash = adapterHash,
                metadata = "boundary=one_adapter_connection;ignition_cycle_requires_physical_validation"
            )
        )
        client = runtimeClient
        candidate = null
        activeSessionId = sessionId
        DriveSessionState(
            sessionId = sessionId,
            active = true,
            adapterLabel = adapter.displayName,
            startedAtMs = now,
            sampleCount = 1
        ).also { mutableState.value = it }
        }.onFailure { error ->
        candidate?.close()
        client?.close()
        client = null
        activeSessionId = null
        mutableState.value = DriveSessionState(lastError = error.message ?: "Unable to start drive session")
    }
    }

    @Synchronized
    fun stop(context: Context, interrupted: Boolean = false, reason: String = "operator_stop") {
        val sessionId = activeSessionId ?: return
        client?.close()
        client = null
        activeSessionId = null
        ObservationStore.repository(context.applicationContext).db().endSession(
            sessionId,
            System.currentTimeMillis(),
            if (interrupted) CaptureSessionStatus.INTERRUPTED else CaptureSessionStatus.COMPLETED,
            "end_reason=$reason"
        )
        mutableState.value = DriveSessionState()
    }

    @Synchronized
    fun collect(context: Context, observationId: Long?, isControl: Boolean): List<ContextSample> {
        if (!HardwareGates.isAuthorized(context, HardwareGates.Gate.LIVE_VEHICLE_CAPTURE)) return emptyList()
        val sessionId = activeSessionId ?: return emptyList()
        val activeClient = client ?: return emptyList()
        return runCatching {
            val snapshot = activeClient.readSnapshot()
            val samples = ObdSnapshotEncoder.samples(
                snapshot,
                System.currentTimeMillis(),
                observationId,
                isControl,
                sessionId
            )
            mutableState.value = mutableState.value.copy(
                sampleCount = mutableState.value.sampleCount + 1,
                lastError = null
            )
            samples
        }.getOrElse { error ->
            mutableState.value = mutableState.value.copy(lastError = error.message ?: "OBD sample failed")
            emptyList()
        }
    }

    fun activeId(): String? = activeSessionId

    fun reconcileProcessStart(context: Context) {
        ObservationStore.repository(context.applicationContext).db().interruptActiveSessions(
            CaptureSessionType.DRIVE_SESSION,
            System.currentTimeMillis()
        )
    }

    @SuppressLint("MissingPermission")
    fun pairedAdapters(context: Context): List<PairedObdAdapter> {
        if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) {
            return listOf(PairedObdAdapter("SIMULATED ELM327", "02:00:00:00:03:27"))
        }
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return adapter.bondedDevices.orEmpty()
            .map { device -> PairedObdAdapter(device.name?.takeIf(String::isNotBlank) ?: "Paired Bluetooth device", device.address) }
            .sortedBy { it.displayName.lowercase() }
    }

    private class SimulationObdTransport : ObdTransport {
        override fun connect(address: String) = Unit
        override fun command(command: String): String = when (command.uppercase()) {
            "010C" -> "41 0C 1A F8>"
            "010D" -> "41 0D 37>"
            "0104" -> "41 04 80>"
            "0105" -> "41 05 7B>"
            "010F" -> "41 0F 54>"
            "0146" -> "41 46 52>"
            "0170" -> "NO DATA>"
            "0111" -> "41 11 40>"
            "012F" -> "41 2F B3>"
            "0106" -> "41 06 82>"
            "0107" -> "41 07 7C>"
            "0142" -> "41 42 36 B0>"
            "03" -> "43 01 33 00 00>"
            "07" -> "47 00 00>"
            else -> "OK>"
        }
        override fun close() = Unit
    }
}
