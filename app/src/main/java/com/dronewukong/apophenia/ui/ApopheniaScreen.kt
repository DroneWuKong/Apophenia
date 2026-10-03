package com.dronewukong.apophenia.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dronewukong.apophenia.correlation.AssociationEngine
import com.dronewukong.apophenia.correlation.CaptureMatcher
import com.dronewukong.apophenia.data.*
import com.dronewukong.apophenia.environment.EnvironmentProvider
import com.dronewukong.apophenia.export.ExportManager
import com.dronewukong.apophenia.garmin.GarminBridge
import com.dronewukong.apophenia.hardware.HardwareGates
import com.dronewukong.apophenia.health.HealthConnectAccess
import com.dronewukong.apophenia.rolling.RollingRecorderService
import com.dronewukong.apophenia.rolling.RollingRecorderHealth
import com.dronewukong.apophenia.rolling.RollingRecorderState
import com.dronewukong.apophenia.work.PromptedCheckInScheduler
import com.dronewukong.apophenia.work.PromptedCheckInState
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
    val repo = remember { ObservationStore.repository(activity) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf(Tab.LOG) }
    var refresh by remember { mutableIntStateOf(0) }
    val introPrefs = remember { activity.getSharedPreferences("onboarding", Context.MODE_PRIVATE) }
    var showContextIntro by remember { mutableStateOf(!introPrefs.getBoolean("context_intro_v1", false)) }
    fun message(text: String) { scope.launch { snackbar.showSnackbar(text) } }

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
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (tab) {
                    Tab.LOG -> LogTab(repo, onSaved = { refresh++ }, onOpenSettings = { tab = Tab.SETTINGS })
                    Tab.TIMELINE -> TimelineTab(repo, refresh)
                    Tab.PATTERNS -> PatternsTab(repo, refresh)
                    Tab.SETTINGS -> SettingsTab(activity, repo, scope, ::message)
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
                                label = action.captureLabel
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
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        label = { Text(if (kind == ObservationKind.HYPOTHESIS_NOTE) "What is the hypothesis?" else "What did you notice?") },
                        value = label,
                        onValueChange = { label = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(label = { Text("Optional note") }, value = note, onValueChange = { note = it }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val capturedAt = System.currentTimeMillis()
                    repo.log(kind, label, note, timestampMs = capturedAt, onSaved = { onSaved() })
                    showForm = false
                    note = ""
                }) { Text("Log now") }
            },
            dismissButton = { TextButton(onClick = { showForm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun TimelineTab(repo: ObservationRepository, refresh: Int) {
    var rows by remember { mutableStateOf<List<Observation>>(emptyList()) }
    var hypotheses by remember { mutableStateOf<List<Hypothesis>>(emptyList()) }
    LaunchedEffect(refresh) {
        val loaded = withContext(Dispatchers.IO) { repo.observations() to repo.hypotheses() }
        rows = loaded.first
        hypotheses = loaded.second
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader("Timeline", "Observations and interpretations stay separate.") }
        if (rows.isEmpty() && hypotheses.isEmpty()) item { EmptyState("No observations yet", "Your one-tap logs will appear here.") }
        if (hypotheses.isNotEmpty()) {
            item { SectionLabel("Hypotheses") }
            items(hypotheses, key = { "h-${it.id}" }) { hypothesis ->
                TimelineCard(hypothesis.eventLabel, "HYPOTHESIS", hypothesis.createdAtMs, hypothesis.note, MaterialTheme.colorScheme.secondary)
            }
        }
        if (rows.isNotEmpty()) {
            item { SectionLabel("Observations") }
            items(rows, key = { "o-${it.id}" }) { observation ->
                TimelineCard(observation.label, observation.kind.name.replace('_', ' '), observation.timestampMs, observation.note, MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun TimelineCard(title: String, type: String, timestampMs: Long, note: String, accent: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
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
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PatternsTab(repo: ObservationRepository, refresh: Int) {
    var labels by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<Pair<String, com.dronewukong.apophenia.correlation.AssociationResult>>>(emptyList()) }
    LaunchedEffect(refresh) {
        labels = withContext(Dispatchers.IO) { repo.db().labels() }
        if (selected == null) selected = labels.firstOrNull()?.first
    }
    LaunchedEffect(selected, refresh) {
        val selectedLabel = selected ?: return@LaunchedEffect
        results = withContext(Dispatchers.IO) {
            val values = linkedMapOf<String, Pair<List<Double>, List<Double>>>()
            fun addMatched(name: String, events: List<com.dronewukong.apophenia.correlation.TimedCaptureValue>, controls: List<com.dronewukong.apophenia.correlation.TimedCaptureValue>) {
                val matched = CaptureMatcher.match(events, controls)
                values[name] = matched.events to matched.controls
            }
            repo.db().metricsForLabel(selectedLabel).forEach { metric ->
                addMatched(metric, repo.db().eventFeatureCaptures(selectedLabel, metric), repo.db().controlFeatureCaptures(metric))
                val eventDelta = repo.db().eventBeforeDeltaCaptures(selectedLabel, metric)
                val controlDelta = repo.db().controlBeforeDeltaCaptures(metric)
                if (eventDelta.isNotEmpty() || controlDelta.isNotEmpty()) addMatched("$metric · before delta", eventDelta, controlDelta)
                listOf(0L to 600_000L, 600_000L to 1_200_000L, 1_200_000L to 1_800_000L).forEachIndexed { index, (from, to) ->
                    val events = repo.db().eventLagFeatureCaptures(selectedLabel, metric, from, to)
                    val controls = repo.db().controlLagFeatureCaptures(metric, from, to)
                    if (events.isNotEmpty() || controls.isNotEmpty()) addMatched("$metric · ${index * 10}-${(index + 1) * 10}m pre", events, controls)
                }
            }
            AssociationEngine.compareAll(values).toList().sortedByDescending { kotlin.math.abs(it.second.standardizedEffect ?: 0.0) }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader("Patterns", "Event windows compared with one-to-one matched control windows.") }
        item {
            Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(9.dp))
                    Text("Controls are matched by local 4-hour time block and weekday/weekend. Post-event samples are excluded. Associations are evidence to inspect, not proof of cause.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (labels.isEmpty()) item { EmptyState("Not enough data", "Log repeated observations and let random controls accumulate.") }
        else {
            item {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(value = selected.orEmpty(), onValueChange = {}, readOnly = true, label = { Text("Observation category") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        labels.forEach { (name, count) -> DropdownMenuItem(text = { Text("$name ($count)") }, onClick = { selected = name; expanded = false }) }
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
                        Text(result.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 5.dp))
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
    var rollingSummary by remember { mutableStateOf("No samples yet") }
    var healthStatus by remember { mutableStateOf("Checking…") }
    var locationAllowed by remember { mutableStateOf(activity.hasLocationPermission()) }
    var notificationsAllowed by remember { mutableStateOf(activity.hasNotificationPermission()) }
    var weatherStatus by remember { mutableStateOf(if (locationAllowed) "Ready to check" else "Location off") }
    var weatherChecking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val permissionRevision = activity.permissionRevision

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
        scope.launch { healthStatus = withContext(Dispatchers.IO) { HealthConnectAccess.permissionSummary(activity) } }
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

    LaunchedEffect(permissionRevision) { refreshPermissionState() }
    LaunchedEffect(Unit) { refreshRolling() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader("Settings", "Everything stays local unless you enable an optional source.") }
        item { SectionLabel("Recorder") }
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

        item { SectionLabel("Testing") }
        item {
            SettingsCard(Icons.Default.Science, "Simulation mode", "Runs the complete pipeline without physical sensors or services.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = simulation, onCheckedChange = { enabled ->
                        simulation = enabled
                        HardwareGates.setRuntimeMode(activity, if (enabled) HardwareGates.RuntimeMode.SIMULATION else HardwareGates.RuntimeMode.LIVE)
                        GarminBridge.shutdown(activity)
                        GarminBridge.initialize(activity)
                        onMessage(if (enabled) "Simulation mode enabled" else "Live mode enabled")
                    })
                    Spacer(Modifier.width(12.dp))
                    StatusPill(if (simulation) "SIMULATION" else "LIVE", if (simulation) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary)
                }
            }
        }

        item { SectionLabel("Your data") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) { ExportManager.exportJson(repo.db(), File(activity.cacheDir, "exports")) }
                            onMessage("Created ${file.name}")
                            activity.shareExport(file)
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.IosShare, null); Spacer(Modifier.width(8.dp)); Text("Export JSON")
                    }
                    OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Default.Delete, null); Spacer(Modifier.width(8.dp)); Text("Delete all local data")
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all local data?") },
            text = { Text("This permanently removes observations, hypotheses, controls, rolling samples, and captured context from this device.") },
            confirmButton = { Button(onClick = { scope.launch { withContext(Dispatchers.IO) { repo.db().deleteAllData() }; confirmDelete = false; onMessage("All local data deleted") } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
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
