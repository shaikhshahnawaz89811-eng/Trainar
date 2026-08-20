package com.sa.computebridge.engine

/**
 * Direct 1:1 mapping to the JNI functions implemented in
 * app/src/main/cpp/llama_bridge.cpp. Nothing in this file is simulated -
 * every function call crosses into real llama.cpp code once
 * libbrain_llama_bridge.so is built by the CI (see PROGRESS.md Phase 2).
 */
object BrainNative {

    init {
        System.loadLibrary("brain_llama_bridge")
    }

    /** Called once per process. Loads ggml backends + llama_backend_init(). */
    external fun nativeBackendInit(): Boolean

    /**
     * Loads a real GGUF file from [modelPath] on device storage.
     * @return true only if both the model and its inference context loaded
     * successfully - false on any real failure (bad file, OOM, unsupported
     * architecture, etc.), never faked as success.
     */
    external fun nativeLoadModel(modelPath: String, nCtx: Int, nThreads: Int): Boolean

    external fun nativeUnloadModel()

    external fun nativeIsModelLoaded(): Boolean

    /** Context window size (n_ctx) of the currently loaded model, or 0 if none. */
    external fun nativeGetContextSize(): Int

    /**
     * Runs a real token-by-token generation loop. [callback] receives each
     * generated token's decoded text piece as it comes off the model;
     * returning false from [TokenCallback.onToken] cancels generation
     * mid-stream. Returns the real stop reason ("end_of_generation",
     * "max_tokens", "context_full", "cancelled", or an "error: ..." string).
     */
    external fun nativeGenerate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        callback: TokenCallback
    ): String

    /** Implemented on the Kotlin side; invoked from native code per token. */
    fun interface TokenCallback {
        /** @return true to keep generating, false to stop early. */
        fun onToken(token: String): Boolean
    }
}
