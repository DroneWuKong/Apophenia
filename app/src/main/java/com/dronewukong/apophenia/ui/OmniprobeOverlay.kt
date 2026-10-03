package com.dronewukong.apophenia.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dronewukong.apophenia.audio.AudioRingCaptureManager
import com.dronewukong.apophenia.data.MediaStatus
import com.dronewukong.apophenia.data.Observation
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.hardware.HardwareGates
import com.dronewukong.apophenia.omniprobe.OmniprobeChannel
import com.dronewukong.apophenia.omniprobe.OmniprobeInspector
import com.dronewukong.apophenia.omniprobe.OmniprobeSnapshot
import com.dronewukong.apophenia.omniprobe.OmniprobeValue
import com.dronewukong.apophenia.video.VideoRingCaptureManager
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun OmniprobeOverlay(
    activity: MainActivity,
    db: ObservationDb,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val audio by AudioRingCaptureManager.state.collectAsState()
    val video by VideoRingCaptureManager.state.collectAsState()
    var observations by remember { mutableStateOf<List<Observation>>(emptyList()) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf<OmniprobeSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var revision by remember { mutableIntStateOf(0) }

    LaunchedEffect(revision) {
        loading = true
        observations = withContext(Dispatchers.IO) { db.observations(250) }
        selectedIndex = selectedIndex.coerceIn(0, (observations.size - 1).coerceAtLeast(0))
        snapshot = observations.getOrNull(selectedIndex)?.let { observation ->
            withContext(Dispatchers.IO) { OmniprobeInspector(activity, db).inspect(observation) }
        }
        loading = false
    }
    LaunchedEffect(selectedIndex, observations) {
        val observation = observations.getOrNull(selectedIndex) ?: return@LaunchedEffect
        loading = true
        snapshot = withContext(Dispatchers.IO) { OmniprobeInspector(activity, db).inspect(observation) }
        loading = false
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close Omniprobe") }
                    Column(Modifier.weight(1f)) {
                        Text("Omniprobe", style = MaterialTheme.typography.headlineSmall)
                        Text("Every planned gate, every stored value, every explained gap", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    IconButton(onClick = { revision++ }) { Icon(Icons.Default.Refresh, "Refresh Omniprobe") }
                }
                HorizontalDivider()
                when {
                    loading && snapshot == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    observations.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No events yet. Log an observation, then return to inspect its circumstances.", modifier = Modifier.padding(28.dp))
                    }
                    else -> snapshot?.let { current ->
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item {
                                EventSelector(
                                    observation = current.observation,
                                    index = selectedIndex,
                                    count = observations.size,
                                    previous = { if (selectedIndex > 0) selectedIndex-- },
                                    next = { if (selectedIndex < observations.lastIndex) selectedIndex++ }
                                )
                            }
                            item {
                                SummaryCard(current, audio.active, audio.pendingEvents, video.active, video.activeStreams.size, video.pendingEvents)
                            }
                            item {
                                Text("Channel inventory", style = MaterialTheme.typography.titleLarge)
                            }
                            items(current.channels, key = { it.gate.name }) { channel -> ChannelCard(channel) }
                            if (current.unmatchedValues.isNotEmpty()) {
                                item {
                                    InventoryCard("Recorded outside the current gate map", "These rows stay visible so a new provider cannot silently disappear.") {
                                        current.unmatchedValues.forEach { ValueRow(it) }
                                    }
                                }
                            }
                            item {
                                val active = current.mediaAssets.count { it.status == MediaStatus.ACTIVE }
                                InventoryCard("Raw evidence retention", "$active active · ${current.purgeEntries.size} purge-ledger entries for this event") {
                                    if (current.mediaAssets.isEmpty()) Text("No raw AV assets are registered for this event.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    current.mediaAssets.forEach { asset ->
                                        val remaining = when {
                                            asset.status != MediaStatus.ACTIVE -> asset.status.name.lowercase()
                                            asset.keepForever -> "keep forever"
                                            else -> retentionText(asset.retentionUntilMs - System.currentTimeMillis())
                                        }
                                        Text("${asset.mediaType.name.lowercase()} · ${asset.streamId} · $remaining", fontSize = 12.sp)
                                    }
                                    current.purgeEntries.forEach { entry ->
                                        Text("Purged ${entry.mediaType.name.lowercase()} · ${entry.reason} · derived metrics kept=${entry.derivedMetricsRetained}", fontSize = 12.sp)
                                    }
                                }
                            }
                            item {
                                InventoryCard("Export status", "Current implementation boundary") {
                                    Text(current.exportState, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                }
                            }
                            item { Spacer(Modifier.height(20.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventSelector(observation: Observation, index: Int, count: Int, previous: () -> Unit, next: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = previous, enabled = index > 0) { Icon(Icons.Default.ChevronLeft, "Newer event") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(observation.label, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Event #${observation.id} · ${DateFormat.getDateTimeInstance().format(Date(observation.timestampMs))}", fontSize = 12.sp)
                    Text("${index + 1} of $count · newest first", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = next, enabled = index < count - 1) { Icon(Icons.Default.ChevronRight, "Older event") }
            }
        }
    }
}

@Composable
private fun SummaryCard(snapshot: OmniprobeSnapshot, audioActive: Boolean, audioPending: Int, videoActive: Boolean, videoStreams: Int, videoPending: Int) {
    InventoryCard("Capture status", "${snapshot.observedChannelCount}/${snapshot.channels.size} gated channels have persisted evidence for this event") {
        Text("Audio ring · ${if (audioActive) "LIVE" else "off"} · $audioPending pending", fontWeight = FontWeight.SemiBold)
        Text("Video rings · ${if (videoActive) "LIVE" else "off"} · $videoStreams streams · $videoPending pending", fontWeight = FontWeight.SemiBold)
        Text("A live ring is current device state; channel rows below are event-specific persisted evidence.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChannelCard(channel: OmniprobeChannel) {
    val subtitle = if (channel.observed) "${channel.values.size} stored value${if (channel.values.size == 1) "" else "s"}"
    else channel.gapReason?.name?.replace('_', ' ') ?: "No data"
    InventoryCard(channel.title, "${channel.gate.name} · ${channel.gate.tier.name} · $subtitle") {
        if (channel.values.isEmpty()) {
            Text(channel.gapDetail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        } else {
            channel.values.take(40).forEach { ValueRow(it) }
            if (channel.values.size > 40) Text("${channel.values.size - 40} additional values omitted from this screen", fontSize = 11.sp)
        }
    }
}

@Composable
private fun ValueRow(value: OmniprobeValue) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text("${value.metric} = ${value.renderedValue.take(700)} ${value.unit}".trim(), fontSize = 12.sp)
        Text("capture_id=${value.captureId} · ${value.phase} · ${value.source}", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (value.metadata.isNotBlank()) Text(value.metadata.take(400), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InventoryCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            content()
        }
    }
}

private fun retentionText(remainingMs: Long): String = when {
    remainingMs <= 0 -> "retention due"
    remainingMs < 3_600_000L -> "${(remainingMs + 59_999L) / 60_000L}m left"
    remainingMs < 86_400_000L -> "${(remainingMs + 3_599_999L) / 3_600_000L}h left"
    else -> "${(remainingMs + 86_399_999L) / 86_400_000L}d left"
}
