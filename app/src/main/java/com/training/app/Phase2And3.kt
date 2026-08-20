package com.training.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val PhasePanel = Color(0xFF0D1B2B)
private val PhasePanel2 = Color(0xFF11243A)
private val PhasePurple = Color(0xFFA855F7)
private val PhaseCyan = Color(0xFF4DD9FF)
private val PhaseGreen = Color(0xFF39D98A)
private val PhaseMuted = Color(0xFF8DA2B8)

@Composable
fun Phase2Screen() {
    val context = LocalContext.current
    val lan = remember { DistributedLanCoordinator(context) }
    val engine = remember { LocalTrainingEngine(context) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { lan.stop() } }

    var role by remember { mutableStateOf("Master") }
    var code by remember { mutableStateOf("") }
    var workerHost by remember { mutableStateOf("") }
    var workerPort by remember { mutableIntStateOf(8765) }
    var workerCode by remember { mutableStateOf("") }
    var maxDevicesText by remember { mutableStateOf("1") }
    var status by remember { mutableStateOf("Start the master or connect this phone as a worker") }
    var qrValue by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var training by remember { mutableStateOf(false) }
    var trainingProgress by remember { mutableStateOf<DistributedProgress?>(null) }
    var trainingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val connections = remember { mutableStateListOf<WorkerSession>() }
    val loadLimits = remember { mutableStateMapOf<String, String>() }
    val steps = listOf("Scan / Pair", "Authenticate", "Exchange Keys", "Capabilities", "Connected")
    var connectionStep by remember { mutableIntStateOf(0) }
    var totalStepsText by remember { mutableStateOf("10000") }
    var learningRateText by remember { mutableStateOf("0.05") }
    var modelReady by remember { mutableStateOf(false) }
    var corpusReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val ready = withContext(Dispatchers.IO) { engine.readSavedModel() != null to engine.hasTrainingCorpus() }
        modelReady = ready.first
        corpusReady = ready.second
    }

    fun addConnection(session: WorkerSession) {
        if (connections.none { it.peer == session.peer }) {
            connections += session
            loadLimits[session.peer] = "100"
        }
        connectionStep = 5
        status = "Worker connected and ready for real training jobs (${connections.size})"
    }

    Card(colors = CardDefaults.cardColors(containerColor = PhasePanel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Multi-Phone Connect + Real Work Distribution", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Same-Wi-Fi LAN • authenticated persistent socket • real training jobs", color = PhaseCyan, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Master", "Worker").forEach { item ->
                    FilterChip(selected = role == item, onClick = { role = item }, label = { Text("Phone $item") })
                }
            }

            if (role == "Master") {
                Text("Master LAN address", color = PhaseMuted)
                Text("${lan.localIp()}:8765", color = Color.White, fontSize = 17.sp)
                OutlinedTextField(code, { code = it }, label = { Text("Pairing code (minimum 8 characters)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(maxDevicesText, { maxDevicesText = it.filter(Char::isDigit) }, label = { Text("Worker phones (1–8)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(
                    onClick = {
                        val count = maxDevicesText.toIntOrNull()
                        if (code.trim().length < 8) status = "Pairing code must contain at least 8 characters"
                        else if (count !in 1..8) status = "Worker count must be 1–8"
                        else {
                            connectionStep = 1
                            qrValue = makeLanQrPayload(lan.localIp(), 8765, code.trim(), count!!)
                            status = "Listening for workers on ${lan.localIp()}:8765"
                            lan.startMaster(code.trim(), count) { result ->
                                result.onSuccess(::addConnection).onFailure { status = "LAN master error: ${it.message}" }
                            }
                        }
                    },
                    enabled = !training && connections.size < (maxDevicesText.toIntOrNull() ?: 0),
                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = PhasePurple)
                ) { Text(if (connections.isEmpty()) "GENERATE QR + START MASTER" else "MASTER LISTENING") }
                qrValue?.let {
                    Text("Worker phones scan this QR:", color = PhaseMuted)
                    LanQrCode(it, Modifier.align(Alignment.CenterHorizontally))
                }
            } else {
                if (scanning) {
                    LanQrScanner(
                        onDecoded = { raw ->
                            runCatching { parseLanQrPayload(raw) }
                                .onSuccess { payload ->
                                    workerHost = payload.host; workerPort = payload.port; workerCode = payload.code
                                    maxDevicesText = payload.maxDevices.toString(); scanning = false
                                    status = "QR accepted • ${payload.host}:${payload.port}"
                                }
                                .onFailure { status = "QR rejected: ${it.message}" }
                        },
                        onClose = { scanning = false }
                    )
                }
                OutlinedButton(onClick = { scanning = true }, modifier = Modifier.fillMaxWidth()) { Text("SCAN MASTER QR") }
                OutlinedTextField(workerHost, { workerHost = it }, label = { Text("Master LAN IP") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(workerCode, { workerCode = it }, label = { Text("Master pairing code") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(
                    onClick = {
                        connectionStep = 1; status = "Authenticating and opening persistent worker channel…"
                        lan.connectWorker(workerHost, workerPort, workerCode) { result ->
                            result.onSuccess(::addConnection).onFailure { status = "LAN connection failed: ${it.message}" }
                        }
                    },
                    enabled = !training && workerHost.isNotBlank() && workerCode.length >= 8,
                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = PhasePurple)
                ) { Text("CONNECT WORKER") }
                Text("After connection, this phone waits for real training jobs from the master. It does not need a separate fake workload animation.", color = PhaseMuted, fontSize = 12.sp)
            }

            Text(status, color = if (connectionStep == 5) PhaseGreen else PhaseMuted)
            steps.forEachIndexed { index, step ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (index < connectionStep) "✓" else "○", color = if (index < connectionStep) PhaseGreen else PhaseMuted, fontSize = 20.sp)
                    Spacer(Modifier.width(10.dp)); Text(step, color = Color.White)
                }
            }

            HorizontalDivider(color = Color(0xFF29415A))
            Text("Real workload allocation", color = PhaseCyan, fontWeight = FontWeight.Bold)
            if (connections.isEmpty()) {
                Text("No remote workload is assigned until a worker completes the real capability handshake.", color = PhaseMuted, fontSize = 12.sp)
            } else {
                val localCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                val remoteWeight = connections.sumOf { it.capability.cpuCores.toDouble() * ((loadLimits[it.peer]?.toIntOrNull() ?: 0) / 100.0) }
                val totalWeight = localCores + remoteWeight
                Text("Master • $localCores CPU cores • ${((localCores / totalWeight) * 100).toInt()}% of scheduled training work", color = Color.White, fontSize = 13.sp)
                connections.forEach { session ->
                    val cap = session.capability
                    val limit = (loadLimits[session.peer]?.toIntOrNull() ?: 0).coerceIn(0, 100)
                    val weight = cap.cpuCores * limit / 100.0
                    val share = if (totalWeight == 0.0) 0 else (weight / totalWeight * 100).toInt()
                    Text("${cap.name} • ${cap.cpuCores} CPU • ${cap.ramMb} MB RAM • ${cap.battery}% battery • scheduled share ≈ $share%", color = Color.White, fontSize = 13.sp)
                    OutlinedTextField(
                        value = loadLimits[session.peer].orEmpty(),
                        onValueChange = { loadLimits[session.peer] = it.filter(Char::isDigit).take(3) },
                        label = { Text("Worker scheduling cap (%)") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                }
                Text("The setting controls how many training steps this worker receives. Android cannot guarantee an exact OS CPU-utilization percentage; this build therefore enforces workload by actual step allocation, not a fake CPU meter.", color = PhaseMuted, fontSize = 12.sp)
            }

            HorizontalDivider(color = Color(0xFF29415A))
            Text("Distributed training", color = PhaseCyan, fontWeight = FontWeight.Bold)
            Text("Model: ${if (modelReady) "ready" else "not loaded"} • Training data: ${if (corpusReady) "ready" else "not selected"}", color = Color.White, fontSize = 13.sp)
            OutlinedTextField(totalStepsText, { totalStepsText = it.filter(Char::isDigit).take(5) }, label = { Text("Total training steps (1–10,000)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(learningRateText, { learningRateText = it.filter { c -> c.isDigit() || c == '.' }.take(8) }, label = { Text("Learning rate") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(
                onClick = {
                    val total = totalStepsText.toIntOrNull()?.coerceIn(1, 10_000) ?: 10_000
                    val lr = learningRateText.toFloatOrNull()?.takeIf { it > 0f } ?: 0.05f
                    if (connections.isEmpty()) status = "Connect at least one worker phone before distributed training"
                    else {
                        training = true; status = "Starting real distributed training…"
                        trainingJob = scope.launch(Dispatchers.Default) {
                            try {
                                val corpus = engine.trainingCorpus() ?: throw IllegalStateException("Select training data in Phase 1 first")
                                val initial = engine.readSavedModel() ?: engine.createInitialModelFromCorpus(corpus)
                                val finalModel = DistributedTrainingCoordinator(context, lan).train(initial, corpus, total, lr, connections.toList(), loadLimits.mapValues { it.value.toIntOrNull() ?: 0 }, { DeviceSafety.shouldPauseTraining(context) }, { progress ->
                                    scope.launch(kotlinx.coroutines.Dispatchers.Main) { trainingProgress = progress }
                                }, { checkpoint ->
                                    engine.writeSavedModel(checkpoint)
                                })
                                engine.writeSavedModel(finalModel)
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    modelReady = true; training = false; status = "Distributed training complete • ${finalModel.completedSteps} scheduled steps saved"
                                }
                            } catch (error: Throwable) {
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    training = false
                                    status = if (error is ThermalPauseException) "Training paused safely for thermal/battery protection" else "Distributed training stopped safely: ${error.message ?: "unknown error"}"
                                }
                            }
                        }
                    }
                },
                enabled = !training && role == "Master" && connections.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = PhasePurple)
            ) { Text(if (training) "DISTRIBUTED TRAINING RUNNING…" else "START REAL DISTRIBUTED TRAINING") }
            if (training) {
                val p = trainingProgress
                LinearProgressIndicator(progress = { (p?.completedSteps ?: 0).toFloat() / (p?.totalSteps ?: 1) }, modifier = Modifier.fillMaxWidth(), color = PhaseGreen)
                Text(p?.message ?: "Preparing workers…", color = Color.White, fontSize = 12.sp)
            }
            OutlinedButton(onClick = { trainingJob?.cancel(); training = false; status = "Training cancellation requested; workers keep their channel safe" }, enabled = training, modifier = Modifier.fillMaxWidth()) { Text("STOP AFTER CURRENT JOB") }
            Text("A worker that loses connection causes the current master job to fail rather than silently pretending it completed. Restarting the distributed run uses the saved master model; completed local-only checkpointing remains available in Phase 1.", color = PhaseMuted, fontSize = 12.sp)
        }
    }
}

@Composable
fun Phase3Screen(onConnectDevices: () -> Unit = {}) {
    val context = LocalContext.current
    val keyStore = remember { ApiKeyStore(context) }
    val ai = remember { AiClient(keyStore) }
    var provider by remember { mutableStateOf("Groq") }
    var endpoint by remember { mutableStateOf("https://api.groq.com/openai/v1") }
    var model by remember { mutableStateOf("llama-3.3-70b-versatile") }
    var apiKey by remember { mutableStateOf("") }
    var instruction by remember { mutableStateOf("") }
    var stack by remember { mutableStateOf("Kotlin") }
    var difficulty by remember { mutableStateOf("Intermediate") }
    var running by remember { mutableStateOf(false) }
    var completed by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf("Configure a real AI provider, then create a task.") }
    var showDevicePrompt by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pipeline = listOf("Intent", "Plan", "Files", "Code", "UI", "Build", "Test", "Fix", "Final")
    fun runAiEvaluation() {
        running = true
        completed = 0
        result = "Calling $provider…"
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { ai.complete(provider.trim(), endpoint.trim(), model.trim(),
                    "You are evaluating an Android coding task. Return a concise plan and explicit verification checklist.\nTask: ${instruction.trim()}\nStack: $stack\nDifficulty: $difficulty")
                }
                running = false; completed = 1
                result = "[${response.provider}] ${response.text}"
            } catch (error: Throwable) {
                running = false
                result = "Evaluation stopped: ${error.message ?: "request failed"}"
            }
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = PhasePanel2), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text("Evaluation Lab", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Real OpenAI-compatible AI endpoint • one verified request; key encrypted in Android Keystore", color = PhaseCyan, fontSize = 12.sp)
            OutlinedTextField(provider, { provider = it }, label = { Text("Provider name (Groq or your app)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(endpoint, { endpoint = it }, label = { Text("API base URL") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(model, { model = it }, label = { Text("Model name") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API key (stored encrypted; never displayed)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = {
                runCatching { keyStore.put(provider.trim(), apiKey) }
                    .onSuccess { apiKey = ""; result = "Saved encrypted key for $provider" }
                    .onFailure { result = "Key was not saved: ${it.message}" }
            }, enabled = provider.isNotBlank() && apiKey.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("SAVE AI PROVIDER KEY")
            }
            OutlinedTextField(
                value = instruction, onValueChange = { instruction = it },
                label = { Text("Task instruction") }, minLines = 3, modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = stack == "Kotlin", onClick = { stack = "Kotlin" }, label = { Text("Kotlin") })
                FilterChip(selected = stack == "Compose", onClick = { stack = "Compose" }, label = { Text("Compose") })
                FilterChip(selected = difficulty == "Advanced", onClick = { difficulty = "Advanced" }, label = { Text("Advanced") })
            }
            Button(
                onClick = {
                    if (instruction.isBlank()) result = "Instruction is required"
                    else showDevicePrompt = true
                },
                enabled = !running, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = PhasePurple)
            ) { Text(if (running) "RUNNING..." else "RUN EVALUATION") }
            Text("Request lifecycle (only Intent is executed here; remaining stages require a project toolchain)", color = PhaseMuted, fontSize = 12.sp)
            pipeline.forEachIndexed { index, stage ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (index < completed) "✓" else "○", color = if (index < completed) PhaseGreen else PhaseMuted)
                    Spacer(Modifier.width(8.dp))
                    Text("${index + 1}. $stage", color = Color.White, fontSize = 13.sp)
                }
            }
            Text(result, color = PhaseMuted, fontSize = 12.sp)
        }
    }
    if (showDevicePrompt) {
        AlertDialog(
            onDismissRequest = { showDevicePrompt = false },
            title = { Text("Connect worker devices?") },
            text = { Text("Before testing, you can connect phones over the same Wi‑Fi LAN and review their live CPU, RAM and battery limits.") },
            confirmButton = {
                TextButton(onClick = { showDevicePrompt = false; onConnectDevices() }) { Text("CONNECT IN PHASE 2") }
            },
            dismissButton = {
                TextButton(onClick = { showDevicePrompt = false; runAiEvaluation() }) { Text("TEST ON THIS PHONE") }
            }
        )
    }
}

@Composable
fun Phase4Screen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fileName by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Upload a ZIP project to begin a real analysis.") }
    var analysis by remember { mutableStateOf<ZipAnalysis?>(null) }
    var selectedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            selectedUri = it
            fileName = it.lastPathSegment ?: "Selected ZIP"
            analysis = null
            status = "ZIP selected; press Extract and Analyze."
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = PhasePanel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("ZIP Project Testing", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Real ZIP extraction, path safety checks and project manifest inspection", color = PhaseCyan, fontSize = 12.sp)
            OutlinedButton(onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed")) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (fileName.isBlank()) "SELECT PROJECT ZIP" else "ZIP: $fileName")
            }
            Button(
                onClick = {
                    selectedUri?.let { uri ->
                        status = "Extracting ZIP safely…"
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { ZipProjectAnalyzer.extractSafely(context, uri) } }
                                .onSuccess {
                                    analysis = it
                                    status = "ZIP extracted and analyzed locally"
                                }
                                .onFailure { status = "ZIP analysis failed: ${it.message ?: "unknown error"}" }
                        }
                    }
                },
                enabled = fileName.isNotBlank(),
                modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = PhasePurple)
            ) { Text("EXTRACT AND ANALYZE ZIP") }
            Text(status, color = PhaseMuted, fontSize = 12.sp)
            analysis?.let {
                Text("${it.fileCount} files • ${it.totalBytes} bytes extracted", color = PhaseGreen, fontSize = 13.sp)
                it.names.take(8).forEach { name -> Text("• $name", color = Color.White, fontSize = 12.sp) }
            }
            listOf("1. Preserve original ZIP", "2. Extract and inspect files", "3. Detect build/test issues", "4. Prepare copy for fixes", "5. Verify changes before export").forEach {
                Text(it, color = Color.White, fontSize = 13.sp)
            }
            Text("The phone can inspect and extract safely. It does not claim to run an Android compiler or rewrite arbitrary projects.", color = PhaseMuted, fontSize = 12.sp)
        }
    }
}

@Composable
fun Phase5Screen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val analytics = remember { GrowthAnalytics(context) }
    var records by remember { mutableStateOf(emptyList<EvaluationRecord>()) }
    var loop by remember { mutableStateOf(ImprovementLoop()) }
    var queued by remember { mutableStateOf(emptySet<String>()) }
    var status by remember { mutableStateOf("Loading persisted evaluation history…") }

    suspend fun refresh() {
        records = analytics.records()
        loop = analytics.loop()
        queued = analytics.queuedTraining()
        status = if (records.isEmpty()) "No verified evaluation records yet." else "${records.size} verified evaluation record(s) loaded"
    }

    LaunchedEffect(Unit) { refresh() }
    val weak = findWeakAreas(records)
    val average = records.takeIf { it.isNotEmpty() }?.map { it.score }?.average()?.toInt() ?: 0

    Card(colors = CardDefaults.cardColors(containerColor = PhasePanel2), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Intelligence Growth Tracker", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Persisted verified evaluation history • no demo/sample numbers", color = PhaseCyan, fontSize = 12.sp)

            if (records.isEmpty()) {
                Text("No verified evaluation has been recorded. Analytics will stay empty instead of inventing a score.", color = PhaseMuted)
            } else {
                Text("Average verified score: $average%", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                records.takeLast(10).forEachIndexed { index, record ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Evaluation ${records.size - records.takeLast(10).size + index + 1}", color = PhaseMuted, modifier = Modifier.weight(1f))
                            Text("${record.score}%", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        LinearProgressIndicator(progress = { record.score / 100f }, modifier = Modifier.fillMaxWidth(), color = PhaseGreen)
                        Text("${record.source} • ${record.categories.keys.joinToString()}", color = PhaseMuted, fontSize = 11.sp)
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF29415A))
            Text("Weak Areas & Suggested Training", color = PhaseCyan, fontWeight = FontWeight.Bold)
            if (weak.isEmpty()) {
                Text(if (records.isEmpty()) "No weak areas can be computed without verified category scores." else "No category is below the 70% weak-area threshold.", color = PhaseMuted)
            } else {
                weak.forEach { (category, score) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(category, color = Color.White, modifier = Modifier.weight(1f))
                        Text("$score%", color = Color.White)
                    }
                }
                Button(onClick = {
                    scope.launch {
                        analytics.queueTraining(weak.keys)
                        analytics.transition(LoopState.QUEUING_DATA, loop.cycle + 1)
                        refresh()
                        status = "Weak areas added to the real training queue"
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("ADD WEAK AREAS TO TRAINING QUEUE") }
            }

            Text("Training queue: ${if (queued.isEmpty()) "empty" else queued.joinToString()}", color = PhaseMuted, fontSize = 12.sp)
            HorizontalDivider(color = Color(0xFF29415A))
            Text("Continuous Improvement Loop", color = PhaseCyan, fontWeight = FontWeight.Bold)
            Text("Train → Evaluate → Find Weak Areas → Add New Data → Retrain", color = Color.White, fontSize = 13.sp)
            Text("State: ${loop.state} • cycle ${loop.cycle}", color = PhaseGreen, fontSize = 13.sp)
            loop.lastError?.let { Text("Last error: $it", color = Color(0xFFFF8A8A), fontSize = 12.sp) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = {
                    scope.launch {
                        analytics.transition(LoopState.TRAINING, loop.cycle + 1)
                        refresh()
                        status = "Loop entered TRAINING; start Phase 1 training to perform the real work"
                    }
                }, modifier = Modifier.weight(1f)) { Text("START LOOP") }
                OutlinedButton(onClick = {
                    scope.launch {
                        analytics.transition(LoopState.PAUSED, loop.cycle)
                        refresh()
                        status = "Improvement loop paused safely"
                    }
                }, modifier = Modifier.weight(1f)) { Text("PAUSE LOOP") }
            }
            OutlinedButton(onClick = {
                scope.launch {
                    analytics.clearTrainingQueue()
                    analytics.transition(LoopState.IDLE, loop.cycle)
                    refresh()
                    status = "Training queue cleared"
                }
            }, enabled = queued.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("CLEAR TRAINING QUEUE") }
            OutlinedButton(onClick = {
                scope.launch {
                    analytics.clear()
                    refresh()
                    status = "Verified analytics history cleared by user"
                }
            }, enabled = records.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("CLEAR SAVED HISTORY") }
            Text(status, color = PhaseMuted, fontSize = 12.sp)
        }
    }
}

@Composable
fun Phase6Screen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val analytics = remember { GrowthAnalytics(context) }
    var loop by remember { mutableStateOf(ImprovementLoop()) }
    var queued by remember { mutableStateOf(emptySet<String>()) }
    var status by remember { mutableStateOf("Loading final feature status…") }

    LaunchedEffect(Unit) {
        loop = analytics.loop()
        queued = analytics.queuedTraining()
        status = "Final source-level feature summary loaded"
    }

    val features = listOf(
        "Offline/local training engine with checkpoint recovery" to "Ready",
        "Same-Wi-Fi authenticated multi-phone training" to "Ready",
        "Capability-based workload scheduling" to "Ready",
        "Thermal and battery safety guard" to "Ready",
        "Real ZIP extraction safety checks" to "Ready",
        "Persisted verified evaluation analytics" to "Ready",
        "Weak-area training queue" to if (queued.isEmpty()) "Ready • queue empty" else "Ready • ${queued.size} queued",
        "Continuous improvement state machine" to "Ready • ${loop.state}",
        "Physical-device crash/LAN validation" to "Deferred for real phones"
    )

    Card(colors = CardDefaults.cardColors(containerColor = PhasePanel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Key Features & Final Polish", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Phase 5 → Phase 6 connected feature summary", color = PhaseCyan, fontSize = 12.sp)
            features.forEach { (name, state) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("•", color = if (state.startsWith("Ready")) PhaseGreen else PhaseMuted, fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(name, color = Color.White, modifier = Modifier.weight(1f), fontSize = 13.sp)
                    Text(state, color = if (state.startsWith("Ready")) PhaseGreen else PhaseMuted, fontSize = 11.sp)
                }
            }
            HorizontalDivider(color = Color(0xFF29415A))
            Text("Safety rule", color = PhaseCyan, fontWeight = FontWeight.Bold)
            Text("No fake evaluation score, fake training completion, or fake device workload is inserted into the product. Missing real verification remains visibly marked.", color = PhaseMuted, fontSize = 12.sp)
            Text("Continuous loop state: ${loop.state} • cycle ${loop.cycle}", color = Color.White, fontSize = 13.sp)
            Text(status, color = PhaseMuted, fontSize = 12.sp)
        }
    }
}
