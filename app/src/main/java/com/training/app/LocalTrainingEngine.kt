package com.training.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext
import kotlin.math.exp
import kotlin.math.ln

data class TrainingInput(val name: String, val content: String)
data class TrainingUpdate(val step: Int, val totalSteps: Int, val loss: Float, val stepsPerSecond: Float, val elapsedSeconds: Int, val pausedForThermal: Boolean = false)
data class ResumeInfo(val step: Int, val totalSteps: Int, val savedAtEpochMs: Long)

/** Real local character-model trainer plus persistent state access for distributed training. */
class LocalTrainingEngine(private val context: Context) {
    private val modelFile get() = File(context.filesDir, "training-model.bin")
    private val checkpointFile get() = File(context.filesDir, "training-checkpoint.bin")
    private val corpusFile get() = File(context.filesDir, "training-corpus.txt")

    fun hasSavedModel(): Boolean = modelFile.exists() && modelFile.length() > 16
    fun hasCheckpoint(): Boolean = checkpointFile.exists() && checkpointFile.length() > 32 && corpusFile.exists()
    fun hasTrainingCorpus(): Boolean = corpusFile.exists() && corpusFile.length() >= 3
    fun trainingCorpus(): String? = runCatching { corpusFile.readText().take(MAX_CORPUS_CHARS) }.getOrNull()

    fun saveTrainingCorpus(inputs: List<TrainingInput>) {
        require(inputs.isNotEmpty())
        val corpus = inputs.joinToString("\n") { it.content }.take(MAX_CORPUS_CHARS)
        require(corpus.length >= 3) { "Selected files contain too little text" }
        atomicWriteText(corpus, corpusFile)
    }

    fun resumeInfo(): ResumeInfo? = runCatching {
        DataInputStream(FileInputStream(checkpointFile)).use { input ->
            require(input.readInt() == VERSION)
            ResumeInfo(input.readInt(), input.readInt(), input.readLong())
        }
    }.getOrNull()

    fun savedModelBytes(): ByteArray? = runCatching {
        if (!hasSavedModel()) return null
        modelFile.readBytes().also { require(it.size <= MAX_MODULE_BYTES) }
    }.getOrNull()

    fun exportSavedModel(destination: Uri): Boolean {
        if (!hasSavedModel()) return false
        val output = context.contentResolver.openOutputStream(destination) ?: return false
        output.use { target -> modelFile.inputStream().use { it.copyTo(target) } }
        return true
    }

    fun importSavedModel(source: Uri): Boolean {
        val temp = File(context.cacheDir, "module-import.tmp")
        val copied = runCatching {
            context.contentResolver.openInputStream(source)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_MODULE_BYTES) { "Training module exceeds the safety limit" }
                        output.write(buffer, 0, read)
                    }
                }
            } != null
        }.getOrDefault(false)
        if (!copied) { temp.delete(); return false }
        val valid = runCatching {
            DataInputStream(FileInputStream(temp)).use { input ->
                require(input.readInt() == VERSION)
                val steps = input.readInt(); val vocabularySize = input.readInt()
                require(steps >= 1 && vocabularySize in 2..MAX_VOCAB)
                repeat(vocabularySize) { input.readChar() }
                repeat(vocabularySize * vocabularySize) { input.readFloat() }
                require(input.available() == 0) { "Unexpected bytes in training module" }
            }
            true
        }.getOrDefault(false)
        if (!valid) { temp.delete(); return false }
        atomicReplace(temp, modelFile)
        return true
    }

    fun readSavedModel(): DistributedModel? = runCatching {
        DataInputStream(FileInputStream(modelFile)).use { input ->
            require(input.readInt() == VERSION)
            val steps = input.readInt(); val size = input.readInt()
            require(steps >= 1 && size in 2..MAX_VOCAB)
            val vocabulary = CharArray(size) { input.readChar() }
            val weights = Array(size) { FloatArray(size) { input.readFloat() } }
            DistributedModel(vocabulary, weights, steps)
        }
    }.getOrNull()

    fun writeSavedModel(model: DistributedModel) {
        require(model.vocabulary.size in 2..MAX_VOCAB)
        require(model.weights.size == model.vocabulary.size && model.weights.all { it.size == model.vocabulary.size })
        val temp = File(context.cacheDir, "training-model.tmp")
        DataOutputStream(FileOutputStream(temp)).use { out ->
            out.writeInt(VERSION); out.writeInt(model.completedSteps); out.writeInt(model.vocabulary.size)
            model.vocabulary.forEach { out.writeChar(it.code) }; model.weights.forEach { row -> row.forEach(out::writeFloat) }
        }
        atomicReplace(temp, modelFile)
    }

    fun createInitialModelFromCorpus(corpus: String): DistributedModel {
        val vocabulary = corpus.toSet().take(MAX_VOCAB).toCharArray()
        require(vocabulary.size >= 2) { "Training data needs at least two unique characters" }
        return DistributedModel(vocabulary, Array(vocabulary.size) { FloatArray(vocabulary.size) }, 0)
    }

    suspend fun train(inputs: List<TrainingInput>, maxSteps: Int, learningRate: Float, shouldPauseForThermal: () -> Boolean = { false }, onUpdate: suspend (TrainingUpdate) -> Unit) = withContext(Dispatchers.Default) {
        saveTrainingCorpus(inputs)
        val corpus = trainingCorpus() ?: error("Training corpus could not be saved")
        trainFromCorpus(corpus, maxSteps, learningRate, 0, null, shouldPauseForThermal, onUpdate)
    }

    suspend fun resume(maxSteps: Int, learningRate: Float, shouldPauseForThermal: () -> Boolean = { false }, onUpdate: suspend (TrainingUpdate) -> Unit) = withContext(Dispatchers.Default) {
        require(hasCheckpoint()) { "No resumable training checkpoint exists" }
        val state = loadCheckpoint(); val corpus = trainingCorpus() ?: error("Training corpus is missing")
        trainFromCorpus(corpus, maxSteps.coerceAtLeast(state.step), learningRate, state.step, state, shouldPauseForThermal, onUpdate)
    }

    private suspend fun trainFromCorpus(corpus: String, requestedTotal: Int, learningRate: Float, startStep: Int, checkpoint: ModelState?, shouldPauseForThermal: () -> Boolean, onUpdate: suspend (TrainingUpdate) -> Unit) {
        coroutineContext.ensureActive()
        val chars = (checkpoint?.vocabulary?.toList() ?: corpus.toSet().take(MAX_VOCAB)).toCharArray()
        require(chars.size >= 2)
        val index = chars.withIndex().associate { it.value to it.index }
        val weights = checkpoint?.weights ?: Array(chars.size) { FloatArray(chars.size) }
        val total = requestedTotal.coerceIn(1, MAX_STEPS)
        val start = System.nanoTime(); var lossTotal = checkpoint?.lossTotal ?: 0.0
        for (step in startStep until total) {
            coroutineContext.ensureActive()
            if (shouldPauseForThermal()) {
                saveCheckpoint(chars, weights, step, total, lossTotal)
                onUpdate(TrainingUpdate(step, total, (lossTotal / step.coerceAtLeast(1)).toFloat(), 0f, ((System.nanoTime() - start) / 1_000_000_000L).toInt(), true))
                return
            }
            val position = step % (corpus.length - 1); val input = index[corpus[position]] ?: 0; val target = index[corpus[position + 1]] ?: 0
            val logits = weights[input]; var normalizer = 0.0; for (value in logits) normalizer += exp(value.toDouble())
            var loss = 0.0
            for (classIndex in logits.indices) {
                val probability = exp(logits[classIndex].toDouble()) / normalizer.coerceAtLeast(1e-12)
                val gradient = probability.toFloat() - if (classIndex == target) 1f else 0f
                weights[input][classIndex] -= learningRate * gradient
                if (classIndex == target) loss = -ln(probability.coerceAtLeast(1e-12))
            }
            lossTotal += loss
            val completed = step + 1
            if (completed == 1 || completed % CHECKPOINT_INTERVAL == 0 || completed == total) {
                val seconds = (System.nanoTime() - start) / 1_000_000_000f
                saveCheckpoint(chars, weights, completed, total, lossTotal)
                onUpdate(TrainingUpdate(completed, total, (lossTotal / completed).toFloat(), completed / seconds.coerceAtLeast(.001f), seconds.toInt()))
            }
        }
        writeSavedModel(DistributedModel(chars, weights, total)); checkpointFile.delete()
    }

    private data class ModelState(val vocabulary: CharArray, val weights: Array<FloatArray>, val step: Int, val total: Int, val lossTotal: Double)

    private fun loadCheckpoint(): ModelState = DataInputStream(FileInputStream(checkpointFile)).use { input ->
        require(input.readInt() == VERSION); val step = input.readInt(); val total = input.readInt(); input.readLong(); val lossTotal = input.readDouble(); val size = input.readInt()
        require(size in 2..MAX_VOCAB); val vocabulary = CharArray(size) { input.readChar() }; val weights = Array(size) { FloatArray(size) { input.readFloat() } }
        ModelState(vocabulary, weights, step, total, lossTotal)
    }

    private fun saveCheckpoint(vocabulary: CharArray, weights: Array<FloatArray>, step: Int, total: Int, lossTotal: Double) {
        val temp = File(context.cacheDir, "training-checkpoint.tmp")
        DataOutputStream(FileOutputStream(temp)).use { out ->
            out.writeInt(VERSION); out.writeInt(step); out.writeInt(total); out.writeLong(System.currentTimeMillis()); out.writeDouble(lossTotal); out.writeInt(vocabulary.size)
            vocabulary.forEach { out.writeChar(it.code) }; weights.forEach { row -> row.forEach(out::writeFloat) }
        }
        atomicReplace(temp, checkpointFile)
    }

    private fun atomicWriteText(value: String, destination: File) {
        val temp = File(context.cacheDir, destination.name + ".tmp"); temp.writeText(value); atomicReplace(temp, destination)
    }

    private fun atomicReplace(source: File, destination: File) {
        runCatching { Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            .onFailure { source.copyTo(destination, overwrite = true); source.delete() }
    }

    companion object { private const val VERSION = 2; private const val MAX_STEPS = 10_000; private const val MAX_CORPUS_CHARS = 200_000; private const val MAX_VOCAB = 128; private const val MAX_MODULE_BYTES = 2L * 1024L * 1024L; private const val CHECKPOINT_INTERVAL = 100 }
}
