package com.dronewukong.apophenia.ui

import android.os.SystemClock
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dronewukong.apophenia.audio.AudioRingCaptureManager
import com.dronewukong.apophenia.hardware.HardwareGates
import com.dronewukong.apophenia.presets.EvidencePreset
import com.dronewukong.apophenia.presets.EvidencePresetManager
import com.dronewukong.apophenia.video.VideoRingCaptureManager

@Composable
internal fun EvidenceMasterStatus(activity: MainActivity) {
    val evidence by EvidencePresetManager.state.collectAsState()
    val revision by HardwareGates.authorizationRevision.collectAsState()
    val audio by AudioRingCaptureManager.state.collectAsState()
    val video by VideoRingCaptureManager.state.collectAsState()
    val armed = remember(revision) { HardwareGates.Gate.entries.count { HardwareGates.isAuthorized(activity, it) } }
    val mode = when {
        evidence.totalEvidenceActive -> "TOTAL EVIDENCE"
        evidence.activePreset != null -> evidence.activePreset?.displayName ?: "NORMAL"
        else -> "NORMAL"
    }
    val ring = buildList {
        if (audio.active) add("audio ${audio.preSeconds}s")
        if (video.active) add("video ${video.activeStreams.size}")
    }.joinToString(" + ").ifBlank { "rings off" }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(
                Icons.Default.FiberManualRecord,
                null,
                tint = if (audio.active || video.active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(12.dp)
            )
            Text("$mode · $armed gates armed · $ring", fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
internal fun EvidencePresetControls(activity: MainActivity, onMessage: (String) -> Unit, onGatesChanged: () -> Unit) {
    val state by EvidencePresetManager.state.collectAsState()
    var confirmTotal by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("TOTAL_EVIDENCE", fontWeight = FontWeight.SemiBold)
            Text(
                if (state.totalEvidenceActive) "The next hours are the investigation · ${state.audioPreSeconds}s audio pre-buffer"
                else "Arms every capture gate and maximum configured buffers",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
        Switch(
            checked = state.totalEvidenceActive,
            onCheckedChange = { enabled ->
                if (enabled) confirmTotal = true else {
                    EvidencePresetManager.deactivate(activity)
                    onMessage("TOTAL_EVIDENCE disarmed. Individual gate authorizations were preserved; live services must be stopped separately.")
                }
            }
        )
    }
    Text("Presets change gate authorization only. They never grant Android permissions, start OBD/UAS/RF sessions, accept screen-record consent, or open an export route.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        EvidencePreset.entries.forEach { preset ->
            HoldToArmButton(
                label = preset.displayName,
                detail = presetDetail(preset),
                selected = state.activePreset == preset && !state.totalEvidenceActive,
                onShort = { onMessage("Press and hold ${preset.displayName} for 1.5 seconds to arm its gates") },
                onHeld = { duration ->
                    val result = EvidencePresetManager.armPreset(activity, preset, duration)
                    onGatesChanged()
                    onMessage("${preset.displayName} armed ${result.armedCount} gates (${result.newlyArmed.size} newly). Android permissions and hardware sessions were not changed.")
                }
            )
        }
    }

    if (confirmTotal) {
        AlertDialog(
            onDismissRequest = { confirmTotal = false },
            title = { Text("Arm TOTAL_EVIDENCE?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("This deliberately authorizes every capture gate, including Tier-2 contents and capability-conditional channels. It excludes LIVE_EXPORT_LAN, requests no Android permission, starts no hardware session, and exports nothing.")
                    HoldToArmButton(
                        label = "Hold to arm TOTAL_EVIDENCE",
                        detail = "Keep holding for 1.5 seconds",
                        selected = false,
                        onShort = { onMessage("Hold for the full 1.5 seconds") },
                        onHeld = { duration ->
                            val result = EvidencePresetManager.activateTotalEvidence(activity, duration)
                            onGatesChanged()
                            confirmTotal = false
                            onMessage("TOTAL_EVIDENCE armed ${result.armedCount} capture gates. Review OS permissions and start the physical sessions you intend to use.")
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { confirmTotal = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun HoldToArmButton(
    label: String,
    detail: String,
    selected: Boolean,
    onShort: () -> Unit,
    onHeld: (Long) -> Unit
) {
    val trigger = { onHeld(HardwareGates.DELIBERATE_HOLD_MS) }
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                onClick(label = "Explain hold requirement") { onShort(); true }
                onLongClick(label = "Arm $label") { trigger(); true }
            }
            .pointerInput(label) {
                detectTapGestures(onPress = {
                    val started = SystemClock.elapsedRealtime()
                    val released = tryAwaitRelease()
                    val duration = SystemClock.elapsedRealtime() - started
                    if (released && duration >= HardwareGates.DELIBERATE_HOLD_MS) onHeld(duration) else onShort()
                })
            }
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("HOLD", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun presetDetail(preset: EvidencePreset): String = when (preset) {
    EvidencePreset.FIELD -> "MAVLink + CRSF/GHST + Field-Kit + RF + ground + all AV + watch"
    EvidencePreset.DRIVE -> "OBD + Automotive/EV + Bluetooth cabin presence + cabin AV"
    EvidencePreset.HOME -> "Phone/environment context; Octopod remains separately configured"
    EvidencePreset.EVERYTHING -> "Every capture gate; export remains separately gated"
}
