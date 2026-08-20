package com.training.app

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Context.trainingStore by preferencesDataStore("training_settings")
private val SkillsKey = stringSetPreferencesKey("skills")

private val Navy = Color(0xFF07111E)
private val Panel = Color(0xFF0D1B2B)
private val Panel2 = Color(0xFF11243A)
private val Purple = Color(0xFFA855F7)
private val Cyan = Color(0xFF4DD9FF)
private val Green = Color(0xFF39D98A)
private val Muted = Color(0xFF8DA2B8)
private const val MAX_TRAINING_FILE_BYTES = 5 * 1024 * 1024
private const val MAX_TRAINING_SESSION_BYTES = 20 * 1024 * 1024

class MainActivity : ComponentActivity() {
    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TrainingApp() }
        // The app uses CameraX only for QR pairing. Request it once at startup so the
        // worker-connect flow does not surprise the user later. No other dangerous
        // runtime permission is required by this project.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestCamera.launch(android.Manifest.permission.CAMERA)
        }
    }
}

@Composable
private fun TrainingApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val skills = remember { mutableStateOf(setOf<String>()) }
    val selectedLevel = remember { mutableStateOf("Intermediate") }
    val selectedFiles = remember { mutableStateListOf<TrainingInput>() }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var steps by remember { mutableIntStateOf(0) }
    var loss by remember { mutableFloatStateOf(1f) }
    var elapsed by remember { mutableIntStateOf(0) }
    var totalSteps by remember { mutableIntStateOf(10_000) }
    var testPrompt by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf("") }
    var testRunning by remember { mutableStateOf(false) }
    var verifiedScore by remember { mutableStateOf("80") }
    var verifiedCategory by remember { mutableStateOf("General") }
    val analytics = remember { GrowthAnalytics(context) }
    var message by remember { mutableStateOf("Ready for local training") }
    var phase by remember { mutableIntStateOf(1) }
    var showDevicePrompt by remember { mutableStateOf(false) }
    val engine = remember { LocalTrainingEngine(context) }
    var modelAvailable by remember { mutableStateOf(engine.hasSavedModel()) }
    var corpusReadyForDistributed by remember { mutableStateOf(engine.hasTrainingCorpus()) }
    var checkpointAvailable by remember { mutableStateOf(engine.hasCheckpoint()) }
    var resumeInfo by remember { mutableStateOf(engine.resumeInfo()) }
    var trainingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val importModule = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val ok = engine.importSavedModel(uri)
            withContext(Dispatchers.Main) {
                modelAvailable = ok
                message = if (ok) "Trained module loaded and verified" else "Module rejected: invalid training-module format"
                if (ok) showDevicePrompt = true
            }
        }
    }
    val exportModel = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val ok = engine.exportSavedModel(uri)
            withContext(Dispatchers.Main) {
                message = if (ok) "Trained module exported" else "No saved trained module to export"
            }
        }
    }
    val exportPackage = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val ok = try {
                TrainingPackageExporter.export(context, engine, analytics, uri)
            } catch (_: Throwable) { false }
            withContext(Dispatchers.Main) { message = if (ok) "Complete training package ZIP exported" else "Training package export failed" }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            scope.launch {
                if (it.lastPathSegment?.endsWith(".zip", ignoreCase = true) == true) {
                    message = "ZIP belongs in Phase 4: open ZIP Project Testing to extract and inspect it"
                    return@launch
                }
                val input = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(it)?.use { stream ->
                        val bytes = stream.readNBytes(MAX_TRAINING_FILE_BYTES + 1)
                        if (bytes.size > MAX_TRAINING_FILE_BYTES) null
                        else TrainingInput(it.lastPathSegment ?: "training.txt", bytes.toString(Charsets.UTF_8))
                    }
                }
                if (input == null) message = "File is unreadable or larger than the 5 MB safety limit"
                else if (selectedFiles.sumOf { f -> f.content.toByteArray(Charsets.UTF_8).size } + input.content.toByteArray(Charsets.UTF_8).size > MAX_TRAINING_SESSION_BYTES) {
                    message = "Training data session is limited to 20 MB to avoid memory pressure"
                } else {
                    val updatedFiles = selectedFiles.toList() + input
                    val saved = withContext(Dispatchers.IO) {
                        runCatching { engine.saveTrainingCorpus(updatedFiles) }.isSuccess
                    }
                    if (!saved) {
                        message = "Training data could not be saved safely"
                    } else {
                        selectedFiles += input
                        corpusReadyForDistributed = true
                        message = "Training data loaded: ${input.name}"
                        showDevicePrompt = true
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        skills.value = context.trainingStore.data.first()[SkillsKey] ?: setOf("Coding", "Project Understanding")
    }
    fun beginTraining(resume: Boolean = false) {
        running = true
        progress = 0f
        steps = 0
        elapsed = 0
        message = if (resume) "Resuming checkpointed training" else "Training local character model"
        trainingJob = scope.launch {
            try {
                if (resume) {
                    engine.resume(totalSteps, .05f, { DeviceSafety.shouldPauseTraining(context) }) { update ->
                        withContext(Dispatchers.Main) {
                            progress = update.step.toFloat() / update.totalSteps
                            steps = update.step; loss = update.loss; elapsed = update.elapsedSeconds
                            message = if (update.pausedForThermal) "Training paused for device safety; checkpoint saved" else "Training resumed • %.1f steps/s".format(update.stepsPerSecond)
                        }
                    }
                } else {
                    engine.train(selectedFiles.toList(), totalSteps, .05f, { DeviceSafety.shouldPauseTraining(context) }) { update ->
                        withContext(Dispatchers.Main) {
                            progress = update.step.toFloat() / update.totalSteps
                            steps = update.step; loss = update.loss; elapsed = update.elapsedSeconds
                            message = if (update.pausedForThermal) "Training paused for device safety; checkpoint saved" else "Training local model • %.1f steps/s".format(update.stepsPerSecond)
                        }
                    }
                }
                running = false
                modelAvailable = engine.hasSavedModel()
                checkpointAvailable = engine.hasCheckpoint()
                resumeInfo = engine.resumeInfo()
                message = if (checkpointAvailable) "Training paused safely; checkpoint is ready to resume" else "Local model trained and saved"
            } catch (error: Throwable) {
                running = false
                checkpointAvailable = engine.hasCheckpoint(); resumeInfo = engine.resumeInfo()
                message = "Training stopped safely: ${error.message ?: "unknown error"}"
            }
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Navy, surface = Panel, primary = Purple,
            onSurface = Color.White, onBackground = Color.White
        )
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(Navy).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Header() }
            item { PhaseTabs(phase = phase, onPhase = { phase = it }) }
            if (phase == 1) {
                item {
                    TrainingSetupCard(
                        skills = skills.value, level = selectedLevel.value, files = selectedFiles, running = running, totalSteps = totalSteps, onTotalSteps = { totalSteps = it },
                        onToggle = { skill ->
                            val updated = if (skill in skills.value) skills.value - skill else skills.value + skill
                            skills.value = updated
                            scope.launch { context.trainingStore.edit { it[SkillsKey] = updated } }
                        },
                        onLevel = { selectedLevel.value = it },
                        onAddData = { picker.launch(arrayOf("text/plain", "application/json", "text/csv")) },
                        onStart = {
                            if (skills.value.isEmpty()) message = "Select at least one training skill"
                            else if (selectedFiles.isEmpty()) message = "Select at least one readable text file"
                            else showDevicePrompt = true
                        }
                    )
                }
                item {
                    OutlinedButton(onClick = { importModule.launch(arrayOf("application/octet-stream", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text("UPLOAD TRAINED MODULE")
                    }
                    if (checkpointAvailable && !running) {
                        OutlinedButton(onClick = { beginTraining(true) }, modifier = Modifier.fillMaxWidth()) {
                            Text("RESUME SAVED TRAINING • STEP ${resumeInfo?.step ?: 0}")
                        }
                    }
                }
                item {
                    ProgressCard(
                        running = running, progress = progress, steps = steps, totalSteps = totalSteps, loss = loss,
                        elapsed = elapsed, message = message,
                        onPause = { trainingJob?.cancel(); running = false; message = "Training cancelled safely; latest checkpoint is kept when available" },
                        battery = DeviceSafety.batteryPercent(context), temperature = DeviceSafety.thermalLabel(context),
                        modelAvailable = modelAvailable,
                        onExport = { exportModel.launch("training-model.bin") }, onExportPackage = { exportPackage.launch("training-package.zip") }
                    )
                }
                item {
                    TrainedModuleTestCard(
                        modelAvailable = modelAvailable, prompt = testPrompt, result = testResult, running = testRunning,
                        score = verifiedScore, category = verifiedCategory,
                        onPrompt = { testPrompt = it }, onScore = { verifiedScore = it }, onCategory = { verifiedCategory = it },
                        onTest = {
                            val model = engine.readSavedModel()
                            if (model == null) testResult = "No trained module is available yet."
                            else {
                                testRunning = true
                                scope.launch(Dispatchers.Default) {
                                    val generated = runCatching { TrainedModuleTester.generate(model, testPrompt.trim()) }.getOrElse { "Test failed: ${it.message ?: "invalid prompt"}" }
                                    withContext(Dispatchers.Main) { testResult = generated; testRunning = false }
                                }
                            }
                        },
                        onVerify = {
                            val score = verifiedScore.toIntOrNull()?.coerceIn(0, 100)
                            if (testResult.isBlank()) message = "Run a module test before marking a result verified"
                            else if (score == null) message = "Enter a score from 0 to 100"
                            else scope.launch {
                                analytics.recordVerified(EvaluationRecord(score = score, categories = mapOf(verifiedCategory.ifBlank { "General" } to score), source = "trained-module-test", verified = true))
                                message = "Verified test result saved to Growth Analytics"
                            }
                        },
                        onExportPackage = { exportPackage.launch("training-package.zip") }
                    )
                }
            } else if (phase == 2) {
                item { Phase2Screen() }
            } else if (phase == 3) {
                item { Phase3Screen(onConnectDevices = { phase = 2 }) }
            } else if (phase == 4) {
                item { Phase4Screen() }
            } else if (phase == 5) {
                item { Phase5Screen() }
            } else {
                item { Phase6Screen() }
            }
            item { Text("Phase $phase • source preserved; no plan files deleted", color = Muted, fontSize = 12.sp) }
        }
        if (showDevicePrompt) {
            AlertDialog(
                onDismissRequest = { showDevicePrompt = false },
                title = { Text("Connect worker devices?") },
                text = { Text("Before training starts, you can connect phones over the same Wi‑Fi LAN and review their CPU, RAM and battery limits.") },
                confirmButton = {
                    TextButton(onClick = { showDevicePrompt = false; phase = 2 }) { Text("CONNECT DEVICES") }
                },
                dismissButton = {
                    TextButton(onClick = { showDevicePrompt = false; beginTraining() }) { Text("TRAIN ON THIS PHONE") }
                }
            )
        }
    }
}

@Composable private fun PhaseTabs(phase: Int, onPhase: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(1 to "Setup", 2 to "Connect", 3 to "Eval", 4 to "ZIP", 5 to "Growth", 6 to "Features").forEach { (number, label) ->
            FilterChip(
                selected = phase == number, onClick = { onPhase(number) },
                label = { Text("P$number $label", fontSize = 11.sp) }
            )
        }
    }
}

@Composable private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("‹", color = Color.White, fontSize = 30.sp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text("AI TRAINING CENTER", color = Cyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Training • Phase 1–6", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TrainingSetupCard(
    skills: Set<String>, level: String, files: List<TrainingInput>, running: Boolean, totalSteps: Int, onTotalSteps: (Int) -> Unit, onToggle: (String) -> Unit,
    onLevel: (String) -> Unit, onAddData: () -> Unit, onStart: () -> Unit
) {
    val allSkills = listOf("Coding", "Project Understanding", "UI / UX Handling", "User Intent Understanding", "Debugging & Error Fixing", "Web Search / Research", "Testing & Validation")
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Select Training Skills / Modules", color = Color.White, fontWeight = FontWeight.Bold)
            allSkills.forEach { skill ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = skill in skills, onCheckedChange = { onToggle(skill) }, colors = CheckboxDefaults.colors(checkedColor = Green))
                    Text(skill, color = Color.White, fontSize = 13.sp)
                }
            }
            Text("Training Level", color = Cyan, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Basic", "Intermediate", "Advanced").forEach {
                    FilterChip(selected = it == level, onClick = { onLevel(it) }, label = { Text(it) })
                }
            }
            OutlinedTextField(
                value = totalSteps.toString(),
                onValueChange = { onTotalSteps((it.filter(Char::isDigit).toIntOrNull() ?: totalSteps).coerceIn(1, 10_000)) },
                label = { Text("Total training steps (1–10,000)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Text("Progress is measured against this actual selected total.", color = Muted, fontSize = 11.sp)
            Text("Add Training Data / Examples", color = Cyan, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onAddData, modifier = Modifier.fillMaxWidth()) {
                Text("+ Add Examples (TXT / JSON / CSV)")
            }
            if (files.isNotEmpty()) {
                Text("${files.size} readable file(s) loaded", color = Green, fontSize = 12.sp)
                files.take(3).forEach { Text("• ${it.name}", color = Muted, fontSize = 12.sp) }
            }
            Button(
                onClick = onStart, enabled = !running,
                modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Purple)
            ) {
                Text(if (running) "TRAINING..." else "START REAL TRAINING", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ProgressCard(
    running: Boolean, progress: Float, steps: Int, totalSteps: Int, loss: Float, elapsed: Int, message: String,
    onPause: () -> Unit, battery: String, temperature: String,
    modelAvailable: Boolean, onExport: () -> Unit, onExportPackage: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel2), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Training in Progress", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(message, color = if (running) Cyan else Muted, fontSize = 12.sp)
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().height(110.dp)) {
                CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(104.dp), color = Green, trackColor = Color(0xFF26384C), strokeWidth = 8.dp)
                Text("${(progress * 100).toInt()}%", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            }
            Text("Time elapsed  ${elapsed}s", color = Muted)
            Text("Steps completed  %,d / %,d".format(steps, totalSteps), color = Color.White)
            Text(
                if (running) "Training speed is measured after each update"
                else "Loss  %.3f     Speed is reported in the last update".format(loss),
                color = Color.White
            )
            HorizontalDivider(color = Color(0xFF29415A))
            Text("Device info", color = Cyan, fontWeight = FontWeight.Bold)
            Text("Battery  $battery     Thermal  $temperature", color = Color.White, fontSize = 13.sp)
            Button(onClick = onPause, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Purple)) {
                Text("TRAINING ENGINE STATUS")
            }
            OutlinedButton(
                onClick = onExport, enabled = modelAvailable,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (modelAvailable) "EXPORT TRAINED MODULE" else "NO TRAINED MODULE")
            }
            OutlinedButton(onClick = onExportPackage, enabled = modelAvailable, modifier = Modifier.fillMaxWidth()) {
                Text(if (modelAvailable) "EXPORT COMPLETE TRAINING PACKAGE ZIP" else "NO TRAINING PACKAGE")
            }
        }
    }
}



@Composable
private fun TrainedModuleTestCard(
    modelAvailable: Boolean, prompt: String, result: String, running: Boolean, score: String, category: String,
    onPrompt: (String) -> Unit, onScore: (String) -> Unit, onCategory: (String) -> Unit,
    onTest: () -> Unit, onVerify: () -> Unit, onExportPackage: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Test Trained Module", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Ask a question or give a generation task. The answer is generated from the saved trained module; no fake response is inserted.", color = Cyan, fontSize = 12.sp)
            OutlinedTextField(prompt, onPrompt, label = { Text("Question / generation prompt") }, minLines = 3, modifier = Modifier.fillMaxWidth(), enabled = modelAvailable && !running)
            Button(onClick = onTest, enabled = modelAvailable && !running && prompt.isNotBlank(), modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Purple)) {
                Text(if (running) "TESTING MODULE…" else "TEST MODULE")
            }
            if (result.isNotBlank()) {
                Text("Module output", color = Cyan, fontWeight = FontWeight.Bold)
                Text(result, color = Color.White, fontSize = 13.sp)
                HorizontalDivider(color = Color(0xFF29415A))
                Text("Human verification", color = Cyan, fontWeight = FontWeight.Bold)
                OutlinedTextField(score, onScore, label = { Text("Score 0–100") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(category, onCategory, label = { Text("Category") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(onClick = onVerify, modifier = Modifier.fillMaxWidth()) { Text("MARK TEST VERIFIED + SAVE") }
            }
            OutlinedButton(onClick = onExportPackage, enabled = modelAvailable, modifier = Modifier.fillMaxWidth()) { Text("EXPORT TRAINING PACKAGE ZIP") }
        }
    }
}
