package com.dronewukong.apophenia.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.dronewukong.apophenia.data.ObservationStore
import com.dronewukong.apophenia.ingest.InboundShareParser
import com.dronewukong.apophenia.ingest.ObservationAttachmentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android Sharesheet target. Selecting it is the explicit action that creates the observation. */
class ShareToApopheniaActivity : ComponentActivity() {
    private var status by mutableStateOf("Freezing the share-time context window…")
    private var complete by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val receivedAtMs = System.currentTimeMillis()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("Log to Apophenia", style = MaterialTheme.typography.headlineSmall)
                        Text(status)
                        if (complete) Button(onClick = ::finish) { Text("Done") }
                    }
                }
            }
        }
        if (savedInstanceState == null) capture(receivedAtMs)
    }

    private fun capture(receivedAtMs: Long) {
        val parsed = runCatching { InboundShareParser.parse(this, intent, receivedAtMs) }
            .getOrElse {
                status = "Nothing was logged: ${it.message ?: "unsupported share"}"
                complete = true
                return
            }
        val repository = ObservationStore.liveRepository(this)
        // log() freezes AV/rolling context synchronously at receivedAtMs before attachment I/O.
        repository.log(parsed.request) { observationId ->
            lifecycleScope.launch {
                val attached = runCatching {
                    withContext(Dispatchers.IO) {
                        ObservationAttachmentStore(this@ShareToApopheniaActivity, repository.db())
                            .store(observationId, parsed.attachment, receivedAtMs)
                    }
                }
                status = attached.fold(
                    onSuccess = { "Logged event #$observationId with ${it.displayName}. Full context enrichment continues locally." },
                    onFailure = { "Event #$observationId was logged, but its attachment failed: ${it.message ?: "unknown error"}" }
                )
                complete = true
            }
        }
    }
}
