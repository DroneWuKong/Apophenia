package com.dronewukong.apophenia.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF090B10),
                    surface = Color(0xFF121722),
                    primary = Color(0xFFAFC8FF),
                    tertiary = Color(0xFF75DDB7),
                    onBackground = Color(0xFFE8ECF5),
                    onSurface = Color(0xFFE8ECF5),
                    onSurfaceVariant = Color(0xFFB8C0D0)
                )
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item { Icon(Icons.Default.HealthAndSafety, null, tint = MaterialTheme.colorScheme.tertiary) }
                    item { Text("Health Connect privacy", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
                    item {
                        Text(
                            "Health Connect is optional. Apophenia requests read-only access so heart rate, resting heart rate, sleep, steps, oxygen saturation, and exercise duration can be attached as historical context to observations you choose to log."
                        )
                    }
                    item {
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                RationalePoint("Local-first", "Health data is stored only in the app's local observation database and included only in exports you create.")
                                RationalePoint("Read-only", "The app never writes to or changes your Health Connect records.")
                                RationalePoint("No causal claims", "Health data may be compared with event and random-control windows, but correlations are not presented as medical conclusions or proven causes.")
                                RationalePoint("Your choice", "You can deny or revoke any permission at any time. Logging continues without Health Connect.")
                            }
                        }
                    }
                    item { Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Done") } }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun RationalePoint(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
