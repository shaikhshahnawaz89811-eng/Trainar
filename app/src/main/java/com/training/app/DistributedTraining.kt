package com.training.app

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.ln

/**
 * Real same-Wi-Fi distributed training transport.
 *
 * Each round starts from the same master model. The master allocates an exact
 * number of training steps to every participant according to CPU/RAM/battery
 * capability and the user's per-device cap. Workers perform those steps locally
 * and return the updated weights. The master then computes a weighted average.
 * This is synchronous federated-style training, not a fake CPU percentage meter.
 */
data class WorkerCapability(
    val id: String,
    val name: String,
    val cpuCores: Int,
    val ramMb: Long,
    val battery: Int
)

data class WorkerSession(
    val capability: WorkerCapability,
    val peer: String,
    internal val socket: Socket,
    internal val input: DataInputStream,
    internal val output: DataOutputStream
)

data class DistributedModel(
    val vocabulary: CharArray,
    val weights: Array<FloatArray>,
    val completedSteps: Int
)

data class DistributedProgress(
    val completedSteps: Int,
    val totalSteps: Int,
    val round: Int,
    val localSteps: Int,
    val remoteSteps: Int,
    val loss: Float,
    val message: String
)

data class DistributedResult(val model: DistributedModel, val loss: Float, val steps: Int)

data class WorkerJob(
    val jobId: String,
    val vocabulary: CharArray,
    val weights: Array<FloatArray>,
    val corpus: String,
    val offset: Int,
    val steps: Int,
    val learningRate: Float
)

data class WorkerResult(
    val jobId: String,
    val vocabulary: CharArray,
    val weights: Array<FloatArray>,
    val loss: Float,
    val steps: Int
)

object DistributedCodec {
    private const val VERSION = 1
    private const val MAX_FRAME = 2_000_000

    fun frame(type: String, body: JSONObject = JSONObject()): ByteArray {
        body.put("v", VERSION).put("type", type)
        return body.toString().toByteArray(Charsets.UTF_8)
    }

    fun write(out: DataOutputStream, type: String, body: JSONObject = JSONObject()) {
        val bytes = frame(type, body)
        require(bytes.size <= MAX_FRAME) { "Protocol frame is too large" }
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    fun read(input: DataInputStream): JSONObject {
        val length = input.readInt()
        require(length in 2..MAX_FRAME) { "Invalid protocol frame length: $length" }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        require(json.optInt("v") == VERSION) { "Unsupported LAN protocol version" }
        return json
    }

    fun encodeModel(model: DistributedModel): String {
        val out = java.io.ByteArrayOutputStream()
        DataOutputStream(out).use { stream ->
            stream.writeInt(model.vocabulary.size)
            model.vocabulary.forEach { stream.writeChar(it.code) }
            model.weights.forEach { row -> row.forEach(stream::writeFloat) }
            stream.writeInt(model.completedSteps)
        }
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    fun decodeModel(encoded: String): DistributedModel {
        val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        DataInputStream(bytes.inputStream()).use { input ->
            val size = input.readInt()
            require(size in 2..128)
            val vocab = CharArray(size) { input.readChar() }
            val weights = Array(size) { FloatArray(size) { input.readFloat() } }
            val steps = input.readInt()
            require(steps >= 0)
            return DistributedModel(vocab, weights, steps)
        }
    }

    fun checksum(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

object DistributedMath {
    fun trainChunk(job: WorkerJob, shouldPause: () -> Boolean): WorkerResult {
        require(job.corpus.length >= 3)
        val chars = job.vocabulary
        val index = chars.withIndex().associate { it.value to it.index }
        require(job.weights.size == chars.size && job.weights.all { it.size == chars.size })
        val weights = Array(chars.size) { i -> job.weights[i].clone() }
        var lossTotal = 0.0
        val start = job.offset.coerceAtLeast(0)
        for (local in 0 until job.steps) {
            if (shouldPause()) throw ThermalPauseException("Worker paused before completing job")
            val globalStep = start + local
            val position = globalStep % (job.corpus.length - 1)
            val input = index[job.corpus[position]] ?: 0
            val target = index[job.corpus[position + 1]] ?: 0
            val logits = weights[input]
            var normalizer = 0.0
            for (value in logits) normalizer += exp(value.toDouble())
            var loss = 0.0
            for (classIndex in logits.indices) {
                val probability = exp(logits[classIndex].toDouble()) / normalizer.coerceAtLeast(1e-12)
                val gradient = probability.toFloat() - if (classIndex == target) 1f else 0f
                weights[input][classIndex] -= job.learningRate * gradient
                if (classIndex == target) loss = -ln(probability.coerceAtLeast(1e-12))
            }
            lossTotal += loss
        }
        return WorkerResult(job.jobId, chars, weights, (lossTotal / job.steps.coerceAtLeast(1)).toFloat(), job.steps)
    }

    fun weightedAverage(results: List<WorkerResult>, local: WorkerResult?): DistributedResult {
        val all = buildList { local?.let(::add); addAll(results) }
        require(all.isNotEmpty()) { "No training participants returned a result" }
        val size = all.first().weights.size
        require(all.all { it.weights.size == size && it.steps > 0 })
        val merged = Array(size) { FloatArray(size) }
        val totalWeight = all.sumOf { it.steps }.toDouble()
        for (result in all) {
            val weight = result.steps / totalWeight
            for (r in 0 until size) for (c in 0 until size) merged[r][c] += (result.weights[r][c] * weight).toFloat()
        }
        val loss = all.sumOf { it.loss.toDouble() * it.steps } / totalWeight
        return DistributedResult(DistributedModel(all.first().vocabulary.copyOf(), merged, all.first().steps + all.drop(1).sumOf { it.steps }), loss.toFloat(), all.sumOf { it.steps })
    }
}

class DistributedLanCoordinator(private val context: Context, private val port: Int = 8765) {
    private val executor = Executors.newCachedThreadPool()
    private val sessions = CopyOnWriteArrayList<WorkerSession>()
    private val pending = ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<WorkerResult>>()
    private var server: ServerSocket? = null
    private var secret = ""
    private var maxWorkers = 0

    fun localIp(): String = LanCoordinator(context).localIp()
    fun sessions(): List<WorkerSession> = sessions.toList()

    fun startMaster(code: String, maxDevices: Int, onConnected: (Result<WorkerSession>) -> Unit) {
        stop()
        secret = code
        maxWorkers = maxDevices.coerceIn(1, 8)
        executor.execute {
            runCatching {
                server = ServerSocket(port)
                while (sessions.size < maxWorkers) {
                    val socket = server!!.accept()
                    socket.tcpNoDelay = true
                    socket.soTimeout = 0
                    val session = handshakeAsMaster(socket)
                    sessions += session
                    onConnected(Result.success(session))
                    executor.execute { listenForWorkerMessages(session) }
                }
            }.onFailure { error -> onConnected(Result.failure(error)) }
        }
    }

    fun connectWorker(host: String, connectPort: Int, code: String, onConnected: (Result<WorkerSession>) -> Unit) {
        executor.execute {
            runCatching {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host.trim(), connectPort.coerceIn(1, 65535)), 10_000)
                socket.soTimeout = 0
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                DistributedCodec.write(output, "HELLO", capabilityJson().put("code", code))
                val reply = DistributedCodec.read(input)
                require(reply.optString("type") == "ACCEPTED") { reply.optString("error", "LAN handshake rejected") }
                val cap = parseCapability(reply.getJSONObject("capability"))
                val session = WorkerSession(cap, socket.inetAddress.hostAddress ?: host, socket, input, output)
                onConnected(Result.success(session))
                while (!socket.isClosed) {
                    val frame = DistributedCodec.read(session.input)
                    if (frame.optString("type") == "TRAIN_JOB") {
                        executor.execute { executeWorkerJob(session, frame) }
                    } else if (frame.optString("type") == "PING") {
                        synchronized(output) { DistributedCodec.write(output, "PONG") }
                    } else if (frame.optString("type") == "CLOSE") break
                }
            }.onFailure { error -> onConnected(Result.failure(error)) }
        }
    }

    fun submitJob(session: WorkerSession, job: WorkerJob): java.util.concurrent.CompletableFuture<WorkerResult> {
        val future = java.util.concurrent.CompletableFuture<WorkerResult>()
        pending[job.jobId] = future
        val body = JSONObject()
            .put("jobId", job.jobId)
            .put("model", DistributedCodec.encodeModel(DistributedModel(job.vocabulary, job.weights, 0)))
            .put("corpus", android.util.Base64.encodeToString(job.corpus.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))
            .put("checksum", DistributedCodec.checksum(job.corpus))
            .put("offset", job.offset)
            .put("steps", job.steps)
            .put("learningRate", job.learningRate.toDouble())
        executor.execute {
            runCatching { synchronized(session.output) { DistributedCodec.write(session.output, "TRAIN_JOB", body) } }
                .onFailure { error -> pending.remove(job.jobId)?.completeExceptionally(error) }
        }
        return future
    }

    fun stop() {
        sessions.forEach { runCatching { synchronized(it.output) { DistributedCodec.write(it.output, "CLOSE") } }; runCatching { it.socket.close() } }
        sessions.clear()
        runCatching { server?.close() }
        server = null
        pending.values.forEach { it.completeExceptionally(IllegalStateException("LAN coordinator stopped")) }
        pending.clear()
    }

    private fun handshakeAsMaster(socket: Socket): WorkerSession {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val hello = DistributedCodec.read(input)
        require(hello.optString("type") == "HELLO") { "Expected HELLO" }
        require(hello.optString("code") == secret) { "Pairing code mismatch" }
        val cap = parseCapability(hello.getJSONObject("capability"))
        DistributedCodec.write(output, "ACCEPTED", JSONObject().put("capability", capabilityJson()))
        return WorkerSession(cap, socket.inetAddress.hostAddress ?: "unknown", socket, input, output)
    }

    private fun listenForWorkerMessages(session: WorkerSession) {
        runCatching {
            while (!session.socket.isClosed) {
                val frame = DistributedCodec.read(session.input)
                when (frame.optString("type")) {
                    "TRAIN_RESULT" -> {
                        val jobId = frame.optString("jobId")
                        val model = DistributedCodec.decodeModel(frame.getString("model"))
                        pending.remove(jobId)?.complete(WorkerResult(jobId, model.vocabulary, model.weights, frame.getDouble("loss").toFloat(), frame.getInt("steps")))
                    }
                    "PONG" -> Unit
                    "ERROR" -> pending.remove(frame.optString("jobId"))?.completeExceptionally(IllegalStateException(frame.optString("message")))
                    else -> Unit
                }
            }
        }.onFailure { error ->
            sessions.remove(session)
            pending.values.forEach { if (!it.isDone) it.completeExceptionally(error) }
        }
    }

    private fun executeWorkerJob(session: WorkerSession, frame: JSONObject) {
        val jobId = frame.optString("jobId")
        runCatching {
            val model = DistributedCodec.decodeModel(frame.getString("model"))
            val corpusBytes = android.util.Base64.decode(frame.getString("corpus"), android.util.Base64.DEFAULT)
            val corpus = String(corpusBytes, Charsets.UTF_8)
            require(DistributedCodec.checksum(corpus) == frame.getString("checksum")) { "Training corpus checksum mismatch" }
            val job = WorkerJob(jobId, model.vocabulary, model.weights, corpus, frame.getInt("offset"), frame.getInt("steps"), frame.getDouble("learningRate").toFloat())
            val result = DistributedMath.trainChunk(job) { DeviceSafety.shouldPauseTraining(context) }
            val body = JSONObject().put("jobId", result.jobId).put("model", DistributedCodec.encodeModel(DistributedModel(result.vocabulary, result.weights, 0)))
                .put("loss", result.loss.toDouble()).put("steps", result.steps)
            synchronized(session.output) { DistributedCodec.write(session.output, "TRAIN_RESULT", body) }
        }.onFailure { error -> synchronized(session.output) { runCatching { DistributedCodec.write(session.output, "ERROR", JSONObject().put("jobId", jobId).put("message", error.message ?: "Worker failed")) } } }
    }

    private fun capabilityJson(): JSONObject {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val battery = context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return JSONObject().put("id", android.os.Build.ID + ":" + android.os.Build.MODEL + ":" + android.os.Build.VERSION.SDK_INT)
            .put("name", android.os.Build.MODEL).put("cpuCores", Runtime.getRuntime().availableProcessors())
            .put("ramMb", info.totalMem / (1024 * 1024)).put("battery", battery)
    }

    private fun parseCapability(value: JSONObject) = WorkerCapability(
        value.optString("id", UUID.randomUUID().toString()), value.optString("name", "Unknown device"),
        value.optInt("cpuCores", 1).coerceIn(1, 128), value.optLong("ramMb", 0).coerceAtLeast(0), value.optInt("battery", -1)
    )
}

class DistributedTrainingCoordinator(private val context: Context, private val lan: DistributedLanCoordinator) {
    suspend fun train(
        initial: DistributedModel,
        corpus: String,
        totalSteps: Int,
        learningRate: Float,
        sessions: List<WorkerSession>,
        limits: Map<String, Int>,
        shouldPause: () -> Boolean,
        onProgress: (DistributedProgress) -> Unit,
        onRoundCheckpoint: (DistributedModel) -> Unit = {}
    ): DistributedModel {
        require(corpus.length >= 3) { "Training data is too short" }
        require(totalSteps in 1..10_000)
        var model = initial
        var completed = initial.completedSteps.coerceAtLeast(0)
        require(completed <= totalSteps) { "Existing model already has more steps than requested total" }
        var round = 0
        val localCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        while (completed < totalSteps) {
            if (shouldPause()) throw ThermalPauseException("Master paused for device safety")
            round++
            val remaining = totalSteps - completed
            val participants = sessions.filter { !it.socket.isClosed && (limits[it.peer] ?: 0) > 0 }
            val weights = mutableListOf<Pair<String, Double>>()
            weights += "LOCAL" to localCores.toDouble()
            participants.forEach { s -> weights += s.peer to s.capability.cpuCores.toDouble() * (limits[s.peer] ?: 0) / 100.0 }
            val totalWeight = weights.sumOf { it.second }.coerceAtLeast(1.0)
            val allocations = allocateSteps(remaining.coerceAtMost(1000), weights, totalWeight)
            val futures = mutableListOf<java.util.concurrent.CompletableFuture<WorkerResult>>()
            var localResult: WorkerResult? = null
            var remoteSteps = 0
            var nextOffset = completed
            allocations.forEach { (peer, steps) ->
                if (steps <= 0) return@forEach
                val jobOffset = nextOffset
                nextOffset += steps
                if (peer == "LOCAL") {
                    val job = WorkerJob("local-$round", model.vocabulary, model.weights, corpus, jobOffset, steps, learningRate)
                    localResult = withContextIO { DistributedMath.trainChunk(job, shouldPause) }
                } else {
                    val session = participants.first { it.peer == peer }
                    futures += lan.submitJob(session, WorkerJob("$round-$peer-${UUID.randomUUID()}", model.vocabulary, model.weights, corpus, jobOffset, steps, learningRate))
                    remoteSteps += steps
                }
            }
            val remoteResults = futures.map { it.get(90, TimeUnit.SECONDS) }
            val merged = DistributedMath.weightedAverage(remoteResults, localResult)
            model = merged.model.copy(completedSteps = model.completedSteps + merged.steps)
            completed += merged.steps
            onRoundCheckpoint(model)
            onProgress(DistributedProgress(completed, totalSteps, round, localResult?.steps ?: 0, remoteResults.sumOf { it.steps }, merged.loss,
                "Round $round complete • local ${localResult?.steps ?: 0} steps • remote ${remoteResults.sumOf { it.steps }} steps"))
        }
        return model
    }

    private fun allocateSteps(remaining: Int, weights: List<Pair<String, Double>>, total: Double): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        var used = 0
        weights.forEachIndexed { index, (id, weight) ->
            val raw = if (index == weights.lastIndex) remaining - used else kotlin.math.floor(remaining * weight / total).toInt()
            val steps = raw.coerceAtLeast(0)
            result[id] = steps
            used += steps
        }
        if (used == 0) result[weights.first().first] = remaining
        return result
    }

    private suspend fun <T> withContextIO(block: () -> T): T = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { block() }
}

class ThermalPauseException(message: String) : Exception(message)
