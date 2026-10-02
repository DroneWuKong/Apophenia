package com.dronewukong.apophenia.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dronewukong.apophenia.correlation.AssociationEngine
import com.dronewukong.apophenia.data.*
import com.dronewukong.apophenia.export.ExportManager
import com.dronewukong.apophenia.garmin.GarminBridge
import com.dronewukong.apophenia.hardware.HardwareGates
import com.dronewukong.apophenia.health.HealthConnectAccess
import com.dronewukong.apophenia.rolling.RollingRecorderService
import com.dronewukong.apophenia.rolling.RollingRecorderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.io.File

private enum class Tab { LOG, TIMELINE, PATTERNS, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApopheniaScreen(activity: MainActivity) {
    val repo = remember { ObservationRepository(activity) }
    var tab by remember { mutableStateOf(Tab.LOG) }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val colors = darkColorScheme(background=Color(0xFF101114), surface=Color(0xFF17191E), primary=Color(0xFFDAE2F2), secondary=Color(0xFF8F9BAD))
    MaterialTheme(colorScheme=colors) {
        Scaffold(containerColor=colors.background,bottomBar={
            NavigationBar {
                listOf(Tab.LOG to Icons.Default.Add,Tab.TIMELINE to Icons.Default.History,Tab.PATTERNS to Icons.Default.AutoGraph,Tab.SETTINGS to Icons.Default.Science).forEach{(t,icon)->
                    NavigationBarItem(selected=tab==t,onClick={tab=t},icon={Icon(icon,null)},label={Text(t.name.lowercase().replaceFirstChar{it.uppercase()})})
                }
            }
        }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when(tab){Tab.LOG->LogTab(repo){refresh++};Tab.TIMELINE->TimelineTab(repo,refresh);Tab.PATTERNS->PatternsTab(repo,refresh);Tab.SETTINGS->SettingsTab(activity,repo,scope)}
            }
        }
    }
}

@Composable private fun LogTab(repo: ObservationRepository,onSaved:()->Unit) {
    var showForm by remember{mutableStateOf(false)};var label by remember{mutableStateOf("")};var note by remember{mutableStateOf("")};var kind by remember{mutableStateOf(ObservationKind.OBSERVATION)}
    var common by remember{mutableStateOf<List<String>>(emptyList())}
    LaunchedEffect(Unit){common=withContext(Dispatchers.IO){repo.db().labels().map{it.first}.filterNot{it.equals("That was weird",true)}.take(3)}}
    val context=LocalContext.current
    val recorderText=if(RollingRecorderState.isEnabled(context)) "Black box active" else "Black box off"
    Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Text("APOPHENIA",fontSize=13.sp,color=MaterialTheme.colorScheme.secondary,fontWeight=FontWeight.Bold)
        Text("Log the moment. Test the pattern later.",fontSize=25.sp,fontWeight=FontWeight.SemiBold)
        Text("${if(HardwareGates.runtimeMode==HardwareGates.RuntimeMode.SIMULATION)"SIMULATION" else "LIVE"} · $recorderText",fontSize=12.sp,color=MaterialTheme.colorScheme.secondary)
        Button(onClick={val capturedAt=System.currentTimeMillis();repo.log(ObservationKind.WEIRD,"That was weird",timestampMs=capturedAt,onSaved={onSaved()})},modifier=Modifier.fillMaxWidth().height(92.dp),shape=RoundedCornerShape(24.dp)){Text("THAT WAS WEIRD",fontSize=20.sp,fontWeight=FontWeight.Bold)}
        Text("One tap records the timestamp immediately, then captures available sensor, device, location and environmental context.",color=MaterialTheme.colorScheme.secondary)
        listOf(listOf("Observation","Headache","Sinus"),listOf("Light","Sound","Body"),listOf("Coincidence","Hypothesis","Other")).forEach{row->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){row.forEach{t->OutlinedButton(onClick={kind=when(t){"Coincidence"->ObservationKind.COINCIDENCE;"Hypothesis"->ObservationKind.HYPOTHESIS_NOTE;else->ObservationKind.OBSERVATION};label=when(t){"Sinus"->"Sinus / congestion";"Light"->"Light changed";"Sound"->"Sound / noise";"Device"->"Device behaved oddly";"Body"->"Body / sensation";"Hypothesis"->"I think this happens when…";"Other"->"";else->t};showForm=true},modifier=Modifier.weight(1f).height(58.dp)){Text(t)}}}
        }
        if(common.isNotEmpty()){Text("Recent",fontSize=12.sp,color=MaterialTheme.colorScheme.secondary);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){common.forEach{recent->OutlinedButton(onClick={val capturedAt=System.currentTimeMillis();repo.log(ObservationKind.OBSERVATION,recent,timestampMs=capturedAt,onSaved={onSaved()})},modifier=Modifier.weight(1f)){Text(recent,maxLines=1)}}}}
        Spacer(Modifier.weight(1f));Text("Tip: don't explain it while logging it. Describe what happened. Add the theory later.",color=MaterialTheme.colorScheme.secondary,fontSize=13.sp)
    }
    if(showForm) AlertDialog(onDismissRequest={showForm=false},title={Text(if(kind==ObservationKind.COINCIDENCE)"Log coincidence" else if(kind==ObservationKind.HYPOTHESIS_NOTE)"Record hypothesis" else "Log observation")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){OutlinedTextField(label={Text(if(kind==ObservationKind.HYPOTHESIS_NOTE)"What is the hypothesis?" else "What did you notice?")},value=label,onValueChange={label=it},modifier=Modifier.fillMaxWidth());OutlinedTextField(label={Text("Optional note")},value=note,onValueChange={note=it},modifier=Modifier.fillMaxWidth(),minLines=2)}},confirmButton={Button(onClick={val capturedAt=System.currentTimeMillis();repo.log(kind,label,note,timestampMs=capturedAt,onSaved={onSaved()});showForm=false;note=""}){Text("Log now")}},dismissButton={TextButton(onClick={showForm=false}){Text("Cancel")}})
}

@Composable private fun TimelineTab(repo:ObservationRepository,refresh:Int){
    var rows by remember{mutableStateOf<List<Observation>>(emptyList())}
    var hypotheses by remember{mutableStateOf<List<Hypothesis>>(emptyList())}
    LaunchedEffect(refresh){val loaded=withContext(Dispatchers.IO){repo.observations() to repo.hypotheses()};rows=loaded.first;hypotheses=loaded.second}
    if(rows.isEmpty()&&hypotheses.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("No observations yet.")}
    else LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),contentPadding=PaddingValues(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        if(hypotheses.isNotEmpty()){item{Text("Hypotheses",fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.secondary)};items(hypotheses,key={"h-${it.id}"}){h->Card{Column(Modifier.fillMaxWidth().padding(14.dp)){Text(h.eventLabel,fontWeight=FontWeight.SemiBold);Text("HYPOTHESIS · "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM).format(Date(h.createdAtMs)),fontSize=11.sp,color=MaterialTheme.colorScheme.secondary);if(h.note.isNotBlank())Text(h.note,modifier=Modifier.padding(top=5.dp))}}}}
        if(rows.isNotEmpty()){item{Text("Observations",fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.secondary)};items(rows,key={"o-${it.id}"}){o->Card{Column(Modifier.fillMaxWidth().padding(14.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text(o.label,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f));Text(o.kind.name.replace('_',' '),fontSize=11.sp,color=MaterialTheme.colorScheme.secondary)};Text(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM).format(Date(o.timestampMs)),fontSize=12.sp,color=MaterialTheme.colorScheme.secondary);if(o.note.isNotBlank())Text(o.note,modifier=Modifier.padding(top=5.dp))}}}}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PatternsTab(repo:ObservationRepository,refresh:Int){
    var labels by remember{mutableStateOf<List<Pair<String,Int>>>(emptyList())}
    var selected by remember{mutableStateOf<String?>(null)}
    var results by remember{mutableStateOf<List<Pair<String,com.dronewukong.apophenia.correlation.AssociationResult>>>(emptyList())}
    LaunchedEffect(refresh){labels=withContext(Dispatchers.IO){repo.db().labels()};if(selected==null)selected=labels.firstOrNull()?.first}
    LaunchedEffect(selected,refresh){val label=selected?:return@LaunchedEffect;results=withContext(Dispatchers.IO){
        val values=linkedMapOf<String,Pair<List<Double>,List<Double>>>()
        repo.db().metricsForLabel(label).forEach{metric->
            values[metric]=repo.db().eventFeatureValues(label,metric) to repo.db().controlFeatureValues(metric)
            val eventDelta=repo.db().eventBeforeDeltaValues(label,metric);val controlDelta=repo.db().controlBeforeDeltaValues(metric)
            if(eventDelta.isNotEmpty()||controlDelta.isNotEmpty())values["$metric · before delta"]=eventDelta to controlDelta
            listOf(0L to 600_000L,600_000L to 1_200_000L,1_200_000L to 1_800_000L).forEachIndexed{index,(from,to)->
                val events=repo.db().eventLagFeatureValues(label,metric,from,to);val controls=repo.db().controlLagFeatureValues(metric,from,to)
                if(events.isNotEmpty()||controls.isNotEmpty())values["$metric · ${index*10}-${(index+1)*10}m pre"]=events to controls
            }
        }
        AssociationEngine.compareAll(values).toList().sortedByDescending{kotlin.math.abs(it.second.standardizedEffect?:0.0)}
    }}
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("Patterns",fontSize=24.sp,fontWeight=FontWeight.SemiBold)
        Text("Each event and control window counts once. Post-event samples are excluded. Adjusted results are evidence to inspect, not proof of cause.",color=MaterialTheme.colorScheme.secondary)
        if(labels.isEmpty())Text("Log a few events first.")else{
            var expanded by remember{mutableStateOf(false)}
            ExposedDropdownMenuBox(expanded=expanded,onExpandedChange={expanded=!expanded}){
                OutlinedTextField(value=selected.orEmpty(),onValueChange={},readOnly=true,label={Text("Observation")},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded)},modifier=Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                ExposedDropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){labels.forEach{(name,count)->DropdownMenuItem(text={Text("$name ($count)")},onClick={selected=name;expanded=false})}}
            }
            LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){items(results){(metric,result)->Card{Column(Modifier.fillMaxWidth().padding(12.dp)){Row{Text(metric.replace('_',' '),fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f));Text(result.strength,fontWeight=FontWeight.Bold)};Text("events ${result.eventCount} · controls ${result.controlCount}",fontSize=12.sp,color=MaterialTheme.colorScheme.secondary);Text(result.summary,fontSize=13.sp,modifier=Modifier.padding(top=4.dp))}}}}
        }
    }
}

@Composable
private fun SettingsTab(activity: MainActivity, repo: ObservationRepository, scope: kotlinx.coroutines.CoroutineScope) {
    var sim by remember { mutableStateOf(HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) }
    var rolling by remember { mutableStateOf(RollingRecorderState.isEnabled(activity)) }
    var status by remember { mutableStateOf("") }
    var garminStatus by remember { mutableStateOf(GarminBridge.statusText) }
    var garminDevice by remember { mutableStateOf(GarminBridge.deviceText) }
    var rollingSummary by remember { mutableStateOf("No rolling samples yet") }
    var healthStatus by remember { mutableStateOf(HealthConnectAccess.statusText(activity)) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun refreshRolling() {
        scope.launch {
            val s = withContext(Dispatchers.IO) { repo.db().rollingStatus() }
            rollingSummary = if (s.first == 0 || s.second == null || s.third == null) "No rolling samples yet"
                else "${s.first} samples · ${"%.1f".format((s.third!! - s.second!!) / 60000.0)} min span"
        }
    }
    LaunchedEffect(Unit) { refreshRolling() }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Rolling black box", fontWeight = FontWeight.Bold)
                    Text("Keeps a bounded 30-minute pre-event sensor buffer.", fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = rolling, onCheckedChange = { enabled ->
                            if (enabled) activity.requestNotificationPermission()
                            rolling = enabled
                            RollingRecorderService.setEnabled(activity, enabled)
                            refreshRolling()
                        })
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(if (rolling) "Enabled" else "Off")
                            Text(rollingSummary, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Garmin Epix Pro (Gen 2)", fontWeight = FontWeight.Bold)
                    Text(garminDevice, fontSize = 13.sp)
                    Text(garminStatus, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            GarminBridge.refresh(activity)
                            garminStatus = GarminBridge.statusText
                            garminDevice = GarminBridge.deviceText
                        }, modifier = Modifier.weight(1f)) { Text("Refresh") }
                        Button(onClick = {
                            GarminBridge.openWatchLogger(activity)
                            garminStatus = GarminBridge.statusText
                            garminDevice = GarminBridge.deviceText
                        }, modifier = Modifier.weight(1f)) { Text("Open logger") }
                    }
                }
            }
        }
        item {
            Button(onClick = { activity.requestLocationPermission() }, modifier = Modifier.fillMaxWidth()) {
                Text("Allow location for weather context")
            }
        }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Health Connect", fontWeight = FontWeight.Bold)
                    Text("$healthStatus · optional read-only historical context", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                    OutlinedButton(onClick = { activity.requestHealthPermissions(); healthStatus = HealthConnectAccess.statusText(activity) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Review Health Connect permissions")
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = sim, onCheckedChange = { enabled ->
                    sim = enabled
                    HardwareGates.setRuntimeMode(activity, if (enabled) HardwareGates.RuntimeMode.SIMULATION else HardwareGates.RuntimeMode.LIVE)
                    GarminBridge.shutdown(activity)
                    GarminBridge.initialize(activity)
                })
                Spacer(Modifier.width(10.dp))
                Text("Simulation mode")
            }
        }
        item {
            OutlinedButton(onClick = {
                scope.launch {
                    val file = withContext(Dispatchers.IO) { ExportManager.exportJson(repo.db(), File(activity.cacheDir, "exports")) }
                    status = "Exported ${file.name}"
                    activity.shareExport(file)
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.IosShare, null)
                Spacer(Modifier.width(8.dp))
                Text("Create JSON export")
            }
        }
        item {
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text("Delete all local data")
            }
        }
        if (status.isNotBlank()) item { Text(status) }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete all local data?") },
        text = { Text("This permanently removes observations, hypotheses, controls, rolling samples, and captured context from this device.") },
        confirmButton = { Button(onClick = { scope.launch { withContext(Dispatchers.IO) { repo.db().deleteAllData() }; status = "All local data deleted"; confirmDelete = false } }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
    )
}
