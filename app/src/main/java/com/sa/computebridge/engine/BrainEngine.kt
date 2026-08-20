package com.sa.computebridge.engine

import android.content.Context
import com.sa.computebridge.ResourceGuard
import com.sa.computebridge.ResourceLimitStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

sealed class EngineState {
    data object Unloaded : EngineState()
    data class Loading(val modelName: String) : EngineState()
    data class Loaded(val modelName: String, val contextSize: Int) : EngineState()
    data class Error(val message: String) : EngineState()
}

object BrainEngine {
    private val _state = MutableStateFlow<EngineState>(EngineState.Unloaded)
    val state: StateFlow<EngineState> = _state
    private val generationActive = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private var backendReady = false

    suspend fun loadModel(context: Context, modelPath: String, requestedContext: Int = 2048): Boolean {
        if (isGenerating()) {
            _state.value = EngineState.Error("Cannot load or replace a model while generation is running")
            return false
        }
        val fileName = modelPath.substringAfterLast('/')
        val modelFile = File(modelPath)
        val limits = ResourceLimitStore(context)
        val guard = ResourceGuard(context, limits)
        if (!modelFile.isFile) {
            _state.value = EngineState.Error("Model file does not exist")
            return false
        }
        if (!guard.canLoadModel(modelFile)) {
            _state.value = EngineState.Error(
                "Model exceeds the configured ${limits.percent}% worker memory budget"
            )
            return false
        }

        val nCtx = requestedContext.coerceIn(512, limits.maxContextSize())
        val nThreads = limits.maxCpuThreads()
        _state.value = EngineState.Loading(fileName)

        return withContext(Dispatchers.Default) {
            try {
                if (!backendReady) {
                    backendReady = BrainNative.nativeBackendInit()
                    if (!backendReady) throw IllegalStateException("llama.cpp backend initialization failed")
                }
                if (!BrainNative.nativeLoadModel(modelPath, nCtx, nThreads)) {
                    throw IllegalStateException("GGUF model could not be loaded")
                }
                _state.value = EngineState.Loaded(fileName, BrainNative.nativeGetContextSize())
                true
            } catch (e: Exception) {
                _state.value = EngineState.Error(e.message ?: "Model load failed")
                false
            }
        }
    }

    fun isGenerating(): Boolean = generationActive.get()

    /** Requests a running native generation to stop at the next safe callback/checkpoint. */
    fun cancelGeneration() {
        stopRequested.set(true)
    }

    suspend fun unloadModel() = withContext(Dispatchers.Default) {
        if (BrainNative.nativeIsModelLoaded()) BrainNative.nativeUnloadModel()
        _state.value = EngineState.Unloaded
    }

    val isLoaded: Boolean get() = _state.value is EngineState.Loaded

    fun generate(
        context: Context,
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        topP: Float = 0.9f
    ): Flow<String> = callbackFlow {
        stopRequested.set(false)
        if (!isLoaded) {
            close(IllegalStateException("No GGUF model loaded"))
            return@callbackFlow
        }

        val limits = ResourceLimitStore(context)
        val guard = ResourceGuard(context, limits)
        if (!guard.canStartTask()) {
            close(IllegalStateException("Worker resource limit reached"))
            return@callbackFlow
        }

        if (!generationActive.compareAndSet(false, true)) {
            close(IllegalStateException("Another generation is already running"))
            return@callbackFlow
        }

        val effectiveMaxTokens = limits.maxTokens(maxTokens)
        val cancelled = AtomicBoolean(false)
        val worker = launch(Dispatchers.Default) {
            try {
                val reason = BrainNative.nativeGenerate(prompt, effectiveMaxTokens, temperature, topP) { token ->
                    if (cancelled.get() || stopRequested.get()) false else trySend(token).isSuccess
                }
                if (reason.startsWith("error:")) close(IllegalStateException(reason)) else close()
            } catch (e: Exception) {
                close(e)
            } finally {
                generationActive.set(false)
                stopRequested.set(false)
            }
        }
        awaitClose {
            cancelled.set(true)
            worker.cancel()
        }
    }
}
