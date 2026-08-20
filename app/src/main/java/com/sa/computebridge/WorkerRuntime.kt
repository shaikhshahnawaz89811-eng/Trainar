package com.sa.computebridge

import android.content.Context
import com.sa.computebridge.engine.ModelFileManager
import com.sa.computebridge.engine.BrainEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.sa.computebridge.network.NetworkInfo
import com.sa.computebridge.network.PairingStore
import com.sa.computebridge.network.WorkerAdvertiser
import com.sa.computebridge.server.WorkerHttpServer
import java.util.concurrent.atomic.AtomicLong

object WorkerRuntime {
    private var server: WorkerHttpServer? = null
    private var advertiser: WorkerAdvertiser? = null
    private var port: Int = 8765
    private val requestCount = AtomicLong(0)
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        val pairing = PairingStore(app)
        val models = ModelFileManager(app)
        val http = WorkerHttpServer(port, pairing, models, ::recordRequest)
        try {
            http.start()
            server = http
            advertiser = WorkerAdvertiser(app) { port }.also { it.start(pairing.workerId) }
        } catch (t: Throwable) {
            runCatching { http.stop() }
            server = null
            advertiser?.stop()
            advertiser = null
            throw IllegalStateException("Unable to start local worker server on port $port", t)
        }
    }

    @Synchronized
    fun stop(context: Context? = null) {
        // Stop accepting new work first, then request the native generation to
        // cancel. This makes an unexpected service shutdown safe even while
        // llama.cpp is generating.
        server?.stop(); server = null
        advertiser?.stop(); advertiser = null
        BrainEngine.cancelGeneration()
        if (context != null) {
            lifecycleScope.launch {
                runCatching { BrainEngine.unloadModel() }
            }
        }
    }

    fun isRunning(): Boolean = server != null
    fun getPort(): Int = port
    fun addresses(): List<String> = NetworkInfo.ipv4Addresses()

    fun recordRequest() { requestCount.incrementAndGet() }

    fun getRequestCount(): Long = requestCount.get()
}
