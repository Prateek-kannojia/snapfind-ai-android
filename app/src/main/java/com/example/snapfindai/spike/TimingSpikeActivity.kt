package com.example.snapfindai.spike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.snapfindai.ui.theme.SnapFindAITheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Validation harness for the facesdk module — see TimingSpikeRunner.kt. Run with a release build for real numbers. */
class TimingSpikeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runner = TimingSpikeRunner(applicationContext)

        setContent {
            SnapFindAITheme {
                var status by remember { mutableStateOf("Idle.") }
                var running by remember { mutableStateOf(false) }
                var summary by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()

                Scaffold { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text("facesdk validation harness", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Photos dir: ${runner.photosDir().absolutePath}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            enabled = !running,
                            onClick = {
                                running = true
                                summary = null
                                status = "Loading models..."
                                scope.launch {
                                    val csv = withContext(Dispatchers.Default) {
                                        runner.loadModels()
                                        val f = runner.runDetectionValidation { done, total, name ->
                                            status = "Photo $done/$total: $name"
                                        }
                                        runner.close()
                                        f
                                    }
                                    summary = "Detection validation (Phase A) written to:\n${csv.absolutePath}\n\n" +
                                        "adb pull it and compare against the real Python/insightface " +
                                        "detector on the same photos."
                                    status = "Done."
                                    running = false
                                }
                            }
                        ) {
                            Text(if (running) "Running..." else "Run detection validation (Phase A)")
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            enabled = !running,
                            onClick = {
                                running = true
                                summary = null
                                status = "Loading models..."
                                scope.launch {
                                    val csv = withContext(Dispatchers.Default) {
                                        runner.loadModels()
                                        val f = runner.runAlignmentValidation { done, total, name ->
                                            status = "Photo $done/$total: $name"
                                        }
                                        runner.close()
                                        f
                                    }
                                    summary = "Alignment validation (Phase B) written to:\n${csv.absolutePath}\n\n" +
                                        "adb pull it and compare embeddings against the server's own " +
                                        "detect+align+embed output for the same photos."
                                    status = "Done."
                                    running = false
                                }
                            }
                        ) {
                            Text(if (running) "Running..." else "Run alignment validation (Phase B)")
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            enabled = !running,
                            onClick = {
                                running = true
                                summary = null
                                status = "Loading models..."
                                scope.launch {
                                    val csv = withContext(Dispatchers.Default) {
                                        runner.loadModels()
                                        val f = runner.runJobMatching { done, total, name ->
                                            status = "Job $done/$total: $name"
                                        }
                                        runner.close()
                                        f
                                    }
                                    summary = "Job matching (Phase C) written to:\n${csv.absolutePath}\n\n" +
                                        "adb pull it and score with load_device_distances() against the " +
                                        "server's own sweep output for the same jobs."
                                    status = "Done."
                                    running = false
                                }
                            }
                        ) {
                            Text(if (running) "Running..." else "Run job matching (Phase C)")
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(status)
                        summary?.let {
                            Spacer(Modifier.height(16.dp))
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
