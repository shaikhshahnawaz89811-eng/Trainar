package com.sa.computebridge.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Build
import android.Manifest
import android.widget.*
import android.view.ViewGroup
import android.graphics.Color
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.sa.computebridge.R
import com.sa.computebridge.WorkerRuntime
import com.sa.computebridge.ResourceLimitStore
import com.sa.computebridge.WorkerService
import com.sa.computebridge.engine.BrainEngine
import com.sa.computebridge.engine.ModelFileManager
import com.sa.computebridge.engine.ImportProgress
import com.sa.computebridge.network.PairingStore
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var device: TextView
    private lateinit var worker: TextView
    private lateinit var model: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var qr: Button
    private lateinit var import: Button
    private lateinit var load: Button
    private lateinit var stopTask: Button
    private lateinit var limitLabel: TextView
    private lateinit var limitSeek: SeekBar
    private lateinit var limitInfo: TextView
    private lateinit var pairing: PairingStore
    private lateinit var models: ModelFileManager

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startWorkerService()
        } else {
            status.text = "Notification permission is required to keep the Worker service visible and safe on Android 13+"
        }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            val name = "model-${System.currentTimeMillis()}.gguf"
            models.importModel(uri, name).collect { progress ->
                when (progress) {
                    is ImportProgress.Copying -> status.text = "Importing: ${progress.bytesCopied / 1024 / 1024} MB"
                    is ImportProgress.Done -> { status.text = "Model imported: ${progress.file.name}"; refresh() }
                    is ImportProgress.Failed -> status.text = "Import failed: ${progress.reason}"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status); device = findViewById(R.id.device); worker = findViewById(R.id.worker); model = findViewById(R.id.model)
        start = findViewById(R.id.start); stop = findViewById(R.id.stop); qr = findViewById(R.id.qr); import = findViewById(R.id.import_model); load = findViewById(R.id.load_model); stopTask = findViewById(R.id.stop_task)
        limitLabel = findViewById(R.id.limit_label); limitSeek = findViewById(R.id.limit_seek); limitInfo = findViewById(R.id.limit_info)
        pairing = PairingStore(this); models = ModelFileManager(this)
        val limits = ResourceLimitStore(this)
        limitSeek.max = (ResourceLimitStore.MAX_PERCENT - ResourceLimitStore.MIN_PERCENT) / 10
        limitSeek.progress = (limits.percent - ResourceLimitStore.MIN_PERCENT) / 10
        updateLimitUi(limits.percent)
        limitSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = ResourceLimitStore.MIN_PERCENT + (progress * 10)
                updateLimitUi(value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val value = (ResourceLimitStore.MIN_PERCENT + limitSeek.progress * 10).coerceIn(ResourceLimitStore.MIN_PERCENT, ResourceLimitStore.MAX_PERCENT)
                limitSeek.progress = (value - ResourceLimitStore.MIN_PERCENT) / 10
                limits.percent = value
                updateLimitUi(value)
                status.text = "Worker limit saved: $value%. Reload the model to apply the CPU-thread limit."
            }
        })
        start.setOnClickListener { startWorkerWithPermissionCheck() }
        stop.setOnClickListener { stopService(Intent(this, WorkerService::class.java)); refresh() }
        qr.setOnClickListener { showPairingQr() }
        import.setOnClickListener { picker.launch(arrayOf("application/octet-stream", "*/*")) }
        stopTask.setOnClickListener {
            BrainEngine.cancelGeneration()
            status.text = "Stop requested; worker will stop at the next safe generation checkpoint."
        }
        load.setOnClickListener {
            val installed = models.getLastInstalledModel() ?: run { status.text = "Import a .gguf model first"; return@setOnClickListener }
            lifecycleScope.launch {
                status.text = "Loading ${installed.name}…"
                val ok = BrainEngine.loadModel(this@MainActivity, installed.file.absolutePath)
                status.text = if (ok) "Model loaded and ready" else "Model load failed"
                refresh()
            }
        }
        refresh()
    }

    private fun startWorkerWithPermissionCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startWorkerService()
    }

    private fun startWorkerService() {
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, WorkerService::class.java))
            status.text = "Starting Worker service…"
            refresh()
        }.onFailure {
            status.text = "Could not start Worker safely: ${it.message ?: "unknown error"}"
        }
    }

    private fun updateLimitUi(percent: Int) {
        val limits = ResourceLimitStore(this)
        limitLabel.text = "Worker usage limit: $percent%"
        limitInfo.text = "Approx. CPU threads: ${limits.maxCpuThreads()} · Max output tokens: ${limits.maxTokens(4096)} · Max context: ${limits.maxContextSize()}"
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        device.text = "Worker ID: ${pairing.workerId}\nAddresses: ${WorkerRuntime.addresses().joinToString().ifBlank { "not connected" }}\nPort: ${WorkerRuntime.getPort()}"
        worker.text = "Server: ${if (WorkerRuntime.isRunning()) "RUNNING" else "STOPPED"} · Requests: ${WorkerRuntime.getRequestCount()}"
        model.text = when (val s = BrainEngine.state.value) {
            is com.sa.computebridge.engine.EngineState.Loaded -> "Model: ${s.modelName} · Context ${s.contextSize}"
            is com.sa.computebridge.engine.EngineState.Loading -> "Model: loading ${s.modelName}"
            is com.sa.computebridge.engine.EngineState.Error -> "Model error: ${s.message}"
            else -> "Model: none loaded"
        }
    }

    private fun showPairingQr() {
        if (!WorkerRuntime.isRunning()) {
            status.text = "Start the Worker before generating a pairing QR"
            return
        }
        val address = com.sa.computebridge.network.NetworkInfo.preferredIpv4() ?: run { status.text = "Connect to Wi-Fi or enable hotspot first"; return }
        val payload = "{\"protocol\":\"sa-compute-v1\",\"worker_id\":\"${pairing.workerId}\",\"host\":\"$address\",\"port\":${WorkerRuntime.getPort()},\"pairing_token\":\"${pairing.pairingToken}\"}"
        val bitmap = qrBitmap(payload, 560)
        ImageDialog(this, bitmap).show()
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val matrix: BitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        return bmp
    }
}

private class ImageDialog(activity: Activity, private val bitmap: Bitmap) : android.app.Dialog(activity) {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val scroll = ScrollView(context)
        val image = ImageView(context).apply {
            setImageBitmap(bitmap)
            setPadding(24, 24, 24, 24)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        scroll.addView(image, ViewGroup.LayoutParams(-1, -2))
        setContentView(scroll)
        window?.setLayout(-1, -2)
    }
}
