package com.dronewukong.apophenia.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.media.AudioTrack
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dronewukong.apophenia.correlation.AssociationEngine
import com.dronewukong.apophenia.correlation.CaptureMatcher
import com.dronewukong.apophenia.correlation.ConfounderSurfacer
import com.dronewukong.apophenia.correlation.HypothesisEvaluator
import com.dronewukong.apophenia.bluetooth.BluetoothContextProvider
import com.dronewukong.apophenia.backup.BackupInspection
import com.dronewukong.apophenia.backup.BackupManager
import com.dronewukong.apophenia.backup.RawDatabaseSnapshot
import com.dronewukong.apophenia.data.*
import com.dronewukong.apophenia.environment.EnvironmentProvider
import com.dronewukong.apophenia.export.ExportManager
import com.dronewukong.apophenia.export.ExportTier
import com.dronewukong.apophenia.export.PreparedExport
import com.dronewukong.apophenia.export.LanDestinationType
import com.dronewukong.apophenia.export.LanExportManager
import com.dronewukong.apophenia.export.LanExportSettings
import com.dronewukong.apophenia.garmin.GarminBridge
import com.dronewukong.apophenia.hardware.HardwareGates
import com.dronewukong.apophenia.media.AvRetentionSettings
import com.dronewukong.apophenia.media.MediaEvidenceReader
import com.dronewukong.apophenia.media.MediaRetentionManager
import com.dronewukong.apophenia.media.VideoEvidenceFrame
import com.dronewukong.apophenia.health.HealthConnectAccess
import com.dronewukong.apophenia.home.HomeContextProvider
import com.dronewukong.apophenia.home.HomeContextSettings
import com.dronewukong.apophenia.network.NetworkStateProvider
import com.dronewukong.apophenia.rolling.RollingRecorderService
import com.dronewukong.apophenia.rolling.RollingRecorderHealth
import com.dronewukong.apophenia.rolling.RollingRecorderState
import com.dronewukong.apophenia.wifi.WifiContextProvider
import com.dronewukong.apophenia.work.PromptedCheckInScheduler
import com.dronewukong.apophenia.work.PromptedCheckInState
import com.dronewukong.apophenia.vehicle.DriveSessionManager
import com.dronewukong.apophenia.vehicle.PairedObdAdapter
import com.dronewukong.apophenia.vehicle.DriveSessionService
import com.dronewukong.apophenia.mavlink.FlightSessionService
import com.dronewukong.apophenia.mavlink.MavlinkEndpoint
import com.dronewukong.apophenia.mavlink.MavlinkSessionManager
import com.dronewukong.apophenia.mavlink.UsbMavlinkDevice
import com.dronewukong.apophenia.control.ControlLinkManager
import com.dronewukong.apophenia.control.ControlLinkProtocol
import com.dronewukong.apophenia.control.ControlLinkService
import com.dronewukong.apophenia.fieldkit.FieldKitContextProvider
import com.dronewukong.apophenia.fieldkit.FieldKitSettings
import com.dronewukong.apophenia.tak.TakContextProvider
import com.dronewukong.apophenia.tak.TakSettings
import com.dronewukong.apophenia.ground.GroundContextProvider
import com.dronewukong.apophenia.rf.RfSurveyConfig
import com.dronewukong.apophenia.rf.RfSurveyContextProvider
import com.dronewukong.apophenia.rf.RfSurveySettings
import com.dronewukong.apophenia.audio.AudioRingCaptureManager
import com.dronewukong.apophenia.audio.AudioRingCaptureService
import com.dronewukong.apophenia.demo.DemoFixtureInstaller
import com.dronewukong.apophenia.demo.DemoFixtureSummary
import com.dronewukong.apophenia.demo.DemoModeManager
import com.dronewukong.apophenia.video.CallAudioCapability
import com.dronewukong.apophenia.video.CallConsentJurisdiction
import com.dronewukong.apophenia.video.CameraCaptureService
import com.dronewukong.apophenia.video.ScreenCaptureService
import com.dronewukong.apophenia.video.VideoRingCaptureManager
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Tab { LOG, TIMELINE, PATTERNS, SETTINGS }

private data class QuickAction(
    val title: String,
    val captureLabel: String = title,
    val kind: ObservationKind = ObservationKind.OBSERVATION,
    val icon: ImageVector
)

private data class PendingVibeNote(
    val grade: VibeGrade,
    val egress: Boolean,
    val timestampMs: Long
)

private data class PendingRestore(val bundle: File, val inspection: BackupInspection)

private val appColors = darkColorScheme(
    background = Color(0xFF090B10),
    surface = Color(0xFF121722),
    surfaceVariant = Color(0xFF1B2230),
    primary = Color(0xFFAFC8FF),
    onPrimary = Color(0xFF10264C),
    primaryContainer = Color(0xFF20345C),
    secondary = Color(0xFFCDBDFF),
    tertiary = Color(0xFF75DDB7),
    error = Color(0xFFFFB4AB),
    onBackground = Color(0xFFE8ECF5),
    onSurface = Color(0xFFE8ECF5),
    onSurfaceVariant = Color(0xFFB8C0D0),
    outline = Color(0xFF465166)
)

private val appTypography = Typography(
    headlineLarge = Typography().headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = Typography().headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Typography().bodyLarge.copy(lineHeight = 24.sp),
    bodyMedium = Typography().bodyMedium.copy(lineHeight = 20.sp),
    labelLarge = Typography().labelLarge.copy(fontWeight = FontWeight.SemiBold)
)

@Composable
fun ApopheniaScreen(activity: MainActivity) {
    val demoMode by DemoModeManager.state.collectAsState()
    val repo = remember(demoMode.active) { ObservationStore.repository(activity, demoMode.active) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf(Tab.LOG) }
    var refresh by remember { mutableIntStateOf(0) }
    val introPrefs = remember { activity.getSharedPreferences("onboarding", Context.MODE_PRIVATE) }
    var showContextIntro by remember { mutableStateOf(!introPrefs.getBoolean("context_intro_v1", false)) }
    fun message(text: String) { scope.launch { snackbar.showSnackbar(text) } }
    LaunchedEffect(demoMode.active) { refresh++ }

    MaterialTheme(colorScheme = appColors, typography = appTypography) {
        Scaffold(
            containerColor = appColors.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF11151F), tonalElevation = 0.dp) {
                    listOf(
                        Triple(Tab.LOG, Icons.Default.Add, "Log"),
                        Triple(Tab.TIMELINE, Icons.Default.History, "Timeline"),
                        Triple(Tab.PATTERNS, Icons.Default.AutoGraph, "Patterns"),
                        Triple(Tab.SETTINGS, Icons.Default.Settings, "Settings")
                    ).forEach { (destination, icon, label) ->
                        NavigationBarItem(
                            selected = tab == destination,
                            onClick = { tab = destination },
                            icon = { Icon(icon, null) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                EvidenceMasterStatus(activity)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        Tab.LOG -> LogTab(repo, onSaved = { refresh++ }, onOpenSettings = { tab = Tab.SETTINGS })
                        Tab.TIMELINE -> TimelineTab(repo, refresh)
                        Tab.PATTERNS -> PatternsTab(repo, refresh)
                        Tab.SETTINGS -> SettingsTab(activity, repo, scope, ::message)
                    }
                }
            }
        }

        if (showContextIntro) {
            AlertDialog(
                onDismissRequest = {},
                icon = { Icon(Icons.Default.Sensors, null) },
                title = { Text("Choose your context") },
                text = {
                    Text("Phone sensors work without a permission prompt. Location and weather, recorder notifications, and Health Connect are optional and stay off until you choose them.")
                },
                confirmButton = {
                    Button(onClick = {
                        introPrefs.edit().putBoolean("context_intro_v1", true).apply()
                        showContextIntro = false
                        tab = Tab.SETTINGS
                    }) { Text("Review access") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        introPrefs.edit().putBoolean("context_intro_v1", true).apply()
                        showContextIntro = false
                    }) { Text("Not now") }
                }
            )
        }
    }
}

@Composable
private fun LogTab(repo: ObservationRepository, onSaved: () -> Unit, onOpenSettings: () -> Unit) {
    var showForm by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ObservationKind.OBSERVATION) }
    var hypothesisMetric by remember { mutableStateOf("") }
    var hypothesisDirection by remember { mutableStateOf(HypothesisDirection.ANY) }
    var hypothesisWindow by remember { mutableStateOf(HypothesisWindow.INSTANT) }
    var hypothesisCohort by remember { mutableStateOf("label") }
    var hypothesisSaving by remember { mutableStateOf(false) }
    var hypothesisError by remember { mutableStateOf<String?>(null) }
    var pendingVibeNote by remember { mutableStateOf<PendingVibeNote?>(null) }
    var vibeNote by remember { mutableStateOf("") }
    var common by remember { mutableStateOf<List<String>>(emptyList()) }
    val context = LocalContext.current
    val recorderEnabled = RollingRecorderState.isEnabled(context)
    val simulation = HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION
    val actions = remember {
        listOf(
            QuickAction("Observation", icon = Icons.Default.Visibility),
            QuickAction("Headache", icon = Icons.Default.Sick),
            QuickAction("Sinus", "Sinus / congestion", icon = Icons.Default.Air),
            QuickAction("Light changed", icon = Icons.Default.LightMode),
            QuickAction("Sound / noise", icon = Icons.Default.GraphicEq),
            QuickAction("Body sensation", "Body / sensation", icon = Icons.Default.AccessibilityNew),
            QuickAction("Coincidence", kind = ObservationKind.COINCIDENCE, icon = Icons.Default.Hub),
            QuickAction("Hypothesis", "I think this happens when…", ObservationKind.HYPOTHESIS_NOTE, Icons.Default.Science),
            QuickAction("Other", "", icon = Icons.Default.MoreHoriz)
        )
    }
    fun captureVibe(grade: VibeGrade, egress: Boolean = false) {
        val capturedAt = System.currentTimeMillis()
        repo.log(VibeCapture.request(grade, capturedAt, egress = egress), onSaved = { onSaved() })
    }
    fun openVibeNote(grade: VibeGrade, egress: Boolean = false) {
        pendingVibeNote = PendingVibeNote(grade, egress, System.currentTimeMillis())
        vibeNote = ""
    }

    LaunchedEffect(Unit) {
        common = withContext(Dispatchers.IO) {
            repo.db().labels().map { it.first }.filterNot { it.equals("That was weird", true) }.take(5)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("APOPHENIA", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text("Notice now.\nUnderstand later.", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 8.dp))
                }
                StatusPill(if (simulation) "SIM" else "LIVE", if (simulation) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary)
            }
        }

        item {
            Button(
                onClick = {
                    val capturedAt = System.currentTimeMillis()
                    repo.log(ObservationKind.WEIRD, "That was weird", timestampMs = capturedAt, onSaved = { onSaved() })
                },
                modifier = Modifier.fillMaxWidth().height(104.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("THAT WAS WEIRD", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("Timestamp now · context follows", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel("Vibe")
                Text(
                    "Single tap logs immediately · hold for an optional note",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        VibeGrade.entries.chunked(2).forEach { rowGrades ->
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowGrades.forEach { grade ->
                        VibeCaptureButton(
                            grade = grade,
                            modifier = Modifier.weight(1f),
                            onCapture = { captureVibe(grade) },
                            onNote = { openVibeNote(grade) }
                        )
                    }
                    if (rowGrades.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        item {
            EgressCaptureButton(
                onCapture = { captureVibe(VibeGrade.FUCKY, egress = true) },
                onNote = { openVibeNote(VibeGrade.FUCKY, egress = true) }
            )
        }

        item {
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), onClick = onOpenSettings) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (recorderEnabled) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null, tint = if (recorderEnabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (recorderEnabled) "Black box recording" else "Black box is off", fontWeight = FontWeight.SemiBold)
                        Text(if (recorderEnabled) "Maintaining the previous 30 minutes" else "Tap to configure rolling context", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item { SectionLabel("Quick log") }
        actions.chunked(2).forEach { rowActions ->
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowActions.forEach { action ->
                        FilledTonalButton(
                            onClick = {
                                kind = action.kind
                                label = if (action.kind == ObservationKind.HYPOTHESIS_NOTE) "" else action.captureLabel
                                if (action.kind == ObservationKind.HYPOTHESIS_NOTE) {
                                    note = ""; hypothesisMetric = ""; hypothesisDirection = HypothesisDirection.ANY
                                    hypothesisWindow = HypothesisWindow.INSTANT; hypothesisCohort = "label"; hypothesisError = null
                                }
                                showForm = true
                            },
                            modifier = Modifier.weight(1f).height(62.dp),
                            shape = RoundedCornerShape(18.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Icon(action.icon, null, modifier = Modifier.size(19.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(action.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (rowActions.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        if (common.isNotEmpty()) {
            item { SectionLabel("Recent") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(common) { recent ->
                        AssistChip(
                            onClick = {
                                val capturedAt = System.currentTimeMillis()
                                repo.log(ObservationKind.OBSERVATION, recent, timestampMs = capturedAt, onSaved = { onSaved() })
                            },
                            label = { Text(recent, maxLines = 1) }
                        )
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(9.dp))
                Text("Describe what happened. Keep the explanation for a separate hypothesis.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (showForm) {
        val title = when (kind) {
            ObservationKind.COINCIDENCE -> "Log coincidence"
            ObservationKind.HYPOTHESIS_NOTE -> "Record hypothesis"
            else -> "Log observation"
        }
        AlertDialog(
            onDismissRequest = { showForm = false },
            title = { Text(title) },
            text = {
                Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (kind == ObservationKind.HYPOTHESIS_NOTE) {
                        Text("Register the expectation before opening results. Once an eligible result is viewed, this registration locks.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Text("Event class", fontWeight = FontWeight.SemiBold)
                        listOf("label" to "Named label", AnalysisCohort.EGRESS to "Egress · bailed", AnalysisCohort.BAD_VIBE_STAYED to "Bad vibes · stayed").forEach { (id, title) ->
                            FilterChip(selected = hypothesisCohort == id, onClick = { hypothesisCohort = id }, label = { Text(title) }, modifier = Modifier.fillMaxWidth())
                        }
                        if (hypothesisCohort == "label") OutlinedTextField(label = { Text("Exact event label") }, value = label, onValueChange = { label = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(label = { Text("Exact context metric") }, value = hypothesisMetric, onValueChange = { hypothesisMetric = it.trim() }, supportingText = { Text("Example: pressure_hpa or bt_nearby_count") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Text("Expected direction", fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HypothesisDirection.entries.forEach { direction -> FilterChip(selected = hypothesisDirection == direction, onClick = { hypothesisDirection = direction }, label = { Text(direction.name.lowercase()) }, modifier = Modifier.weight(1f)) }
                        }
                        Text("Window", fontWeight = FontWeight.SemiBold)
                        HypothesisWindow.entries.forEach { window -> FilterChip(selected = hypothesisWindow == window, onClick = { hypothesisWindow = window }, label = { Text(window.displayName) }, modifier = Modifier.fillMaxWidth()) }
                        OutlinedTextField(label = { Text("Expected association") }, value = note, onValueChange = { note = it }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                        hypothesisError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    } else {
                        OutlinedTextField(label = { Text("What did you notice?") }, value = label, onValueChange = { label = it }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(label = { Text("Optional note") }, value = note, onValueChange = { note = it }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    }
                }
            },
            confirmButton = {
                Button(enabled = !hypothesisSaving && (kind != ObservationKind.HYPOTHESIS_NOTE || (hypothesisMetric.isNotBlank() && note.isNotBlank() && (hypothesisCohort != "label" || label.isNotBlank()))), onClick = {
                    val capturedAt = System.currentTimeMillis()
                    if (kind == ObservationKind.HYPOTHESIS_NOTE) {
                        val cohortId = if (hypothesisCohort == "label") AnalysisCohort.labelId(label.trim()) else hypothesisCohort
                        val eventLabel = when (cohortId) {
                            AnalysisCohort.EGRESS -> "Egress · bailed"
                            AnalysisCohort.BAD_VIBE_STAYED -> "Bad vibes · stayed"
                            else -> label.trim()
                        }
                        val registration = Hypothesis(createdAtMs = capturedAt, eventLabel = eventLabel, metric = hypothesisMetric, direction = hypothesisDirection, note = note.trim(), cohortId = cohortId, windowStartMs = hypothesisWindow.fromBeforeMs, windowEndMs = hypothesisWindow.toBeforeMs)
                        hypothesisSaving = true
                        repo.registerHypothesis(registration, HypothesisEvaluator.resultKey(registration)) { id ->
                            hypothesisSaving = false
                            if (id == null) hypothesisError = "Results for this exact cohort, metric, and window were already viewed. This cannot be labeled a pre-registration."
                            else { showForm = false; note = ""; hypothesisError = null; onSaved() }
                        }
                    } else {
                        repo.log(kind, label, note, timestampMs = capturedAt, onSaved = { onSaved() })
                        showForm = false
                        note = ""
                    }
                }) { Text(if (hypothesisSaving) "Registering…" else if (kind == ObservationKind.HYPOTHESIS_NOTE) "Register now" else "Log now") }
            },
            dismissButton = { TextButton(onClick = { showForm = false }) { Text("Cancel") } }
        )
    }


    pendingVibeNote?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingVibeNote = null },
            title = { Text(if (pending.egress) VibeGrade.EGRESS_LABEL else pending.grade.renderedLabel) },
            text = {
                OutlinedTextField(
                    label = { Text("Optional note") },
                    value = vibeNote,
                    onValueChange = { vibeNote = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            },
            confirmButton = {
                Button(onClick = {
                    repo.log(
                        VibeCapture.request(
                            grade = pending.grade,
                            timestampMs = pending.timestampMs,
                            note = vibeNote,
                            egress = pending.egress
                        ),
                        onSaved = { onSaved() }
                    )
                    pendingVibeNote = null
                    vibeNote = ""
                }) { Text("Save stamped vibe") }
            },
            dismissButton = {
                TextButton(onClick = { pendingVibeNote = null }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VibeCaptureButton(
    grade: VibeGrade,
    modifier: Modifier = Modifier,
    onCapture: () -> Unit,
    onNote: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(58.dp)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = "Log ${grade.renderedLabel}",
                onLongClickLabel = "Add a note to ${grade.renderedLabel}",
                onClick = onCapture,
                onLongClick = onNote
            ),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(
                grade.renderedLabel,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EgressCaptureButton(onCapture: () -> Unit, onNote: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = "Log egress",
                onLongClickLabel = "Add a note to egress",
                onClick = onCapture,
                onLongClick = onNote
            ),
        color = Color(0xFFB3261E),
        shape = RoundedCornerShape(18.dp)
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(
                VibeGrade.EGRESS_LABEL,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun TimelineTab(repo: ObservationRepository, refresh: Int) {
    var rows by remember { mutableStateOf<List<Observation>>(emptyList()) }
    var hypotheses by remember { mutableStateOf<List<Hypothesis>>(emptyList()) }
    var hypothesisEvaluations by remember { mutableStateOf<Map<Long, HypothesisEvaluation>>(emptyMap()) }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var contextByObservation by remember { mutableStateOf<Map<Long, List<ContextSample>>>(emptyMap()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(refresh) {
        val loaded = withContext(Dispatchers.IO) {
            val hypotheses = repo.hypotheses()
            Triple(repo.observations(), hypotheses, hypotheses.mapNotNull { hypothesis -> repo.db().latestHypothesisEvaluation(hypothesis.id)?.let { hypothesis.id to it } }.toMap())
        }
        rows = loaded.first
        hypotheses = loaded.second
        hypothesisEvaluations = loaded.third
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader("Timeline", "Observations and interpretations stay separate.") }
        if (rows.isEmpty() && hypotheses.isEmpty()) item { EmptyState("No observations yet", "Your one-tap logs will appear here.") }
        if (hypotheses.isNotEmpty()) {
            item { SectionLabel("Hypotheses") }
            items(hypotheses, key = { "h-${it.id}" }) { hypothesis ->
                val evaluation = hypothesisEvaluations[hypothesis.id]
                val window = HypothesisWindow.fromBounds(hypothesis.windowStartMs, hypothesis.windowEndMs)
                val detail = buildString {
                    append(hypothesis.note)
                    if (hypothesis.metric.isNotBlank()) {
                        append("\n${hypothesis.metric} · ${hypothesis.direction.name.lowercase()} · ${window.displayName}")
                        if (evaluation != null) append("\n${evaluation.outcome.name.replace('_',' ')} · ${evaluation.summary}")
                        else append("\nRegistered · not evaluated")
                    }
                }
                val type = if (hypothesis.metric.isBlank()) "HYPOTHESIS NOTE" else if (hypothesis.lockedAtMs == null) "REGISTERED HYPOTHESIS" else "LOCKED REGISTRATION"
                TimelineCard(hypothesis.eventLabel, type, hypothesis.createdAtMs, detail, MaterialTheme.colorScheme.secondary)
            }
        }
        if (rows.isNotEmpty()) {
            item { SectionLabel("Observations") }
            items(rows, key = { "o-${it.id}" }) { observation ->
                val expanded = expandedId == observation.id
                TimelineCard(
                    title = observation.label,
                    type = observation.kind.name.replace('_', ' '),
                    timestampMs = observation.timestampMs,
                    note = observation.note,
                    accent = MaterialTheme.colorScheme.primary,
                    origin = observation.origin.name,
                    expanded = expanded,
                    context = contextByObservation[observation.id],
                    onClick = {
                        expandedId = if (expanded) null else observation.id
                        if (!expanded && observation.id !in contextByObservation) {
                            scope.launch {
                                val samples = withContext(Dispatchers.IO) { repo.db().contextForObservation(observation.id) }
                                contextByObservation = contextByObservation + (observation.id to samples)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun TimelineCard(
    title: String,
    type: String,
    timestampMs: Long,
    note: String,
    accent: Color,
    origin: String? = null,
    expanded: Boolean = false,
    context: List<ContextSample>? = null,
    onClick: (() -> Unit)? = null
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        onClick = onClick ?: {}
    ) {
        Column(Modifier.fillMaxWidth().padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = accent.copy(alpha = 0.14f), shape = RoundedCornerShape(8.dp)) {
                    Text(type, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestampMs)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
            if (note.isNotBlank()) Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
            if (origin != null) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$origin · timestamp $timestampMs", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                when {
                    context == null -> Text("Loading context capsule…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    context.isEmpty() -> Text("No context values yet. Enrichment may still be running.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> {
                        val phases = context.groupingBy { it.phase }.eachCount()
                        val sources = context.map { it.source.substringBefore('/') }.distinct().sorted()
                        Text("CONTEXT CAPSULE", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp, color = accent)
                        Text(
                            buildList {
                                phases[ContextPhase.PRE]?.let { add("$it pre") }
                                phases[ContextPhase.INSTANT]?.let { add("$it instant") }
                                phases[ContextPhase.POST]?.let { add("$it post") }
                            }.joinToString(" · ").ifBlank { "${context.size} values" },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 5.dp)
                        )
                        Text(sources.joinToString(" · "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
                        context.filter { it.phase != ContextPhase.POST }
                            .distinctBy { it.metric }
                            .take(8)
                            .forEach { sample ->
                                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                    Text(sample.metric.replace('_', ' '), fontSize = 12.sp, modifier = Modifier.weight(1f))
                                    Text("${"%.2f".format(sample.value)} ${sample.unit}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        if (context.any { it.phase == ContextPhase.POST }) {
                            Text("Post-event values are visible here but never used as predictors.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PatternsTab(repo: ObservationRepository, refresh: Int) {
    var cohorts by remember { mutableStateOf<List<AnalysisCohort>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<Pair<String, com.dronewukong.apophenia.correlation.AssociationResult>>>(emptyList()) }
    var registrationRows by remember { mutableStateOf<List<Pair<Hypothesis, HypothesisEvaluation?>>>(emptyList()) }
    var ambientDifferences by remember { mutableStateOf<List<com.dronewukong.apophenia.correlation.AmbientDifference>>(emptyList()) }
    LaunchedEffect(refresh) {
        cohorts = withContext(Dispatchers.IO) { repo.db().analysisCohorts() }
        if (selected !in cohorts.map { it.id }) selected = cohorts.firstOrNull()?.id
    }
    LaunchedEffect(selected, refresh) {
        val selectedCohort = selected ?: return@LaunchedEffect
        val analysis = withContext(Dispatchers.IO) {
            val values = linkedMapOf<String, Pair<List<Double>, List<Double>>>()
            fun addMatched(name: String, events: List<com.dronewukong.apophenia.correlation.TimedCaptureValue>, controls: List<com.dronewukong.apophenia.correlation.TimedCaptureValue>) {
                val matched = CaptureMatcher.match(events, controls)
                values[name] = matched.events to matched.controls
            }
            repo.db().metricsForCohort(selectedCohort).forEach { metric ->
                addMatched(metric, repo.db().eventFeatureCapturesForCohort(selectedCohort, metric), repo.db().controlFeatureCaptures(metric))
                val eventDelta = repo.db().eventBeforeDeltaCapturesForCohort(selectedCohort, metric)
                val controlDelta = repo.db().controlBeforeDeltaCaptures(metric)
                if (eventDelta.isNotEmpty() || controlDelta.isNotEmpty()) addMatched("$metric · before delta", eventDelta, controlDelta)
                listOf(0L to 600_000L, 600_000L to 1_200_000L, 1_200_000L to 1_800_000L).forEachIndexed { index, (from, to) ->
                    val events = repo.db().eventLagFeatureCapturesForCohort(selectedCohort, metric, from, to)
                    val controls = repo.db().controlLagFeatureCaptures(metric, from, to)
                    if (events.isNotEmpty() || controls.isNotEmpty()) addMatched("$metric · ${index * 10}-${(index + 1) * 10}m pre", events, controls)
                }
            }
            repo.db().devicePresenceCapturesForCohort(selectedCohort).forEach { (feature, captures) ->
                addMatched(feature, captures.first, captures.second)
            }
            val resultRows = AssociationEngine.compareAll(values, seed = selectedCohort.hashCode()).toList().sortedByDescending { kotlin.math.abs(it.second.standardizedEffect ?: 0.0) }
            val byFeature = resultRows.toMap()
            val viewedAt = System.currentTimeMillis()
            resultRows.filter { it.second.permutationP != null }.forEach { (feature, result) ->
                repo.db().recordAnalysisView(selectedCohort, feature, viewedAt, HypothesisEvaluator.analysisSignature(0, result))
            }
            val registrations = repo.db().hypotheses(10_000).filter { it.enabled && it.metric.isNotBlank() && it.cohortId == selectedCohort }
            registrations.forEach { hypothesis ->
                byFeature[HypothesisEvaluator.resultKey(hypothesis)]?.let { result ->
                    HypothesisEvaluator.evaluate(hypothesis, result)?.let { repo.db().recordHypothesisEvaluation(it) }
                }
            }
            val refreshed = repo.db().hypotheses(10_000).filter { it.enabled && it.metric.isNotBlank() && it.cohortId == selectedCohort }
            Triple(resultRows, refreshed.map { it to repo.db().latestHypothesisEvaluation(it.id) }, ConfounderSurfacer.scan(values).take(6))
        }
        results = analysis.first
        registrationRows = analysis.second
        ambientDifferences = analysis.third
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader("Patterns", "Explicit event classes compared with one-to-one matched control windows.") }
        item {
            Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(9.dp))
                    Text("Controls are matched by local 4-hour time block and weekday/weekend. Post-event samples are excluded. Associations are evidence to inspect, not proof of cause.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (cohorts.isEmpty()) item { EmptyState("Not enough data", "Log repeated observations and let random controls accumulate.") }
        else {
            item {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(value = cohorts.firstOrNull { it.id == selected }?.displayName.orEmpty(), onValueChange = {}, readOnly = true, label = { Text("Event class") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        cohorts.forEach { cohort -> DropdownMenuItem(text = { Text("${cohort.displayName} (${cohort.eventCount})") }, onClick = { selected = cohort.id; expanded = false }) }
                    }
                }
            }
            if (results.isNotEmpty()) item {
                val scope = results.first().second
                Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f), shape = RoundedCornerShape(14.dp)) {
                    Text(
                        "${scope.comparisonsTested} features tested · ${scope.comparisonsEligible} had enough matched captures · Benjamini-Hochberg correction applied. Weak hits are labeled indistinguishable from noise.",
                        modifier = Modifier.padding(12.dp),
                        fontSize = 12.sp
                    )
                }
            }
            if (registrationRows.isNotEmpty()) {
                item { SectionLabel("Registered hypotheses") }
                items(registrationRows, key = { "registration-${it.first.id}" }) { (hypothesis, evaluation) ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f))) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(hypothesis.note, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                StatusPill(evaluation?.outcome?.name?.replace('_',' ') ?: "REGISTERED", MaterialTheme.colorScheme.secondary)
                            }
                            Text("${hypothesis.metric} · ${hypothesis.direction.name.lowercase()} · ${HypothesisWindow.fromBounds(hypothesis.windowStartMs,hypothesis.windowEndMs).displayName}", fontSize = 12.sp)
                            Text("Registered ${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(hypothesis.createdAtMs))}${if(hypothesis.lockedAtMs!=null) " · immutable after results" else " · awaiting eligible result"}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            evaluation?.let { Text(it.summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
            if (ambientDifferences.isNotEmpty()) {
                item { SectionLabel("Ambient differences to check") }
                item {
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("These descriptive differences surface possible confounders from the first matched capture. They are not significance tests or causes.", fontSize = 12.sp)
                            ambientDifferences.forEach { difference ->
                                Text("• ${difference.summary} (${difference.eventCount} event / ${difference.controlCount} control)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            if (results.isEmpty()) item { EmptyState("Insufficient context", "More event and control windows are needed for this category.") }
            items(results) { (metric, result) ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(metric.replace('_', ' '), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            StatusPill(result.strength, MaterialTheme.colorScheme.secondary)
                        }
                        Text("${result.eventCount} events · ${result.controlCount} controls", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 7.dp))
                        Text(result.plainLanguageSummary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 5.dp))
                        Text(result.summary, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 7.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(activity: MainActivity, repo: ObservationRepository, scope: CoroutineScope, onMessage: (String) -> Unit) {
    var simulation by remember { mutableStateOf(HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) }
    var rolling by remember { mutableStateOf(RollingRecorderState.isEnabled(activity)) }
    var promptedCheckIns by remember { mutableStateOf(PromptedCheckInState.isEnabled(activity)) }
    val garminBridge by GarminBridge.state.collectAsState()
    val driveState by DriveSessionManager.state.collectAsState()
    val flightState by MavlinkSessionManager.state.collectAsState()
    val controlLinkState by ControlLinkManager.state.collectAsState()
    val audioRingState by AudioRingCaptureManager.state.collectAsState()
    val videoRingState by VideoRingCaptureManager.state.collectAsState()
    var rollingSummary by remember { mutableStateOf("No samples yet") }
    var healthStatus by remember { mutableStateOf("Checking…") }
    var locationAllowed by remember { mutableStateOf(activity.hasLocationPermission()) }
    var notificationsAllowed by remember { mutableStateOf(activity.hasNotificationPermission()) }
    var weatherStatus by remember { mutableStateOf(if (locationAllowed) "Ready to check" else "Location off") }
    var weatherChecking by remember { mutableStateOf(false) }
    var homeEnabled by remember { mutableStateOf(HomeContextSettings.isEnabled(activity)) }
    var homeEndpoint by remember { mutableStateOf(HomeContextSettings.endpoint(activity)) }
    var homeStatus by remember { mutableStateOf(if (homeEnabled) "Ready to check" else "Off") }
    var homeChecking by remember { mutableStateOf(false) }
    var bluetoothAllowed by remember { mutableStateOf(activity.hasBluetoothPermissions()) }
    var bluetoothEnabled by remember {
        mutableStateOf(HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_BLUETOOTH_CAPTURE))
    }
    var bluetoothStatus by remember { mutableStateOf(if (bluetoothEnabled) "Ready to scan" else "Off") }
    var bluetoothChecking by remember { mutableStateOf(false) }
    var wifiEnabled by remember {
        mutableStateOf(HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_WIFI_CAPTURE))
    }
    var wifiAllowed by remember { mutableStateOf(activity.hasWifiPermissions()) }
    var wifiStatus by remember { mutableStateOf(if (wifiEnabled) "Ready to scan" else "Off") }
    var wifiChecking by remember { mutableStateOf(false) }
    var networkEnabled by remember {
        mutableStateOf(HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_NETWORK_STATE_CAPTURE))
    }
    var networkSignalAllowed by remember { mutableStateOf(activity.hasNetworkSignalPermission()) }
    var networkStatus by remember { mutableStateOf(if (networkEnabled) "Ready to sample" else "Off") }
    var networkChecking by remember { mutableStateOf(false) }
    var gateRevision by remember { mutableIntStateOf(0) }
    var pendingDeliberateGate by remember { mutableStateOf<HardwareGates.Gate?>(null) }
    var deliberateGateInput by remember { mutableStateOf("") }
    var usageAllowed by remember { mutableStateOf(activity.hasUsageAccess()) }
    var vehicleEnabled by remember {
        mutableStateOf(HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_VEHICLE_CAPTURE))
    }
    var pairedObdAdapters by remember { mutableStateOf<List<PairedObdAdapter>>(emptyList()) }
    var showObdAdapterPicker by remember { mutableStateOf(false) }
    var mavlinkEnabled by remember {
        mutableStateOf(HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_MAVLINK_CAPTURE))
    }
    var mavlinkHost by remember { mutableStateOf("127.0.0.1") }
    var mavlinkPort by remember { mutableStateOf("14550") }
    var usbMavlinkDevices by remember { mutableStateOf<List<UsbMavlinkDevice>>(emptyList()) }
    var showUsbMavlinkPicker by remember { mutableStateOf(false) }
    var controlLinkEnabled by remember { mutableStateOf(HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_CRSF_GHST_CAPTURE)) }
    var selectedControlProtocol by remember { mutableStateOf(ControlLinkProtocol.CRSF) }
    var controlLinkBaud by remember { mutableStateOf(ControlLinkProtocol.CRSF.defaultBaud.toString()) }
    var usbControlDevices by remember { mutableStateOf<List<UsbMavlinkDevice>>(emptyList()) }
    var showUsbControlPicker by remember { mutableStateOf(false) }
    var fieldKitEnabled by remember { mutableStateOf(HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_FIELD_KIT_CAPTURE)) }
    var fieldKitPort by remember { mutableStateOf(FieldKitSettings.port(activity).toString()) }
    var takEnabled by remember { mutableStateOf(HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_TAK_CAPTURE)) }
    var takFullEnabled by remember { mutableStateOf(HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_TAK_CAPTURE_FULL)) }
    var takOwnUid by remember { mutableStateOf("") }
    var takGroup by remember { mutableStateOf(TakSettings.group(activity)) }
    var takPort by remember { mutableStateOf(TakSettings.port(activity).toString()) }
    var confirmTakFull by remember { mutableStateOf(false) }
    var groundEnabled by remember { mutableStateOf(HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_GROUND_CONTEXT_CAPTURE)) }
    var rfSurveyEnabled by remember { mutableStateOf(HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_RF_SURVEY_CAPTURE)) }
    val initialRfConfig = remember { RfSurveySettings.load(activity) }
    var rfHost by remember { mutableStateOf(initialRfConfig.host) }
    var rfPort by remember { mutableStateOf(initialRfConfig.port.toString()) }
    var rfCenterMhz by remember { mutableStateOf((initialRfConfig.centerFrequencyHz / 1_000_000.0).toString()) }
    var rfSampleRate by remember { mutableStateOf(initialRfConfig.sampleRateHz.toString()) }
    var rfWindowMs by remember { mutableStateOf(initialRfConfig.windowMs.toString()) }
    var rfRetentionDays by remember { mutableStateOf(initialRfConfig.retentionDays.toString()) }
    var confirmRfSurvey by remember { mutableStateOf(false) }
    var audioGateEnabled by remember { mutableStateOf(HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_AUDIO_CAPTURE)) }
    var confirmAudioGate by remember { mutableStateOf(false) }
    var audioGateInput by remember { mutableStateOf("") }
    var mainVideoEnabled by remember { mutableStateOf(HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_VIDEO_CAPTURE)) }
    var frontVideoEnabled by remember { mutableStateOf(HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_VIDEO_SELFCAPTURE)) }
    var multicamEnabled by remember { mutableStateOf(HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_MULTICAM_CAPTURE)) }
    var screenVideoEnabled by remember { mutableStateOf(HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_SCREENRECORD_CAPTURE)) }
    var pendingVideoGate by remember { mutableStateOf<HardwareGates.Gate?>(null) }
    var videoGateInput by remember { mutableStateOf("") }
    var callAudioEnabled by remember { mutableStateOf(HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_CALL_AUDIO_CAPTURE)) }
    var callJurisdiction by remember { mutableStateOf(CallAudioCapability.jurisdiction(activity)) }
    var confirmCallAudio by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmFullExport by remember { mutableStateOf(false) }
    var confirmFullBackup by remember { mutableStateOf(false) }
    var exportPreparing by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<PreparedExport?>(null) }
    var pendingRawDatabase by remember { mutableStateOf<RawDatabaseSnapshot?>(null) }
    var pendingRestore by remember { mutableStateOf<PendingRestore?>(null) }
    var restoreApplying by remember { mutableStateOf(false) }
    val lanSettings = remember { LanExportSettings(activity) }
    var lanEnabled by remember { mutableStateOf(HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_EXPORT_LAN)) }
    var lanConfiguration by remember { mutableStateOf(lanSettings.configuration()) }
    var lanEndpoint by remember { mutableStateOf(lanConfiguration.endpoint) }
    var lanUsername by remember { mutableStateOf(lanConfiguration.username) }
    var lanPassword by remember { mutableStateOf("") }
    var lanPushBusy by remember { mutableStateOf(false) }
    var pendingMediaScrub by remember { mutableStateOf<Long?>(null) }
    var mediaAssets by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }
    var purgeLedger by remember { mutableStateOf<List<PurgeLedgerEntry>>(emptyList()) }
    var retentionDaysText by remember { mutableStateOf(AvRetentionSettings.days(activity).toString()) }
    var audioTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var videoFrames by remember { mutableStateOf<List<VideoEvidenceFrame>>(emptyList()) }
    var videoFrameIndex by remember { mutableIntStateOf(0) }
    var videoTitle by remember { mutableStateOf("") }
    var showOmniprobe by remember { mutableStateOf(false) }
    val demoMode by DemoModeManager.state.collectAsState()
    var demoSummary by remember { mutableStateOf<DemoFixtureSummary?>(null) }
    val permissionRevision = activity.permissionRevision

    fun refreshGateToggles() {
        bluetoothEnabled = HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_BLUETOOTH_CAPTURE)
        wifiEnabled = HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_WIFI_CAPTURE)
        networkEnabled = HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_NETWORK_STATE_CAPTURE)
        vehicleEnabled = HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_VEHICLE_CAPTURE)
        mavlinkEnabled = HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_MAVLINK_CAPTURE)
        controlLinkEnabled = HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_CRSF_GHST_CAPTURE)
        fieldKitEnabled = HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_FIELD_KIT_CAPTURE)
        takEnabled = HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_TAK_CAPTURE)
        takFullEnabled = HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_TAK_CAPTURE_FULL)
        groundEnabled = HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_GROUND_CONTEXT_CAPTURE)
        rfSurveyEnabled = HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_RF_SURVEY_CAPTURE)
        audioGateEnabled = HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_AUDIO_CAPTURE)
        mainVideoEnabled = HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_VIDEO_CAPTURE)
        frontVideoEnabled = HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_VIDEO_SELFCAPTURE)
        multicamEnabled = HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_MULTICAM_CAPTURE)
        screenVideoEnabled = HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_SCREENRECORD_CAPTURE)
        callAudioEnabled = HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_CALL_AUDIO_CAPTURE)
        lanEnabled = HardwareGates.isAuthorized(activity, HardwareGates.Gate.LIVE_EXPORT_LAN)
        gateRevision++
    }

    fun refreshRolling() {
        scope.launch {
            val (snapshot, health) = withContext(Dispatchers.IO) { repo.db().rollingStatus() to RollingRecorderHealth.snapshot(activity) }
            val buffer = if (snapshot.first == 0 || snapshot.second == null || snapshot.third == null) "No buffered samples"
            else "${snapshot.first} buffered · ${"%.1f".format((snapshot.third!! - snapshot.second!!) / 60000.0)} min"
            val heartbeat = if (health.lastSampleAtMs == 0L) "no heartbeat yet"
            else "last heartbeat ${((System.currentTimeMillis() - health.lastSampleAtMs).coerceAtLeast(0L) / 1000L)}s ago"
            val failure = health.lastError.takeIf { it.isNotBlank() }?.let { " · last error: $it" }.orEmpty()
            rollingSummary = "$buffer · $heartbeat · ${health.capturedSampleCount} captured$failure"
        }
    }

    fun refreshPermissionState() {
        locationAllowed = activity.hasLocationPermission()
        notificationsAllowed = activity.hasNotificationPermission()
        bluetoothAllowed = activity.hasBluetoothPermissions()
        wifiAllowed = activity.hasWifiPermissions()
        networkSignalAllowed = activity.hasNetworkSignalPermission()
        usageAllowed = activity.hasUsageAccess()
        scope.launch { healthStatus = withContext(Dispatchers.IO) { HealthConnectAccess.permissionSummary(activity) } }
    }

    fun refreshMedia() {
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) { repo.db().mediaAssets(limit = 100) to repo.db().purgeLedger(20) }
            mediaAssets = snapshot.first
            purgeLedger = snapshot.second
        }
    }

    fun refreshDemoSummary() {
        if (!demoMode.active) { demoSummary = null; return }
        scope.launch { demoSummary = withContext(Dispatchers.IO) { DemoFixtureInstaller.summary(ObservationStore.demoRepository(activity).db()) } }
    }

    fun prepareExport(tier: ExportTier) {
        if (exportPreparing) return
        exportPreparing = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    ExportManager.prepareBundle(
                        context = activity,
                        db = ObservationStore.liveRepository(activity).db(),
                        tier = tier,
                        dir = File(activity.cacheDir, "exports")
                    )
                }
            }
            exportPreparing = false
            result.onSuccess { pendingExport = it }
                .onFailure { onMessage("Export preparation failed: ${it.message ?: "unknown error"}") }
        }
    }

    fun prepareBackup() {
        if (exportPreparing) return
        exportPreparing = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    BackupManager(activity).prepare(
                        ObservationStore.liveRepository(activity).db(),
                        File(activity.cacheDir, "exports")
                    )
                }
            }
            exportPreparing = false
            result.onSuccess { pendingExport = it }
                .onFailure { onMessage("Backup preparation failed: ${it.message ?: "unknown error"}") }
        }
    }

    fun prepareRawDatabase() {
        if (exportPreparing) return
        exportPreparing = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    BackupManager(activity).prepareRawDatabase(
                        ObservationStore.liveRepository(activity).db(),
                        File(activity.cacheDir, "exports")
                    )
                }
            }
            exportPreparing = false
            result.onSuccess { pendingRawDatabase = it }
                .onFailure { onMessage("SQLite snapshot failed: ${it.message ?: "unknown error"}") }
        }
    }

    fun captureSessionActive(): Boolean = audioRingState.active || audioRingState.pendingEvents > 0 ||
        videoRingState.active || videoRingState.pendingEvents > 0 || driveState.active ||
        flightState.armed || controlLinkState.active

    fun selectRestore() {
        if (captureSessionActive()) {
            onMessage("Disarm AV, drive, flight, and control-link sessions before selecting a restore")
            return
        }
        activity.selectBackupForRestore { selected ->
            selected.onFailure { onMessage(it.message ?: "Restore selection failed") }
            selected.onSuccess { file ->
                scope.launch {
                    val inspection = runCatching { withContext(Dispatchers.IO) { BackupManager(activity).inspect(file) } }
                    inspection.onSuccess { pendingRestore = PendingRestore(file, it) }
                        .onFailure { file.delete(); onMessage("Restore refused: ${it.message ?: "invalid or corrupt backup"}") }
                }
            }
        }
    }

    fun pushLan(prepared: PreparedExport) {
        if (lanPushBusy) return
        lanPushBusy = true
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { LanExportManager(activity).push(prepared.bundle) } }
            lanPushBusy = false
            result.onSuccess {
                prepared.bundle.delete()
                pendingExport = null
                onMessage("LAN push completed · ${it.bytesWritten} bytes · explicit transfer only")
            }.onFailure { onMessage("LAN push failed; prepared bundle kept locally: ${it.message ?: "unknown error"}") }
        }
    }

    fun testWeather() {
        if (weatherChecking) return
        weatherChecking = true
        weatherStatus = "Checking location and weather…"
        scope.launch {
            val samples = withContext(Dispatchers.IO) { EnvironmentProvider(activity).collect(null, false) }
            val temperature = samples.firstOrNull { it.metric == "weather_temperature_c" }?.value
            val humidity = samples.firstOrNull { it.metric == "weather_humidity_pct" }?.value
            weatherStatus = when {
                temperature != null && humidity != null -> "Ready · ${"%.1f".format(temperature)} °C · ${"%.0f".format(humidity)}% humidity"
                samples.any { it.source == "location" } -> "Location works · weather service unavailable"
                else -> "No location fix yet · turn on device location and retry"
            }
            weatherChecking = false
            onMessage(weatherStatus)
        }
    }

    fun testHomeContext() {
        if (homeChecking) return
        val saved = runCatching { HomeContextSettings.setEndpoint(activity, homeEndpoint) }.isSuccess
        if (!saved) {
            homeStatus = "Use an http:// or https:// cluster address"
            onMessage(homeStatus)
            return
        }
        homeChecking = true
        homeStatus = "Checking Octopod…"
        scope.launch {
            val samples = withContext(Dispatchers.IO) { HomeContextProvider(activity).collect(null, false, force = true) }
            homeStatus = if (samples.isEmpty()) {
                "No cluster response · check Wi-Fi, DNS, and Octopod"
            } else {
                "Connected · ${samples.size} aggregate context signals"
            }
            homeChecking = false
            onMessage(homeStatus)
        }
    }

    fun testBluetoothContext() {
        if (bluetoothChecking) return
        bluetoothChecking = true
        bluetoothStatus = "Scanning nearby Bluetooth LE advertisements…"
        scope.launch {
            val samples = withContext(Dispatchers.IO) {
                BluetoothContextProvider(activity).collect(null, false)
            }
            val count = samples.firstOrNull { it.metric == "bt_nearby_count" }?.value?.toInt()
            val strongest = samples.firstOrNull { it.metric == "bt_rssi_max" }?.value?.toInt()
            bluetoothStatus = if (samples.isEmpty()) {
                "No Bluetooth data · check the gate, Nearby devices, location, and Bluetooth"
            } else {
                "Snapshot · ${count ?: 0} devices" + (strongest?.let { " · strongest $it dBm" } ?: "")
            }
            bluetoothChecking = false
            onMessage(bluetoothStatus)
        }
    }

    fun testWifiContext() {
        if (wifiChecking) return
        wifiChecking = true
        wifiStatus = "Reading the platform Wi-Fi scan snapshot…"
        scope.launch {
            val samples = withContext(Dispatchers.IO) { WifiContextProvider(activity).collect(null, false) }
            val count = samples.firstOrNull { it.metric == "wifi_visible_count" }?.value?.toInt()
            val strongest = samples.firstOrNull { it.metric == "wifi_rssi_max" }?.value?.toInt()
            wifiStatus = if (samples.isEmpty()) {
                "No Wi-Fi data · check gate, location, Nearby Wi-Fi, device location, and scan limits"
            } else {
                "Snapshot · ${count ?: 0} access points" + (strongest?.let { " · strongest $it dBm" } ?: "")
            }
            wifiChecking = false
            onMessage(wifiStatus)
        }
    }

    fun testNetworkContext() {
        if (networkChecking) return
        networkChecking = true
        networkStatus = "Reading connectivity, carrier, and signal state…"
        scope.launch {
            val samples = withContext(Dispatchers.IO) { NetworkStateProvider(activity).collect(null, false) }
            val connected = samples.firstOrNull { it.metric == "network_connected" }?.value == 1.0
            val signal = samples.firstOrNull { it.metric == "network_signal_dbm" }?.value?.toInt()
            networkStatus = if (samples.isEmpty()) {
                "No network sample · gate or platform access unavailable"
            } else {
                (if (connected) "Connected" else "Disconnected") + (signal?.let { " · $it dBm" } ?: " · signal unavailable")
            }
            networkChecking = false
            onMessage(networkStatus)
        }
    }

    fun openObdAdapterPicker() {
        if (!notificationsAllowed) {
            activity.requestNotificationPermission { granted, message ->
                notificationsAllowed = granted
                onMessage(message)
                if (granted) openObdAdapterPicker()
            }
            return
        }
        if (!simulation && !activity.hasVehicleBluetoothPermission()) {
            activity.requestVehicleBluetoothPermission { granted, message ->
                onMessage(message)
                if (granted) openObdAdapterPicker()
            }
            return
        }
        scope.launch {
            pairedObdAdapters = withContext(Dispatchers.IO) { DriveSessionManager.pairedAdapters(activity) }
            if (pairedObdAdapters.isEmpty()) {
                onMessage("No paired Bluetooth adapters found. Pair the ELM327 in Android first.")
            } else {
                showObdAdapterPicker = true
            }
        }
    }

    fun startMavlink(endpoint: MavlinkEndpoint) {
        if (!notificationsAllowed) {
            activity.requestNotificationPermission { granted, message ->
                notificationsAllowed = granted
                onMessage(message)
                if (granted) startMavlink(endpoint)
            }
            return
        }
        FlightSessionService.start(activity, endpoint)
        onMessage("MAVLink capture armed · waiting for a valid airframe heartbeat")
    }

    fun openUsbMavlinkPicker() {
        usbMavlinkDevices = activity.usbMavlinkDevices()
        if (usbMavlinkDevices.isEmpty()) onMessage("No attached USB serial/SiK device found")
        else showUsbMavlinkPicker = true
    }

    fun openUsbControlPicker() {
        if (!notificationsAllowed) {
            activity.requestNotificationPermission { granted, message ->
                notificationsAllowed = granted; onMessage(message); if (granted) openUsbControlPicker()
            }
            return
        }
        usbControlDevices = activity.usbMavlinkDevices()
        if (usbControlDevices.isEmpty()) onMessage("No attached USB control-link device found")
        else showUsbControlPicker = true
    }

    fun armAudioRing() {
        fun startAfterNotification() {
            if (simulation) {
                AudioRingCaptureService.start(activity)
                onMessage("SIMULATION audio ring armed")
            } else activity.requestAudioPermission { granted, message ->
                onMessage(message)
                if (granted) AudioRingCaptureService.start(activity)
            }
        }
        if (!notificationsAllowed) activity.requestNotificationPermission { granted, message ->
            notificationsAllowed = granted
            onMessage(message)
            if (granted) startAfterNotification()
        } else startAfterNotification()
    }

    fun armCameraRings() {
        fun afterNotification() {
            if (simulation) CameraCaptureService.start(activity)
            else activity.requestCameraPermission { granted, message ->
                onMessage(message)
                if (granted) CameraCaptureService.start(activity)
            }
        }
        if (!notificationsAllowed) activity.requestNotificationPermission { granted, message ->
            notificationsAllowed = granted; onMessage(message); if (granted) afterNotification()
        } else afterNotification()
    }

    fun armScreenRing() {
        fun afterNotification() {
            if (simulation) ScreenCaptureService.startSimulation(activity)
            else activity.requestScreenCapture { resultCode, data ->
                if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                    ScreenCaptureService.start(activity, resultCode, data)
                    onMessage("Screen ring armed with Android MediaProjection consent")
                } else onMessage("Screen-capture consent was not granted")
            }
        }
        if (!notificationsAllowed) activity.requestNotificationPermission { granted, message ->
            notificationsAllowed = granted; onMessage(message); if (granted) afterNotification()
        } else afterNotification()
    }

    LaunchedEffect(permissionRevision) { refreshPermissionState() }
    LaunchedEffect(Unit) { refreshRolling(); refreshMedia(); refreshDemoSummary() }
    LaunchedEffect(demoMode.active) { simulation = HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION; refreshDemoSummary() }
    DisposableEffect(Unit) {
        onDispose { runCatching { audioTrack?.stop() }; audioTrack?.release(); audioTrack = null }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader("Settings", "Everything stays local unless you enable an optional source.") }
        item { SectionLabel("Recorder") }
        item {
            SettingsCard(Icons.Default.AllInclusive, "Total evidence + presets", "Deliberately arm capture gates for a bounded investigation without silently granting OS permissions or starting hardware sessions.") {
                EvidencePresetControls(activity, onMessage, ::refreshGateToggles)
            }
        }
        item {
            SettingsCard(Icons.Default.Storage, "Rolling black box", "Keeps a bounded 30-minute pre-event buffer.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = rolling, onCheckedChange = { enabled ->
                        rolling = enabled
                        if (enabled) activity.requestNotificationPermission { granted, message -> notificationsAllowed = granted; onMessage(message) }
                        RollingRecorderService.setEnabled(activity, enabled)
                        refreshRolling()
                    })
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(if (rolling) "Recording" else "Off", fontWeight = FontWeight.SemiBold)
                        Text(rollingSummary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }
        }
        item { SectionLabel("Study design") }
        item {
            SettingsCard(Icons.Default.Alarm, "Neutral check-ins", "Optional prompts every 3–6 hours create user-confirmed baseline captures.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = promptedCheckIns, onCheckedChange = { enabled ->
                        if (!enabled) {
                            promptedCheckIns = false
                            PromptedCheckInScheduler.setEnabled(activity, false)
                            onMessage("Neutral check-ins disabled")
                        } else if (notificationsAllowed) {
                            promptedCheckIns = true
                            PromptedCheckInScheduler.setEnabled(activity, true)
                            onMessage("Neutral check-ins enabled")
                        } else {
                            activity.requestNotificationPermission { granted, message ->
                                notificationsAllowed = granted
                                promptedCheckIns = granted
                                if (granted) PromptedCheckInScheduler.setEnabled(activity, true)
                                onMessage(if (granted) "Neutral check-ins enabled" else message)
                            }
                        }
                    })
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(if (promptedCheckIns) "Enabled" else "Off", fontWeight = FontWeight.SemiBold)
                        Text("Tap “Nothing unusual” to store a prompted control, or open the app to log an event.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }
        }

        item { SectionLabel("Context access") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp)) {
                    AccessRow(Icons.Default.Sensors, "Phone sensors", "Ready · no Android prompt required", true)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    AccessRow(Icons.Default.LocationOn, "Location + weather", weatherStatus, locationAllowed, action = if (locationAllowed) "Test" else "Allow", actionEnabled = !weatherChecking) {
                        if (locationAllowed) testWeather()
                        else activity.requestLocationPermission { granted, message ->
                            locationAllowed = granted
                            weatherStatus = if (granted) "Permission granted · checking…" else "Location off"
                            onMessage(message)
                            if (granted) testWeather()
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    AccessRow(Icons.Default.Notifications, "Recorder notifications", if (notificationsAllowed) "Allowed" else "Not allowed", notificationsAllowed, action = if (notificationsAllowed) null else "Allow") {
                        activity.requestNotificationPermission { granted, message -> notificationsAllowed = granted; onMessage(message) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    AccessRow(Icons.Default.HealthAndSafety, "Health Connect", healthStatus, healthStatus.startsWith("Connected"), action = if (healthStatus.startsWith("Connected")) "Manage" else "Connect") {
                        activity.requestHealthPermissions { message -> onMessage(message); refreshPermissionState() }
                    }
                }
            }
        }

        item { SectionLabel("Radio context") }
        item {
            SettingsCard(Icons.AutoMirrored.Filled.BluetoothSearching, "Bluetooth presence", "Hashed per-device BLE presence and capture-level counts/RSSI.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = bluetoothEnabled, onCheckedChange = { enabled ->
                        if (!enabled) {
                            HardwareGates.setAuthorized(
                                activity,
                                HardwareGates.Gate.LIVE_BLUETOOTH_CAPTURE,
                                enabled = false
                            )
                            bluetoothEnabled = false
                            bluetoothStatus = "Off"
                        } else if (simulation || bluetoothAllowed) {
                            val result = HardwareGates.setAuthorized(
                                activity,
                                HardwareGates.Gate.LIVE_BLUETOOTH_CAPTURE,
                                enabled = true,
                                proof = HardwareGates.ConsentProof.SingleConfirmation
                            )
                            bluetoothEnabled = result == HardwareGates.AuthorizationResult.ENABLED
                            bluetoothStatus = if (bluetoothEnabled) "Enabled · test a snapshot" else "Confirmation rejected"
                        } else {
                            activity.requestBluetoothPermissions { granted, message ->
                                bluetoothAllowed = granted
                                if (granted) {
                                    HardwareGates.setAuthorized(
                                        activity,
                                        HardwareGates.Gate.LIVE_BLUETOOTH_CAPTURE,
                                        enabled = true,
                                        proof = HardwareGates.ConsentProof.SingleConfirmation
                                    )
                                }
                                bluetoothEnabled = granted
                                bluetoothStatus = if (granted) "Enabled · test a snapshot" else "Permission required"
                                onMessage(message)
                            }
                        }
                    })
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (bluetoothEnabled) "Included in events and controls" else "Not collecting", fontWeight = FontWeight.SemiBold)
                        Text(bluetoothStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                Text(
                    "Stores a locally keyed address hash, advertised-device class, name category, and RSSI. Raw addresses and names never enter SQLite.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                OutlinedButton(
                    onClick = {
                        if ((simulation || bluetoothAllowed) && bluetoothEnabled) testBluetoothContext()
                        else activity.requestBluetoothPermissions { granted, message ->
                            bluetoothAllowed = granted
                            onMessage(message)
                            if (granted && bluetoothEnabled) testBluetoothContext()
                        }
                    },
                    enabled = !bluetoothChecking && bluetoothEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Radar, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (bluetoothChecking) "Scanning…" else "Take test snapshot")
                }
            }
        }
        item {
            SettingsCard(Icons.Default.Wifi, "Wi-Fi presence", "Hashed BSSID presence, band, RSSI, and capture-level counts.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = wifiEnabled, onCheckedChange = { enabled ->
                        if (!enabled) {
                            HardwareGates.setAuthorized(activity, HardwareGates.Gate.LIVE_WIFI_CAPTURE, enabled = false)
                            wifiEnabled = false
                            wifiStatus = "Off"
                        } else if (simulation || wifiAllowed) {
                            wifiEnabled = HardwareGates.setAuthorized(
                                activity,
                                HardwareGates.Gate.LIVE_WIFI_CAPTURE,
                                enabled = true,
                                proof = HardwareGates.ConsentProof.SingleConfirmation
                            ) == HardwareGates.AuthorizationResult.ENABLED
                            wifiStatus = if (wifiEnabled) "Enabled · test a snapshot" else "Confirmation rejected"
                        } else {
                            activity.requestWifiPermissions { granted, message ->
                                wifiAllowed = granted
                                if (granted) {
                                    HardwareGates.setAuthorized(
                                        activity,
                                        HardwareGates.Gate.LIVE_WIFI_CAPTURE,
                                        enabled = true,
                                        proof = HardwareGates.ConsentProof.SingleConfirmation
                                    )
                                }
                                wifiEnabled = granted
                                wifiStatus = if (granted) "Enabled · test a snapshot" else "Permission required"
                                onMessage(message)
                            }
                        }
                    })
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (wifiEnabled) "Included in events and controls" else "Not collecting", fontWeight = FontWeight.SemiBold)
                        Text(wifiStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                Text(
                    "Stores a locally keyed BSSID hash, 2.4/5/6 GHz band, frequency, and RSSI. SSID and raw BSSID are never persisted.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                OutlinedButton(
                    onClick = {
                        if ((simulation || wifiAllowed) && wifiEnabled) testWifiContext()
                        else activity.requestWifiPermissions { granted, message ->
                            wifiAllowed = granted
                            onMessage(message)
                            if (granted && wifiEnabled) testWifiContext()
                        }
                    },
                    enabled = !wifiChecking && wifiEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Radar, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (wifiChecking) "Scanning…" else "Take test snapshot")
                }
                Text(
                    "Android may return cached or rate-limited scan results; timestamps describe collection, not guaranteed RF airtime.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
        }
        item {
            SettingsCard(Icons.Default.CellTower, "Network state", "Carrier, network type, roaming, connectivity, and signal when exposed.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = networkEnabled, onCheckedChange = { enabled ->
                        networkEnabled = if (!enabled) {
                            HardwareGates.setAuthorized(activity, HardwareGates.Gate.LIVE_NETWORK_STATE_CAPTURE, enabled = false)
                            false
                        } else {
                            HardwareGates.setAuthorized(
                                activity,
                                HardwareGates.Gate.LIVE_NETWORK_STATE_CAPTURE,
                                enabled = true,
                                proof = HardwareGates.ConsentProof.SingleConfirmation
                            ) == HardwareGates.AuthorizationResult.ENABLED
                        }
                        networkStatus = if (networkEnabled) "Enabled · test a sample" else "Off"
                    })
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (networkEnabled) "Included in events and controls" else "Not collecting", fontWeight = FontWeight.SemiBold)
                        Text(networkStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                Text(
                    "Basic connectivity needs no extra prompt. Android phone-state access adds cellular network type and signal where the modem exposes them.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { testNetworkContext() },
                        enabled = !networkChecking && networkEnabled,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (networkChecking) "Sampling…" else "Test") }
                    OutlinedButton(
                        onClick = {
                            activity.requestNetworkSignalPermission { granted, message ->
                                networkSignalAllowed = granted
                                onMessage(message)
                            }
                        },
                        enabled = !networkSignalAllowed,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (networkSignalAllowed) "Signal allowed" else "Allow signal") }
                }
            }
        }

        item { SectionLabel("Phone metadata") }
        item {
            SettingsCard(Icons.Default.PhoneAndroid, "Device circumstances", "Independent standard gates; each snapshots only at event/control windows.") {
                gateRevision
                listOf(
                    Triple(HardwareGates.Gate.LIVE_AUDIO_METADATA_CAPTURE, "Audio state", "Outputs, ringer, volume, and active playback count."),
                    Triple(HardwareGates.Gate.LIVE_DISPLAY_INTERACTION_CAPTURE, "Display + interaction", "Screen, brightness, own notification count, keyboard, and foreground app when Android exposes it."),
                    Triple(HardwareGates.Gate.LIVE_POWER_THERMAL_CAPTURE, "Power + thermal", "Battery, charging, thermal status, load, and memory pressure."),
                    Triple(HardwareGates.Gate.LIVE_TIME_CONTEXT_CAPTURE, "Time + solar phase", "Timezone, weekday, day part, and locally computed solar elevation."),
                    Triple(HardwareGates.Gate.LIVE_WIFI_P2P_CAPTURE, "Wi-Fi Direct", "Hardware and group state; off by default."),
                    Triple(HardwareGates.Gate.LIVE_NFC_CAPTURE, "NFC state", "Adapter state at the capture window; no background tag polling.")
                ).forEachIndexed { index, (gate, title, detail) ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    GateSwitchRow(
                        title = title,
                        detail = detail,
                        enabled = HardwareGates.isAuthorized(activity, gate),
                        onCheckedChange = { enabled ->
                            HardwareGates.setAuthorized(
                                activity,
                                gate,
                                enabled,
                                if (enabled) HardwareGates.ConsentProof.SingleConfirmation else null
                            )
                            gateRevision++
                        }
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Foreground app package", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (usageAllowed) "Android Usage Access enabled" else "Platform permission not granted; other display metrics still capture",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                    TextButton(onClick = { activity.requestUsageAccess(onMessage) }) {
                        Text(if (usageAllowed) "Granted" else "Open access")
                    }
                }
            }
        }

        item { SectionLabel("Encrypted contents") }
        item {
            SettingsCard(Icons.Default.EnhancedEncryption, "Tier-2 contents", "Type the exact gate name to enable. Contents are AES-GCM encrypted before SQLite and excluded from data-only export.") {
                gateRevision
                listOf(
                    Triple(HardwareGates.Gate.LIVE_NOTIFICATION_CONTENTS_CAPTURE, "Notification contents", "Active notification text visible to Android Notification Access."),
                    Triple(HardwareGates.Gate.LIVE_CALENDAR_CONTENTS_CAPTURE, "Calendar contents", "Events overlapping the bounded -12h/+36h capture window."),
                    Triple(HardwareGates.Gate.LIVE_CONTACTS_CONTENTS_CAPTURE, "Contacts contents", "Full address-book snapshot at the capture window."),
                    Triple(HardwareGates.Gate.LIVE_MESSAGE_METADATA_CAPTURE, "Message metadata", "Six-hour SMS metadata window; message body is not part of this channel.")
                ).forEachIndexed { index, (gate, title, detail) ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    val authorized = HardwareGates.isAuthorized(activity, gate)
                    val platformAllowed = simulation || activity.hasTier2PlatformAccess(gate)
                    GateSwitchRow(
                        title = title,
                        detail = detail + if (authorized && !platformAllowed) " Android access is still required." else "",
                        enabled = authorized,
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                HardwareGates.setAuthorized(activity, gate, false)
                                gateRevision++
                            } else {
                                pendingDeliberateGate = gate
                                deliberateGateInput = ""
                            }
                        },
                        status = when {
                            !authorized -> "Off"
                            platformAllowed -> "Armed"
                            else -> "Gate on · permission denied"
                        }
                    )
                }
                Text(
                    "Data-only export has no code path to the sensitive_context table. Full evidence uses a separate double-confirmed, manifest-previewed plaintext route.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
        }

        item { SectionLabel("Vehicle") }
        item {
            SettingsCard(Icons.Default.DirectionsCar, "OBD-II drive session", "One active user-paired ELM327 connection groups vehicle, Bluetooth cabin presence, and phone context.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = vehicleEnabled, onCheckedChange = { enabled ->
                        vehicleEnabled = if (!enabled) {
                            if (driveState.active) DriveSessionService.stop(activity)
                            HardwareGates.setAuthorized(activity, HardwareGates.Gate.LIVE_VEHICLE_CAPTURE, false)
                            false
                        } else {
                            HardwareGates.setAuthorized(
                                activity,
                                HardwareGates.Gate.LIVE_VEHICLE_CAPTURE,
                                true,
                                HardwareGates.ConsentProof.SingleConfirmation
                            ) == HardwareGates.AuthorizationResult.ENABLED
                        }
                    })
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (driveState.active) "DRIVE_SESSION active" else if (vehicleEnabled) "Armed · no session" else "Off", fontWeight = FontWeight.SemiBold)
                        Text(
                            driveState.lastError ?: if (driveState.active) "${driveState.adapterLabel} · ${driveState.sampleCount} snapshots" else "Raw adapter addresses are never persisted",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }
                if (driveState.active) {
                    Button(
                        onClick = { DriveSessionService.stop(activity) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("End drive session") }
                } else {
                    OutlinedButton(
                        onClick = ::openObdAdapterPicker,
                        enabled = vehicleEnabled,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (simulation) "Start simulated drive" else "Choose paired adapter") }
                }
                Text(
                    "Standard PIDs cover RPM, speed, load, temperatures, throttle, fuel, trims, voltage, and DTCs. PID 0x70/manufacturer temperature coverage is recorded as variable rather than assumed.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
        }
        item {
            val automotiveGate = HardwareGates.Gate.LIVE_CARPLAY_AUTOMOTIVE_CAPTURE
            val automotivePlatform = activity.packageManager.hasSystemFeature("android.hardware.type.automotive")
            SettingsCard(Icons.Default.DirectionsCarFilled, "Native Automotive properties", "Reads vehicle properties exposed by Android Automotive OS; projection hosts may expose none.") {
                gateRevision
                val enabled = HardwareGates.isAuthorized(activity, automotiveGate)
                GateSwitchRow(
                    title = "Automotive OS property channel",
                    detail = "Cabin/outside temperature, speed, gear, fuel/charge, and odometer when the host and permissions expose them.",
                    enabled = enabled,
                    status = when {
                        !enabled -> "Off"
                        simulation || automotivePlatform -> "Armed"
                        else -> "Armed · host absent"
                    },
                    onCheckedChange = { requested ->
                        HardwareGates.setAuthorized(
                            activity,
                            automotiveGate,
                            requested,
                            if (requested) HardwareGates.ConsentProof.SingleConfirmation else null
                        )
                        gateRevision++
                    }
                )
                Text(
                    "Android Auto and Apple CarPlay projection do not grant a general vehicle-property API. On those hosts the gate stays visible and records a platform gap rather than invented values.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
        }

        item { SectionLabel("Flight / MAVLink") }
        item {
            SettingsCard(Icons.Default.Flight, "MAVLink flight session", "USB serial/SiK, UDP, or TCP telemetry binds to the first valid airframe heartbeat and groups one FLIGHT_SESSION.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = mavlinkEnabled, onCheckedChange = { enabled ->
                        mavlinkEnabled = if (!enabled) {
                            if (flightState.armed) FlightSessionService.stop(activity)
                            HardwareGates.setAuthorized(activity, HardwareGates.Gate.LIVE_MAVLINK_CAPTURE, false)
                            false
                        } else {
                            HardwareGates.setAuthorized(
                                activity,
                                HardwareGates.Gate.LIVE_MAVLINK_CAPTURE,
                                true,
                                HardwareGates.ConsentProof.SingleConfirmation
                            ) == HardwareGates.AuthorizationResult.ENABLED
                        }
                    })
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                flightState.sessionId != null -> "FLIGHT_SESSION active"
                                flightState.armed -> "Armed · awaiting heartbeat"
                                mavlinkEnabled -> "Armed · no transport"
                                else -> "Off"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            flightState.lastError ?: if (flightState.armed) {
                                "${flightState.endpointLabel} · sysid ${flightState.systemId ?: "pending"} · ${flightState.frameCount} frames · ${flightState.packetDropCount} observed gaps"
                            } else "Airframe sysid is locally hashed before session persistence",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }
                if (flightState.armed) {
                    Button(onClick = { FlightSessionService.stop(activity) }, modifier = Modifier.fillMaxWidth()) {
                        Text("End flight session")
                    }
                } else if (simulation) {
                    OutlinedButton(
                        onClick = { startMavlink(MavlinkEndpoint.Simulation) },
                        enabled = mavlinkEnabled,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Start simulated flight") }
                } else {
                    OutlinedTextField(
                        value = mavlinkHost,
                        onValueChange = { mavlinkHost = it.trim() },
                        label = { Text("TCP host") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = mavlinkPort,
                        onValueChange = { mavlinkPort = it.filter(Char::isDigit).take(5) },
                        label = { Text("Port") },
                        supportingText = { Text("UDP usually 14550 · TCP stacks commonly 5760") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { mavlinkPort.toIntOrNull()?.takeIf { it in 1..65535 }?.let { startMavlink(MavlinkEndpoint.Udp(it)) } ?: onMessage("Enter a valid UDP port") },
                            enabled = mavlinkEnabled,
                            modifier = Modifier.weight(1f)
                        ) { Text("Listen UDP") }
                        OutlinedButton(
                            onClick = {
                                val port = mavlinkPort.toIntOrNull()
                                if (mavlinkHost.isBlank() || port == null || port !in 1..65535) onMessage("Enter a valid TCP host and port")
                                else startMavlink(MavlinkEndpoint.Tcp(mavlinkHost, port))
                            },
                            enabled = mavlinkEnabled,
                            modifier = Modifier.weight(1f)
                        ) { Text("Connect TCP") }
                    }
                    OutlinedButton(
                        onClick = ::openUsbMavlinkPicker,
                        enabled = mavlinkEnabled,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Choose USB serial / SiK") }
                }
                Text(
                    "STATUSTEXT is stored verbatim. Sequence gaps and telemetry age are evidence of the received stream, not an exactly-once-delivery claim. Physical link, radio, and airframe behavior still require hardware validation.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
        }

        item { SectionLabel("Control link, Field-Kit + TAK") }
        item {
            SettingsCard(Icons.Default.SettingsInputAntenna, "CRSF / GHST link telemetry", "CRC-validated USB serial link statistics from the operator's own CRSF/ELRS or GHST/IRONghost path.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = controlLinkEnabled, onCheckedChange = { enabled ->
                        controlLinkEnabled = if (!enabled) {
                            if (controlLinkState.active) ControlLinkService.stop(activity)
                            HardwareGates.setAuthorized(activity, HardwareGates.Gate.LIVE_CRSF_GHST_CAPTURE, false); false
                        } else HardwareGates.setAuthorized(activity, HardwareGates.Gate.LIVE_CRSF_GHST_CAPTURE, true, HardwareGates.ConsentProof.SingleConfirmation) == HardwareGates.AuthorizationResult.ENABLED
                    })
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (controlLinkState.active) "${controlLinkState.protocol?.displayName} capture active" else if (controlLinkEnabled) "Armed · no serial input" else "Off", fontWeight = FontWeight.SemiBold)
                        Text(controlLinkState.lastError ?: if (controlLinkState.active) "${controlLinkState.frameCount} link frames · ${controlLinkState.endpointLabel}" else "No raw USB identity is persisted", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                if (controlLinkState.active) {
                    Button(onClick = { ControlLinkService.stop(activity) }, modifier = Modifier.fillMaxWidth()) { Text("End control-link capture") }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ControlLinkProtocol.entries.forEach { protocol ->
                            FilterChip(
                                selected = selectedControlProtocol == protocol,
                                onClick = { selectedControlProtocol = protocol; controlLinkBaud = protocol.defaultBaud.toString() },
                                label = { Text(protocol.name) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (simulation) {
                        OutlinedButton(
                            onClick = { ControlLinkService.start(activity, selectedControlProtocol, -1, selectedControlProtocol.defaultBaud, simulation = true) },
                            enabled = controlLinkEnabled,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Start simulated ${selectedControlProtocol.name}") }
                    } else {
                        OutlinedTextField(
                            value = controlLinkBaud,
                            onValueChange = { controlLinkBaud = it.filter(Char::isDigit).take(7) },
                            label = { Text("Serial baud") },
                            supportingText = { Text("CRSF mirror commonly 115200; GHST hardware may expose 115200 or 400000") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(onClick = ::openUsbControlPicker, enabled = controlLinkEnabled, modifier = Modifier.fillMaxWidth()) { Text("Choose USB control-link device") }
                    }
                }
                Text("CRSF supplies directional RSSI/LQ/SNR. The proven GHST link-stat frame supplies receiver-reported uplink statistics; unavailable downlink fields are recorded as unavailable rather than mirrored or invented.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        item {
            SettingsCard(Icons.Default.Sensors, "Owned Field-Kit detector", "Listens only during event/control windows for bounded JSON datagrams from your ESP32 detector hardware.") {
                GateSwitchRow(
                    title = "Field-Kit window capture",
                    detail = "Band RSSI, configured thresholds, threshold crossings, and trigger events; device ID is hashed.",
                    enabled = fieldKitEnabled,
                    onCheckedChange = { enabled ->
                        fieldKitEnabled = if (enabled) HardwareGates.setAuthorized(activity, HardwareGates.Gate.LIVE_FIELD_KIT_CAPTURE, true, HardwareGates.ConsentProof.SingleConfirmation) == HardwareGates.AuthorizationResult.ENABLED
                        else { HardwareGates.setAuthorized(activity, HardwareGates.Gate.LIVE_FIELD_KIT_CAPTURE, false); false }
                    }
                )
                OutlinedTextField(value = fieldKitPort, onValueChange = { fieldKitPort = it.filter(Char::isDigit).take(5) }, label = { Text("Field-Kit UDP port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val port = fieldKitPort.toIntOrNull()
                        if (port == null || port !in 1..65535) onMessage("Enter a valid Field-Kit port")
                        else { FieldKitSettings.setPort(activity, port); onMessage("Field-Kit window saved on UDP $port") }
                    }, modifier = Modifier.weight(1f)) { Text("Save") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val rows = withContext(Dispatchers.IO) { FieldKitContextProvider(activity).collect(null, false) }
                            onMessage(if (rows.isEmpty()) "No Field-Kit datagram in the bounded window" else "Field-Kit snapshot · ${rows.size} metrics")
                        }
                    }, enabled = fieldKitEnabled, modifier = Modifier.weight(1f)) { Text("Test window") }
                }
            }
        }
        item {
            SettingsCard(Icons.Default.Map, "TAK visible-track context", "Reads CoT traffic visible on your configured ATAK/TAK multicast connection at event/control windows.") {
                GateSwitchRow(
                    title = "Own asset track",
                    detail = "Default: only the configured own UID. UID is stored only as a local keyed hash; callsign text is not persisted.",
                    enabled = takEnabled,
                    onCheckedChange = { enabled ->
                        takEnabled = if (enabled) HardwareGates.setAuthorized(activity, HardwareGates.Gate.LIVE_TAK_CAPTURE, true, HardwareGates.ConsentProof.SingleConfirmation) == HardwareGates.AuthorizationResult.ENABLED
                        else { HardwareGates.setAuthorized(activity, HardwareGates.Gate.LIVE_TAK_CAPTURE, false); false }
                    }
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Full visible CoT traffic", fontWeight = FontWeight.SemiBold)
                        Text("Tier 3 · traffic visible on your own connection; every UID remains hashed", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Switch(checked = takFullEnabled, enabled = takEnabled, onCheckedChange = { enabled ->
                        if (!enabled) { HardwareGates.setAuthorized(activity, HardwareGates.Gate.LIVE_TAK_CAPTURE_FULL, false); takFullEnabled = false }
                        else confirmTakFull = true
                    })
                }
                OutlinedTextField(
                    value = takOwnUid,
                    onValueChange = { takOwnUid = it },
                    label = { Text("Own CoT UID${if (TakSettings.ownUidConfigured(activity)) " · hash configured" else ""}") },
                    supportingText = { Text("Enter only to set/replace; the raw UID is hashed before preferences") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = takGroup, onValueChange = { takGroup = it.trim() }, label = { Text("Multicast group") }, singleLine = true, modifier = Modifier.weight(1.4f))
                    OutlinedTextField(value = takPort, onValueChange = { takPort = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(0.7f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val port = takPort.toIntOrNull()
                        runCatching { TakSettings.save(activity, takOwnUid, takGroup, port ?: -1) }
                            .onSuccess { takOwnUid = ""; onMessage("TAK filter and multicast settings saved") }
                            .onFailure { onMessage("Enter a nonblank group and valid TAK port") }
                    }, modifier = Modifier.weight(1f)) { Text("Save") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val rows = withContext(Dispatchers.IO) { TakContextProvider(activity).collect(null, false) }
                            val tracks = rows.map { it.metadata.substringAfter("uid_hash=", "").substringBefore(';') }.filter(String::isNotBlank).distinct().size
                            onMessage(if (rows.isEmpty()) "No eligible CoT track in the bounded window" else "TAK snapshot · $tracks track(s) · ${rows.size} metrics")
                        }
                    }, enabled = takEnabled, modifier = Modifier.weight(1f)) { Text("Test window") }
                }
            }
        }

        item { SectionLabel("Audio/video evidence") }
        item {
            SettingsCard(Icons.Default.Mic, "Audio evidence ring", "Tier 2 · persistent 60-second encrypted pre-event ring plus 30 seconds post-event when armed.") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (audioRingState.active) "LIVE · microphone ring buffering" else if (audioGateEnabled) "Gate authorized · ring disarmed" else "Gate off", fontWeight = FontWeight.SemiBold)
                        Text(
                            audioRingState.lastError ?: if (audioRingState.active) "${audioRingState.capturedBytes / 1_024} KiB processed · ${audioRingState.pendingEvents} event freeze(s) finishing" else "No microphone capture outside an armed ring",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                    StatusPill(if (audioRingState.active) "AUDIO LIVE" else "OFF", if (audioRingState.active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
                }
                if (audioRingState.active) {
                    Button(onClick = { AudioRingCaptureService.stop(activity) }, modifier = Modifier.fillMaxWidth()) { Text("Disarm audio ring") }
                } else if (audioGateEnabled) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = ::armAudioRing, modifier = Modifier.weight(1f)) { Text("Arm audio ring") }
                        OutlinedButton(onClick = {
                            AudioRingCaptureService.stop(activity)
                            HardwareGates.setAuthorized(activity, HardwareGates.Gate.LIVE_AUDIO_CAPTURE, false)
                            audioGateEnabled = false
                        }, modifier = Modifier.weight(1f)) { Text("Revoke gate") }
                    }
                } else {
                    Button(onClick = { audioGateInput = ""; confirmAudioGate = true }, modifier = Modifier.fillMaxWidth()) { Text("Enable + arm") }
                }
                Text("Every observation freezes the sound already in memory. Raw PCM is written only after per-event AES-256-GCM encryption; derived loudness, hum/voice/high-frequency bands, onsets, and silence ratios survive raw retention. POST rows are excluded from predictors.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        item {
            SettingsCard(Icons.Default.Videocam, "Camera + screen evidence rings", "Tier 2 · 15 seconds pre-event and 10 seconds post-event per available stream, each encrypted separately.") {
                fun setVideoGate(gate: HardwareGates.Gate, enabled: Boolean) {
                    if (enabled) {
                        videoGateInput = ""
                        pendingVideoGate = gate
                    } else {
                        HardwareGates.setAuthorized(activity, gate, false)
                        when (gate) {
                            HardwareGates.Gate.LIVE_VIDEO_CAPTURE -> mainVideoEnabled = false
                            HardwareGates.Gate.LIVE_VIDEO_SELFCAPTURE -> frontVideoEnabled = false
                            HardwareGates.Gate.LIVE_MULTICAM_CAPTURE -> multicamEnabled = false
                            HardwareGates.Gate.LIVE_SCREENRECORD_CAPTURE -> screenVideoEnabled = false
                            else -> Unit
                        }
                    }
                }
                GateSwitchRow("Main camera", "Rear/logical-main stream when the platform exposes it.", mainVideoEnabled, onCheckedChange = { setVideoGate(HardwareGates.Gate.LIVE_VIDEO_CAPTURE, it) })
                GateSwitchRow("Front camera", "Self-capture stream, separately tagged and stored.", frontVideoEnabled, onCheckedChange = { setVideoGate(HardwareGates.Gate.LIVE_VIDEO_SELFCAPTURE, it) })
                GateSwitchRow("All-camera multicam", "Requests the largest concurrent camera set Android reports; every unavailable lens stays an explicit degradation.", multicamEnabled, onCheckedChange = { setVideoGate(HardwareGates.Gate.LIVE_MULTICAM_CAPTURE, it) })
                GateSwitchRow("Screen record", "Requires Android's MediaProjection consent each time it is armed.", screenVideoEnabled, onCheckedChange = { setVideoGate(HardwareGates.Gate.LIVE_SCREENRECORD_CAPTURE, it) })
                Text(
                    if (videoRingState.active) "LIVE · ${videoRingState.activeStreams.size} stream(s) · ${videoRingState.framesCaptured} frames · ${videoRingState.pendingEvents} event freeze(s)"
                    else "Rings disarmed",
                    fontWeight = FontWeight.SemiBold
                )
                videoRingState.degradation?.let { Text("Degraded: $it", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
                videoRingState.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if ("camera" in videoRingState.activeSources) {
                        Button(onClick = { CameraCaptureService.stop(activity) }, modifier = Modifier.weight(1f)) { Text("Disarm cameras") }
                    } else {
                        Button(onClick = ::armCameraRings, enabled = mainVideoEnabled || frontVideoEnabled || multicamEnabled, modifier = Modifier.weight(1f)) { Text("Arm cameras") }
                    }
                    if ("screen" in videoRingState.activeSources) {
                        Button(onClick = { ScreenCaptureService.stop(activity) }, modifier = Modifier.weight(1f)) { Text("Disarm screen") }
                    } else {
                        Button(onClick = ::armScreenRing, enabled = screenVideoEnabled, modifier = Modifier.weight(1f)) { Text("Arm screen") }
                    }
                }
                Text("MJPEG frame streams are separate per lens/screen. Derived rows include motion energy, brightness, frame-to-frame flicker, spatial PWM-banding score, and scene-change flags. Two-fps capture cannot estimate PWM frequency and says so explicitly.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        item {
            SettingsCard(Icons.Default.PhoneInTalk, "Call audio capability", "Tier 3 · visible per-call channel with platform and configured-jurisdiction gaps; no substitute audio is mislabeled.") {
                Text("Configured consent jurisdiction", fontWeight = FontWeight.SemiBold)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CallConsentJurisdiction.entries.forEach { jurisdiction ->
                        FilterChip(
                            selected = callJurisdiction == jurisdiction,
                            onClick = { callJurisdiction = jurisdiction; CallAudioCapability.setJurisdiction(activity, jurisdiction) },
                            label = { Text(jurisdiction.name.replace('_', ' ')) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (callAudioEnabled) "Call-audio gate authorized" else "Call-audio gate off", fontWeight = FontWeight.SemiBold)
                        Text(CallAudioCapability.explanation(activity), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Switch(checked = callAudioEnabled, onCheckedChange = { enabled ->
                        if (!enabled) { HardwareGates.setAuthorized(activity, HardwareGates.Gate.LIVE_CALL_AUDIO_CAPTURE, false); callAudioEnabled = false }
                        else confirmCallAudio = true
                    })
                }
                OutlinedButton(onClick = { onMessage(CallAudioCapability.explanation(activity)) }, enabled = callAudioEnabled, modifier = Modifier.fillMaxWidth()) { Text("Per-call capability check") }
            }
        }
        item {
            SettingsCard(Icons.Default.VideoLibrary, "Encrypted evidence media", "Retention, keep-forever, scrub, and in-app playback. Decryption stays in memory; derived metrics survive every raw-media purge.") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = retentionDaysText,
                        onValueChange = { retentionDaysText = it.filter(Char::isDigit).take(4) },
                        label = { Text("Default days") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = {
                        val days = retentionDaysText.toIntOrNull()?.coerceIn(1, 3_650)
                        if (days == null) onMessage("Retention must be 1–3650 days") else {
                            retentionDaysText = days.toString()
                            AvRetentionSettings.setDays(activity, days)
                            onMessage("New AV captures retain raw media for $days days")
                        }
                    }) { Text("Save") }
                }
                Text("Changing the default applies to new freezes. Existing deadlines stay fixed unless that event is marked keep forever.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)

                if (videoFrames.isNotEmpty()) {
                    val frame = videoFrames[videoFrameIndex.coerceIn(0, videoFrames.lastIndex)]
                    val bitmap = remember(frame) { BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size) }
                    bitmap?.let {
                        Image(it.asImageBitmap(), contentDescription = "Decrypted evidence frame", modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp))
                    }
                    Text("$videoTitle · ${frame.phase.uppercase()} · ${videoFrameIndex + 1}/${videoFrames.size}", fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { videoFrameIndex = (videoFrameIndex - 1).coerceAtLeast(0) }, enabled = videoFrameIndex > 0, modifier = Modifier.weight(1f)) { Text("Previous") }
                        OutlinedButton(onClick = { videoFrameIndex = (videoFrameIndex + 1).coerceAtMost(videoFrames.lastIndex) }, enabled = videoFrameIndex < videoFrames.lastIndex, modifier = Modifier.weight(1f)) { Text("Next") }
                        TextButton(onClick = { videoFrames = emptyList(); videoFrameIndex = 0; videoTitle = "" }) { Text("Close") }
                    }
                }
                if (audioTrack != null) {
                    OutlinedButton(onClick = {
                        runCatching { audioTrack?.stop() }
                        audioTrack?.release()
                        audioTrack = null
                        onMessage("Evidence audio stopped and released from memory")
                    }, modifier = Modifier.fillMaxWidth()) { Text("Stop evidence audio") }
                }

                if (mediaAssets.isEmpty()) {
                    Text("No retained AV evidence yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    mediaAssets.groupBy { it.observationId }.entries.take(8).forEach { (observationId, assets) ->
                        HorizontalDivider()
                        val allKept = assets.all { it.keepForever }
                        Text("Event #$observationId · ${assets.size} stream${if (assets.size == 1) "" else "s"}", fontWeight = FontWeight.SemiBold)
                        assets.forEach { asset ->
                            val remainingMs = (asset.retentionUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)
                            val remaining = if (asset.keepForever) "keep forever" else "${(remainingMs + 86_399_999L) / 86_400_000L}d left"
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${asset.mediaType.name.lowercase()} · ${asset.streamId} · ${asset.sizeBytes / 1_024} KiB · $remaining", modifier = Modifier.weight(1f), fontSize = 12.sp)
                                TextButton(onClick = {
                                    scope.launch {
                                        runCatching {
                                            if (asset.mediaType == MediaType.AUDIO) {
                                                runCatching { audioTrack?.stop() }; audioTrack?.release()
                                                audioTrack = withContext(Dispatchers.IO) {
                                                    val reader = MediaEvidenceReader(activity)
                                                    val evidence = reader.loadAudio(asset)
                                                    try { reader.playAudio(evidence) } finally { evidence.pcm.fill(0) }
                                                }
                                                onMessage("Playing event #$observationId audio from encrypted storage")
                                            } else {
                                                videoFrames = withContext(Dispatchers.IO) { MediaEvidenceReader(activity).loadVideo(asset) }
                                                videoFrameIndex = 0
                                                videoTitle = "Event #$observationId · ${asset.streamId}"
                                                onMessage("Loaded ${videoFrames.size} decrypted frames in memory")
                                            }
                                        }.onFailure { onMessage(it.message ?: "Could not open encrypted media") }
                                    }
                                }) { Text(if (asset.mediaType == MediaType.AUDIO) "Play" else "View") }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    val changed = withContext(Dispatchers.IO) { MediaRetentionManager(activity).setEventKeepForever(observationId, !allKept) }
                                    refreshMedia()
                                    onMessage(if (changed > 0) if (allKept) "Event returns to its original deadline" else "Event kept forever" else "No active media changed")
                                }
                            }, modifier = Modifier.weight(1f)) { Text(if (allKept) "Use deadline" else "Keep forever") }
                            OutlinedButton(onClick = { pendingMediaScrub = observationId }, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Scrub event") }
                        }
                    }
                }
                if (purgeLedger.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Purge ledger", fontWeight = FontWeight.SemiBold)
                    purgeLedger.take(5).forEach { entry ->
                        Text("Event #${entry.observationId} · ${entry.mediaType.name.lowercase()} · ${entry.reason} · ${entry.bytesDeleted / 1_024} KiB deleted · derived metrics kept", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedButton(onClick = { refreshMedia() }, modifier = Modifier.fillMaxWidth()) { Text("Refresh media inventory") }
            }
        }

        item { SectionLabel("Ground context + owned RF receiver") }
        item {
            SettingsCard(Icons.Default.Explore, "Ground context", "Local barometer, magnetic field and declination, solar phase, plus gated public NOAA Kp and F10.7 indices.") {
                GateSwitchRow(
                    title = "Ground-context snapshots",
                    detail = "Event/control windows only. NOAA lookup additionally follows the existing environment lookup gate.",
                    enabled = groundEnabled,
                    onCheckedChange = { enabled ->
                        groundEnabled = if (enabled) HardwareGates.setAuthorized(activity, HardwareGates.Gate.LIVE_GROUND_CONTEXT_CAPTURE, true, HardwareGates.ConsentProof.SingleConfirmation) == HardwareGates.AuthorizationResult.ENABLED
                        else { HardwareGates.setAuthorized(activity, HardwareGates.Gate.LIVE_GROUND_CONTEXT_CAPTURE, false); false }
                    }
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val rows = withContext(Dispatchers.IO) { GroundContextProvider(activity).collect(null, false) }
                            val local = rows.count { it.source.contains("ground") || it.source == "local_solar" }
                            val space = rows.count { it.metric.startsWith("space_weather_") }
                            onMessage(if (rows.isEmpty()) "No ground channels available in this window" else "Ground snapshot · $local local · $space space-weather metrics")
                        }
                    },
                    enabled = groundEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Test ground snapshot") }
                Text("Pressure is normalized to hPa. Declination and solar phase need an authorized location fix; missing phone sensors remain absent rather than synthesized.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        item {
            SettingsCard(Icons.Default.GraphicEq, "RTL-SDR survey window", "Tier 3 · bounded IQ from your attached OTG receiver through a user-started rtl_tcp-compatible Android driver.") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("RF survey capture", fontWeight = FontWeight.SemiBold)
                        Text("Raw IQ is app-private, SHA-256 inventoried, and purged after the configured retention period.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Switch(checked = rfSurveyEnabled, onCheckedChange = { enabled ->
                        if (!enabled) { HardwareGates.setAuthorized(activity, HardwareGates.Gate.LIVE_RF_SURVEY_CAPTURE, false); rfSurveyEnabled = false }
                        else confirmRfSurvey = true
                    })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = rfHost, onValueChange = { rfHost = it.trim() }, label = { Text("rtl_tcp host") }, singleLine = true, modifier = Modifier.weight(1.3f))
                    OutlinedTextField(value = rfPort, onValueChange = { rfPort = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(0.7f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = rfCenterMhz, onValueChange = { rfCenterMhz = it.filter { c -> c.isDigit() || c == '.' }.take(10) }, label = { Text("Center MHz") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = rfSampleRate, onValueChange = { rfSampleRate = it.filter(Char::isDigit).take(8) }, label = { Text("Sample rate") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = rfWindowMs, onValueChange = { rfWindowMs = it.filter(Char::isDigit).take(4) }, label = { Text("Window ms") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = rfRetentionDays, onValueChange = { rfRetentionDays = it.filter(Char::isDigit).take(4) }, label = { Text("Retention days") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                fun configuredRf(): RfSurveyConfig? = runCatching {
                    RfSurveyConfig(
                        host = rfHost,
                        port = rfPort.toInt(),
                        centerFrequencyHz = (rfCenterMhz.toDouble() * 1_000_000.0).toLong(),
                        sampleRateHz = rfSampleRate.toInt(),
                        windowMs = rfWindowMs.toInt(),
                        retentionDays = rfRetentionDays.toInt()
                    )
                }.getOrNull()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        configuredRf()?.let { RfSurveySettings.save(activity, it); onMessage("RF survey settings saved") }
                            ?: onMessage("Check RTL host, port, frequency, sample rate, window, and retention")
                    }, modifier = Modifier.weight(1f)) { Text("Save") }
                    OutlinedButton(onClick = {
                        val config = configuredRf()
                        if (config == null) onMessage("Check RF survey settings") else {
                            RfSurveySettings.save(activity, config)
                            scope.launch {
                                val rows = withContext(Dispatchers.IO) { runCatching { RfSurveyContextProvider(activity).collect(null, false) }.getOrDefault(emptyList()) }
                                val bytes = rows.firstOrNull { it.metric == "rf_iq_bytes" }?.value?.toInt()
                                onMessage(if (bytes == null) "No rtl_tcp IQ window · check OTG driver and endpoint" else "RF snapshot · $bytes bounded IQ bytes")
                            }
                        }
                    }, enabled = rfSurveyEnabled, modifier = Modifier.weight(1f)) { Text("Test window") }
                }
                Text("This records what the configured receiver hears during the capture window. It does not identify transmitters, decode communications, or claim calibrated RF power.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }

        item { SectionLabel("Watch") }
        item {
            SettingsCard(Icons.Default.Watch, "Garmin Epix Pro (Gen 2)", garminBridge.deviceText) {
                Text(garminBridge.statusText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                garminBridge.lastDiagnostic?.let {
                    Text("Last diagnostic · $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { GarminBridge.refresh(activity); onMessage(GarminBridge.statusText) }, modifier = Modifier.weight(1f)) { Text("Refresh") }
                    Button(onClick = { GarminBridge.openWatchLogger(activity); onMessage(GarminBridge.statusText) }, modifier = Modifier.weight(1f)) { Text("Open logger") }
                }
            }
        }

        item { SectionLabel("Home context") }
        item {
            SettingsCard(Icons.Default.Home, "Octopod observer", "Optional read-only context from your home cluster.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = homeEnabled, onCheckedChange = { enabled ->
                        homeEnabled = enabled
                        HomeContextSettings.setEnabled(activity, enabled)
                        homeStatus = if (enabled) "Enabled · test the cluster connection" else "Off"
                    })
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (homeEnabled) "Included in events and controls" else "Not collecting", fontWeight = FontWeight.SemiBold)
                        Text(homeStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                OutlinedTextField(
                    value = homeEndpoint,
                    onValueChange = { homeEndpoint = it },
                    label = { Text("Octopod cluster address") },
                    supportingText = { Text("Aggregates only · no names, video, audio, or service tokens") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = ::testHomeContext, enabled = !homeChecking, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.WifiFind, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (homeChecking) "Checking…" else "Save and test")
                }
            }
        }

        item { SectionLabel("Testing") }
        item {
            SettingsCard(Icons.Default.Dataset, "Demo mode", "A separate synthetic database: 60 days, 45 events, 120 controls, six credibility stories, and no live-export eligibility.") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (demoMode.active) "DEMO DATA active" else "Live data active", fontWeight = FontWeight.SemiBold)
                        val summary = demoSummary
                        if (summary != null) Text("${summary.eventCount} events · ${summary.controlCaptureCount} controls · ${summary.registeredHypothesisCount} registration · ${summary.flightSessionCount} flight session", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        else Text("Fixtures remain isolated in apophenia-demo.db.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Switch(checked = demoMode.active, modifier = Modifier.semantics { contentDescription = "Toggle demo mode" }, onCheckedChange = { enabled ->
                        if (audioRingState.active || audioRingState.pendingEvents > 0 || videoRingState.active || videoRingState.pendingEvents > 0 || driveState.active || flightState.armed || controlLinkState.active) {
                            onMessage("Disarm AV, drive, flight, and control-link sessions before changing the demo/live database boundary")
                            return@Switch
                        }
                        if (enabled) {
                            scope.launch {
                                val summary = withContext(Dispatchers.IO) { DemoFixtureInstaller.ensureInstalled(ObservationStore.demoRepository(activity).db()) }
                                DemoModeManager.enable(activity)
                                simulation = true
                                demoSummary = summary
                                onMessage("DEMO DATA active · ${summary.eventCount} synthetic events · live database untouched")
                            }
                        } else {
                            DemoModeManager.disable(activity)
                            simulation = HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION
                            demoSummary = null
                            onMessage("Demo mode off · returned to the live database")
                        }
                    })
                }
                if (demoMode.active) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            demoSummary = withContext(Dispatchers.IO) { DemoFixtureInstaller.reset(ObservationStore.demoRepository(activity).db()) }
                            onMessage("Demo fixtures reset to the deterministic 60-day corpus")
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Reset demo fixtures") }
                }
                Text("The badge stays visible on every tab. Demo mode forces SIMULATION; disabling it restores the previous runtime mode.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        item {
            SettingsCard(Icons.Default.Science, "Simulation mode", "Runs the complete pipeline without physical sensors or services.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = simulation, onCheckedChange = { enabled ->
                        simulation = enabled
                        HardwareGates.setRuntimeMode(activity, if (enabled) HardwareGates.RuntimeMode.SIMULATION else HardwareGates.RuntimeMode.LIVE)
                        GarminBridge.shutdown(activity)
                        GarminBridge.initialize(activity)
                        onMessage(if (enabled) "Simulation mode enabled" else "Live mode enabled")
                    }, enabled = !demoMode.active)
                    Spacer(Modifier.width(12.dp))
                    StatusPill(if (simulation) "SIMULATION" else "LIVE", if (simulation) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary)
                }
            }
        }

        item { SectionLabel("Your data") }
        item {
            SettingsCard(Icons.Default.Radar, "Omniprobe", "Inspect every planned channel for an event: stored values, capture IDs, explicit gaps, live AV state, retention, and export status.") {
                OutlinedButton(onClick = { showOmniprobe = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Visibility, null); Spacer(Modifier.width(8.dp)); Text("Open Omniprobe")
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Build locally, inspect every payload hash, then choose the sharesheet or Android save-as. Nothing is sent while the preview is open.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Button(onClick = { prepareExport(ExportTier.DATA_ONLY) }, enabled = !exportPreparing, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.IosShare, null); Spacer(Modifier.width(8.dp)); Text(if (exportPreparing) "Preparing…" else if (demoMode.active) "Prepare live data-only export" else "Prepare data-only export")
                    }
                    OutlinedButton(onClick = { confirmFullExport = true }, enabled = !exportPreparing, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Inventory2, null); Spacer(Modifier.width(8.dp)); Text("Prepare full evidence package")
                    }
                    OutlinedButton(onClick = ::prepareRawDatabase, enabled = !exportPreparing, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Storage, null); Spacer(Modifier.width(8.dp)); Text("Prepare raw SQLite snapshot")
                    }
                    OutlinedButton(onClick = {
                        if (captureSessionActive()) onMessage("Disarm AV, drive, flight, and control-link sessions before building a full backup")
                        else confirmFullBackup = true
                    }, enabled = !exportPreparing, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Backup, null); Spacer(Modifier.width(8.dp)); Text("Prepare full restorable backup")
                    }
                    OutlinedButton(onClick = ::selectRestore, enabled = !exportPreparing && !restoreApplying && !demoMode.active, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Restore, null); Spacer(Modifier.width(8.dp)); Text(if (demoMode.active) "Restore unavailable in demo" else "Verify and restore backup")
                    }
                    OutlinedButton(onClick = { confirmDelete = true }, enabled = !demoMode.active, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Default.Delete, null); Spacer(Modifier.width(8.dp)); Text(if (demoMode.active) "Live delete unavailable in demo" else "Delete all local data")
                    }
                }
            }
        }
        item {
            SettingsCard(Icons.Default.Lan, "Explicit LAN export", "Standard gate · one deliberate push to a configured local document provider or literal private HTTP(S) address. No background upload and no delivery guarantee.") {
                GateSwitchRow(
                    "LIVE_EXPORT_LAN",
                    if (lanEnabled) "Enabled · ${lanConfiguration.summary}" else "Off · configured destination remains local",
                    lanEnabled,
                    onCheckedChange = { enabled ->
                        lanEnabled = if (enabled) {
                            HardwareGates.setAuthorized(activity, HardwareGates.Gate.LIVE_EXPORT_LAN, true, HardwareGates.ConsentProof.SingleConfirmation) == HardwareGates.AuthorizationResult.ENABLED
                        } else {
                            HardwareGates.setAuthorized(activity, HardwareGates.Gate.LIVE_EXPORT_LAN, false)
                            false
                        }
                    }
                )
                OutlinedButton(onClick = {
                    activity.chooseLanDocumentTree { success, resultMessage ->
                        if (success) {
                            lanConfiguration = lanSettings.configuration()
                            lanEndpoint = ""
                            lanUsername = ""
                            lanPassword = ""
                        }
                        onMessage(resultMessage)
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Choose LAN / SMB / NFS document folder") }
                OutlinedTextField(
                    value = lanEndpoint,
                    onValueChange = { lanEndpoint = it },
                    label = { Text("Private HTTP(S) endpoint") },
                    supportingText = { Text("Literal loopback, RFC1918, link-local, or IPv6 ULA address only; no DNS names") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = lanUsername, onValueChange = { lanUsername = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = lanPassword, onValueChange = { lanPassword = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedButton(onClick = {
                    runCatching { lanSettings.saveHttp(lanEndpoint, lanUsername, lanPassword) }
                        .onSuccess {
                            lanConfiguration = lanSettings.configuration()
                            lanPassword = ""
                            onMessage("Private HTTP(S) LAN destination saved; credentials are Keystore-encrypted")
                        }
                        .onFailure { onMessage("LAN configuration rejected: ${it.message ?: "invalid endpoint"}") }
                }, modifier = Modifier.fillMaxWidth()) { Text("Save private HTTP(S) destination") }
                Text("SMB/NFS transport is supplied by the Android DocumentsProvider you select. Plain HTTP and Basic credentials are visible to that local network; prefer HTTPS where your endpoint supports it. Redirects are refused, and a success response confirms only that endpoint response, not exactly-once delivery.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
    }

    if (showOmniprobe) {
        OmniprobeOverlay(activity = activity, db = repo.db(), onDismiss = { showOmniprobe = false })
    }

    if (confirmFullExport) {
        AlertDialog(
            onDismissRequest = { confirmFullExport = false },
            title = { Text("Build a full evidence package?") },
            text = { Text("Confirmation 1 of 2. This prepares a local ZIP containing the data export plus every retained audio/video stream in portable plaintext formats and every Tier-2 content record in plaintext. No destination receives it until you review the manifest and confirm a route.") },
            confirmButton = {
                Button(onClick = { confirmFullExport = false; prepareExport(ExportTier.FULL_EVIDENCE) }) { Text("Build manifest preview") }
            },
            dismissButton = { TextButton(onClick = { confirmFullExport = false }) { Text("Cancel") } }
        )
    }

    if (confirmFullBackup) {
        AlertDialog(
            onDismissRequest = { confirmFullBackup = false },
            title = { Text("Build a full restorable backup?") },
            text = { Text("Confirmation 1 of 2. The ZIP contains a checkpointed raw SQLite database, portable plaintext copies of retained AV and Tier-2 contents, RF IQ files, and SHA-256 manifests. The backup is integrity-checked, not encrypted as a whole. No destination receives it until you review the manifest.") },
            confirmButton = { Button(onClick = { confirmFullBackup = false; prepareBackup() }) { Text("Build verified backup") } },
            dismissButton = { TextButton(onClick = { confirmFullBackup = false }) { Text("Cancel") } }
        )
    }

    pendingExport?.let { prepared ->
        val manifest = prepared.manifest
        AlertDialog(
            onDismissRequest = { prepared.bundle.delete(); pendingExport = null },
            title = { Text("Manifest preview · ${manifest.tier.displayName}") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("${manifest.entries.size} payload files · ${formatBytes(manifest.totalBytes)} payload · ${formatBytes(prepared.bundle.length())} ZIP", fontWeight = FontWeight.SemiBold)
                    Text("Raw AV: ${if (manifest.containsRawAv) "YES" else "no"} · Tier-2 contents: ${if (manifest.containsTier2Contents) "YES" else "no"}", color = if (manifest.containsRawAv || manifest.containsTier2Contents) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
                    Text("Bundle SHA-256\n${prepared.bundleSha256}", fontSize = 11.sp)
                    if (manifest.tier != ExportTier.DATA_ONLY) {
                        Text("Confirmation 2 of 2: choosing Share, Save, or Push LAN below explicitly releases this plaintext ${manifest.tier.displayName.lowercase()} from the app boundary.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                    } else {
                        Text("Data-only excludes raw AV and Tier-2 contents. Hashed identifiers remain exactly as stored.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                    manifest.entries.forEach { entry ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(entry.path, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text("${formatBytes(entry.sizeBytes)} · AV=${entry.containsRawAv} · Tier2=${entry.containsTier2Contents}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(entry.sha256, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text("manifest.json is included in the ZIP and declares every payload above. The bundle was re-opened and hash-verified before this preview.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (lanEnabled && lanConfiguration.type != LanDestinationType.NONE) {
                        TextButton(onClick = { pushLan(prepared) }, enabled = !lanPushBusy) {
                            Text(if (lanPushBusy) "Pushing…" else "Push LAN")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        activity.saveExportWithSaf(prepared.bundle) { success, resultMessage ->
                            if (success) {
                                prepared.bundle.delete()
                                pendingExport = null
                            }
                            onMessage(resultMessage)
                        }
                    }) { Text("Save as…") }
                    Button(onClick = {
                        activity.shareExports(listOf(prepared.bundle))
                        pendingExport = null
                        onMessage("Opened Android sharesheet for the verified ${manifest.tier.displayName.lowercase()} bundle")
                    }) { Text("Share") }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { prepared.bundle.delete(); pendingExport = null }) { Text("Cancel + delete") } }
        )
    }

    pendingRawDatabase?.let { snapshot ->
        AlertDialog(
            onDismissRequest = { snapshot.file.delete(); pendingRawDatabase = null },
            title = { Text("Raw SQLite preview") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${snapshot.file.name} · ${formatBytes(snapshot.file.length())}", fontWeight = FontWeight.SemiBold)
                    Text("schema_version ${snapshot.schemaVersion} · ${snapshot.observationCount} observations · ${snapshot.contextSampleCount} context samples")
                    Text("SHA-256\n${snapshot.sha256}", fontSize = 11.sp)
                    Text("The WAL was checkpointed with FULL and the copied database passed SQLite integrity_check. This raw database can contain sensitive stored ciphertext and identifiers as stored; it does not include portable AV files.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        activity.saveExportWithSaf(snapshot.file) { success, resultMessage ->
                            if (success) { snapshot.file.delete(); pendingRawDatabase = null }
                            onMessage(resultMessage)
                        }
                    }) { Text("Save as…") }
                    Button(onClick = {
                        activity.shareExport(snapshot.file)
                        pendingRawDatabase = null
                        onMessage("Opened Android sharesheet for the verified SQLite snapshot")
                    }) { Text("Share") }
                }
            },
            dismissButton = { TextButton(onClick = { snapshot.file.delete(); pendingRawDatabase = null }) { Text("Cancel + delete") } }
        )
    }

    pendingRestore?.let { selected ->
        val inspection = selected.inspection
        AlertDialog(
            onDismissRequest = { if (!restoreApplying) { selected.bundle.delete(); pendingRestore = null } },
            title = { Text("Restore verified backup?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The bundle and every nested payload passed declared SHA-256 hashes, SQLite integrity_check, schema checks, and protected-evidence completeness checks before this confirmation.")
                    Text("schema ${inspection.schemaVersion} · ${inspection.observationCount} observations · ${inspection.contextSampleCount} context samples")
                    Text("${inspection.activeMediaCount} active AV streams · ${inspection.sensitiveRecordCount} Tier-2 rows · ${inspection.rfIqFileCount} RF IQ files")
                    Text("This replaces the live database and retained AV/RF files. Portable AV and Tier-2 data are re-encrypted under fresh device-local keys. If any apply step fails, the previous local store is rolled back.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            },
            confirmButton = {
                Button(
                    enabled = !restoreApplying,
                    onClick = {
                        if (captureSessionActive()) {
                            onMessage("Restore stopped: a capture session became active")
                            return@Button
                        }
                        restoreApplying = true
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    BackupManager(activity).restore(ObservationStore.liveRepository(activity).db(), selected.bundle)
                                }
                            }
                            restoreApplying = false
                            result.onSuccess {
                                selected.bundle.delete()
                                pendingRestore = null
                                onMessage("Restore complete · ${it.observationCount} observations · ${it.restoredMediaCount} AV streams")
                                activity.recreate()
                            }.onFailure { onMessage("Restore failed and prior store was recovered: ${it.message ?: "unknown error"}") }
                        }
                    }
                ) { Text(if (restoreApplying) "Restoring…" else "Replace live store") }
            },
            dismissButton = { TextButton(enabled = !restoreApplying, onClick = { selected.bundle.delete(); pendingRestore = null }) { Text("Cancel + delete import") } }
        )
    }

    pendingMediaScrub?.let { observationId ->
        AlertDialog(
            onDismissRequest = { pendingMediaScrub = null },
            title = { Text("Scrub event #$observationId raw media?") },
            text = { Text("Encrypted audio and video, their manifests, and event keys will be deleted. Derived loudness, spectral, motion, brightness, flicker, and scene-change metrics remain for analysis; the purge is recorded in the local ledger.") },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        runCatching { audioTrack?.stop() }; audioTrack?.release(); audioTrack = null
                        videoFrames = emptyList()
                        val purged = withContext(Dispatchers.IO) { MediaRetentionManager(activity).scrubEvent(observationId) }
                        pendingMediaScrub = null
                        refreshMedia()
                        onMessage("Scrubbed $purged raw media stream${if (purged == 1) "" else "s"}; derived metrics kept")
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Scrub raw media") }
            },
            dismissButton = { TextButton(onClick = { pendingMediaScrub = null }) { Text("Cancel") } }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all local data?") },
            text = { Text("This permanently removes observations, hypotheses, controls, rolling samples, ordinary context, encrypted Tier-2 contents, encrypted AV media, and AV keys from this device.") },
            confirmButton = { Button(onClick = { scope.launch { withContext(Dispatchers.IO) { MediaRetentionManager(activity).scrubAll(); repo.db().deleteAllData() }; confirmDelete = false; mediaAssets = emptyList(); purgeLedger = emptyList(); onMessage("All local data and retained AV media deleted") } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }

    pendingDeliberateGate?.let { gate ->
        AlertDialog(
            onDismissRequest = { pendingDeliberateGate = null; deliberateGateInput = "" },
            title = { Text("Enable ${gate.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("This gate captures protected contents at event and control windows and encrypts them on-device before storage.")
                    OutlinedTextField(
                        value = deliberateGateInput,
                        onValueChange = { deliberateGateInput = it },
                        label = { Text("Type the gate name") },
                        supportingText = { Text(gate.name) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = deliberateGateInput.trim() == gate.name,
                    onClick = {
                        val result = HardwareGates.setAuthorized(
                            activity,
                            gate,
                            enabled = true,
                            proof = HardwareGates.ConsentProof.TypedGateName(deliberateGateInput)
                        )
                        pendingDeliberateGate = null
                        deliberateGateInput = ""
                        gateRevision++
                        if (result == HardwareGates.AuthorizationResult.ENABLED && !simulation) {
                            activity.requestTier2PlatformAccess(gate) { _, message -> onMessage(message) }
                        }
                    }
                ) { Text("Enable gate") }
            },
            dismissButton = { TextButton(onClick = { pendingDeliberateGate = null; deliberateGateInput = "" }) { Text("Cancel") } }
        )
    }

    if (showObdAdapterPicker) {
        AlertDialog(
            onDismissRequest = { showObdAdapterPicker = false },
            title = { Text("Choose paired OBD-II adapter") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    pairedObdAdapters.forEach { adapter ->
                        TextButton(
                            onClick = {
                                showObdAdapterPicker = false
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { DriveSessionManager.start(activity, adapter) }
                                    onMessage(result.fold(
                                        onSuccess = {
                                            DriveSessionService.start(activity)
                                            "Drive session started with ${adapter.displayName}"
                                        },
                                        onFailure = { it.message ?: "Drive session could not start" }
                                    ))
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(adapter.displayName, fontWeight = FontWeight.SemiBold)
                                Text(adapter.address, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showObdAdapterPicker = false }) { Text("Cancel") } }
        )
    }

    if (showUsbMavlinkPicker) {
        AlertDialog(
            onDismissRequest = { showUsbMavlinkPicker = false },
            title = { Text("Choose attached MAVLink USB device") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    usbMavlinkDevices.forEach { device ->
                        TextButton(
                            onClick = {
                                showUsbMavlinkPicker = false
                                activity.requestUsbMavlinkPermission(device.deviceId) { granted, message ->
                                    onMessage(message)
                                    if (granted) startMavlink(MavlinkEndpoint.Usb(device.deviceId))
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(device.displayName, fontWeight = FontWeight.SemiBold)
                                Text("57,600 baud · class-compliant bulk/CDC", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showUsbMavlinkPicker = false }) { Text("Cancel") } }
        )
    }

    if (showUsbControlPicker) {
        AlertDialog(
            onDismissRequest = { showUsbControlPicker = false },
            title = { Text("Choose ${selectedControlProtocol.displayName} USB device") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    usbControlDevices.forEach { device ->
                        TextButton(onClick = {
                            showUsbControlPicker = false
                            val baud = controlLinkBaud.toIntOrNull()
                            if (baud == null || baud !in 1_200..2_000_000) { onMessage("Enter a valid serial baud"); return@TextButton }
                            activity.requestUsbMavlinkPermission(device.deviceId) { granted, message ->
                                onMessage(message)
                                if (granted) ControlLinkService.start(activity, selectedControlProtocol, device.deviceId, baud)
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(device.displayName, fontWeight = FontWeight.SemiBold)
                                Text("${selectedControlProtocol.displayName} · $controlLinkBaud baud", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showUsbControlPicker = false }) { Text("Cancel") } }
        )
    }

    if (confirmTakFull) {
        AlertDialog(
            onDismissRequest = { confirmTakFull = false },
            title = { Text("Enable full visible TAK traffic?") },
            text = { Text("At event and control windows, Apophenia will ingest every CoT track visible on your configured connection, label it visible-on-your-connection, hash each UID, and omit callsign text. This does not grant network access or bypass TAK controls.") },
            confirmButton = {
                Button(onClick = {
                    takFullEnabled = HardwareGates.setAuthorized(activity, HardwareGates.Gate.LIVE_TAK_CAPTURE_FULL, true, HardwareGates.ConsentProof.CapabilityConditionalConfirmation) == HardwareGates.AuthorizationResult.ENABLED
                    confirmTakFull = false
                }) { Text("Enable full traffic") }
            },
            dismissButton = { TextButton(onClick = { confirmTakFull = false }) { Text("Cancel") } }
        )
    }

    if (confirmRfSurvey) {
        AlertDialog(
            onDismissRequest = { confirmRfSurvey = false },
            title = { Text("Enable live RF survey windows?") },
            text = { Text("At event and control windows, Apophenia will command your configured rtl_tcp receiver, retain a bounded raw IQ file in app-private storage, and store spectrum summary metrics. Receiver availability, tuning limits, local law, calibration, and antenna behavior remain external capabilities that Apophenia will not invent.") },
            confirmButton = {
                Button(onClick = {
                    rfSurveyEnabled = HardwareGates.setAuthorized(activity, HardwareGates.Gate.LIVE_RF_SURVEY_CAPTURE, true, HardwareGates.ConsentProof.CapabilityConditionalConfirmation) == HardwareGates.AuthorizationResult.ENABLED
                    confirmRfSurvey = false
                }) { Text("Enable RF survey") }
            },
            dismissButton = { TextButton(onClick = { confirmRfSurvey = false }) { Text("Cancel") } }
        )
    }

    if (confirmAudioGate) {
        AlertDialog(
            onDismissRequest = { confirmAudioGate = false },
            title = { Text("Enable live microphone buffering?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("When armed, Apophenia continuously holds the most recent 60 seconds of microphone PCM in memory. Logging any observation freezes that pre-event sound and 30 post-event seconds into a per-event encrypted artifact. Android shows a persistent recording indicator.")
                    Text("Type LIVE_AUDIO_CAPTURE to authorize this deliberate gate.", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(value = audioGateInput, onValueChange = { audioGateInput = it }, singleLine = true, label = { Text("Gate name") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    val enabled = HardwareGates.setAuthorized(
                        activity,
                        HardwareGates.Gate.LIVE_AUDIO_CAPTURE,
                        true,
                        HardwareGates.ConsentProof.TypedGateName(audioGateInput)
                    ) == HardwareGates.AuthorizationResult.ENABLED
                    if (enabled) {
                        audioGateEnabled = true
                        confirmAudioGate = false
                        armAudioRing()
                    } else onMessage("Type LIVE_AUDIO_CAPTURE exactly")
                }) { Text("Authorize + arm") }
            },
            dismissButton = { TextButton(onClick = { confirmAudioGate = false }) { Text("Cancel") } }
        )
    }

    pendingVideoGate?.let { gate ->
        AlertDialog(
            onDismissRequest = { pendingVideoGate = null },
            title = { Text("Authorize ${gate.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("This deliberate gate permits an armed foreground ring for that camera/screen channel. Enabling the gate does not bypass Android camera, concurrency, or MediaProjection controls.")
                    Text("Type ${gate.name} exactly.", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(value = videoGateInput, onValueChange = { videoGateInput = it }, singleLine = true, label = { Text("Gate name") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    val enabled = HardwareGates.setAuthorized(activity, gate, true, HardwareGates.ConsentProof.TypedGateName(videoGateInput)) == HardwareGates.AuthorizationResult.ENABLED
                    if (!enabled) onMessage("Type ${gate.name} exactly") else {
                        when (gate) {
                            HardwareGates.Gate.LIVE_VIDEO_CAPTURE -> mainVideoEnabled = true
                            HardwareGates.Gate.LIVE_VIDEO_SELFCAPTURE -> frontVideoEnabled = true
                            HardwareGates.Gate.LIVE_MULTICAM_CAPTURE -> multicamEnabled = true
                            HardwareGates.Gate.LIVE_SCREENRECORD_CAPTURE -> screenVideoEnabled = true
                            else -> Unit
                        }
                        pendingVideoGate = null
                    }
                }) { Text("Authorize") }
            },
            dismissButton = { TextButton(onClick = { pendingVideoGate = null }) { Text("Cancel") } }
        )
    }

    if (confirmCallAudio) {
        AlertDialog(
            onDismissRequest = { confirmCallAudio = false },
            title = { Text("Authorize per-call audio capability?") },
            text = { Text("The gate records your intent but cannot create a platform API or legal authority. Each call would still require a separate opt-in and capability check. ${CallAudioCapability.explanation(activity)}") },
            confirmButton = {
                Button(onClick = {
                    callAudioEnabled = HardwareGates.setAuthorized(activity, HardwareGates.Gate.LIVE_CALL_AUDIO_CAPTURE, true, HardwareGates.ConsentProof.CapabilityConditionalConfirmation) == HardwareGates.AuthorizationResult.ENABLED
                    confirmCallAudio = false
                }) { Text("Authorize gate") }
            },
            dismissButton = { TextButton(onClick = { confirmCallAudio = false }) { Text("Cancel") } }
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.2f GiB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.2f MiB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.1f KiB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

@Composable
private fun SettingsCard(icon: ImageVector, title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(9.dp).size(20.dp))
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

@Composable
private fun AccessRow(icon: ImageVector, title: String, status: String, ready: Boolean, action: String? = null, actionEnabled: Boolean = true, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (ready) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (action != null) TextButton(onClick = onClick, enabled = actionEnabled) { Text(action) }
        else Icon(if (ready) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null, tint = if (ready) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun GateSwitchRow(
    title: String,
    detail: String,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    status: String = if (enabled) "Armed" else "Off"
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = enabled, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                StatusPill(status, if (enabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ScreenHeader(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(999.dp)) {
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), maxLines = 1)
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.AutoGraph, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
