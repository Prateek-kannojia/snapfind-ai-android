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

/** See TimingSpikeRunner.kt for scope notes. Run with a release build for real numbers. */
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
                        Text("On-device timing spike", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Photos dir: ${runner.photosDir().absolutePath}\n" +
                                "Results CSV: ${runner.resultsCsv().absolutePath}",
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
                                    val results = withContext(Dispatchers.Default) {
                                        runner.loadModels()
                                        runner.run { done, total, name ->
                                            status = "Photo $done/$total: $name"
                                        }
                                    }
                                    withContext(Dispatchers.Default) { runner.close() }
                                    summary = buildSummary(results)
                                    status = "Done. ${results.size} photos. CSV written to ${runner.resultsCsv().absolutePath}"
                                    running = false
                                }
                            }
                        ) {
                            Text(if (running) "Running..." else "Run spike")
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

private fun buildSummary(results: List<PhotoTiming>): String {
    if (results.isEmpty()) return "No photos found. Push .jpg/.png files into the photos dir above, then re-run."

    fun percentile(values: List<Double>, p: Double): Double {
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * p).toInt()
        return sorted[idx]
    }

    fun stat(label: String, values: List<Double>): String {
        val mean = values.average()
        val median = percentile(values, 0.5)
        val p95 = percentile(values, 0.95)
        return "%-16s mean %6.1fms  median %6.1fms  p95 %6.1fms".format(label, mean, median, p95)
    }

    return buildString {
        appendLine("n = ${results.size} photos")
        appendLine(stat("decode", results.map { it.decodeMs }))
        appendLine(stat("det preprocess", results.map { it.detPreprocessMs }))
        appendLine(stat("det infer", results.map { it.detInferMs }))
        appendLine(stat("rec preprocess", results.map { it.recPreprocessMs }))
        appendLine(stat("rec infer", results.map { it.recInferMs }))
        appendLine(stat("TOTAL", results.map { it.totalMs }))
    }
}
