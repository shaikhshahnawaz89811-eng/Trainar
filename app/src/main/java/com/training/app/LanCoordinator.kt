package com.training.app

import android.content.Context
import android.net.wifi.WifiManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import org.json.JSONObject
import android.os.Handler
import android.os.Looper

data class DeviceCapability(val name: String, val cpuCores: Int, val ramMb: Long, val battery: Int)
data class LanConnection(val capability: DeviceCapability, val peer: String)

/** Same-WiFi LAN transport: line-delimited JSON handshake over a real TCP socket. */
class LanCoordinator(private val context: Context, private val port: Int = 8765) {
    private var server: ServerSocket? = null
    private var secret: String = ""
    private val main = Handler(Looper.getMainLooper())

    fun localIp(): String {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION") val address = wifi?.connectionInfo?.ipAddress ?: 0
        return InetAddress.getByAddress(byteArrayOf(
            (address and 0xff).toByte(), ((address shr 8) and 0xff).toByte(),
            ((address shr 16) and 0xff).toByte(), ((address shr 24) and 0xff).toByte()
        )).hostAddress ?: "0.0.0.0"
    }

    fun startMaster(code: String, maxDevices: Int, onConnection: (Result<LanConnection>) -> Unit) {
        stop()
        secret = code
        Thread {
            runCatching {
                server = ServerSocket(port)
                repeat(maxDevices.coerceIn(1, 8)) {
                    val socket = server!!.accept()
                    val connection = socket.use { handle(it) }
                    main.post { onConnection(Result.success(connection)) }
                }
            }.onFailure { error -> if (server != null) main.post { onConnection(Result.failure(error)) } }
        }.start()
    }

    fun connectWorker(host: String, connectPort: Int, code: String, onResult: (Result<LanConnection>) -> Unit) {
        Thread {
            runCatching {
                Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(host.trim(), connectPort.coerceIn(1, 65535)), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = IO_TIMEOUT_MS
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    writer.println(JSONObject().put("type", "HELLO").put("code", code)
                        .put("capability", capabilityJson()).toString())
                    val reply = JSONObject(reader.readLine() ?: error("Master closed connection"))
                    if (!reply.optBoolean("accepted")) error(reply.optString("error", "LAN handshake rejected"))
                    LanConnection(parseCapability(reply.getJSONObject("capability")), host)
                }
            }.onSuccess { result -> main.post { onResult(Result.success(result)) } }
                .onFailure { error -> main.post { onResult(Result.failure(error)) } }
        }.start()
    }

    fun stop() { runCatching { server?.close() }; server = null }

    private fun handle(socket: Socket): LanConnection {
        socket.soTimeout = IO_TIMEOUT_MS
        val hello = JSONObject(BufferedReader(InputStreamReader(socket.getInputStream())).readLine())
        val writer = PrintWriter(socket.getOutputStream(), true)
        if (hello.optString("code") != secret) {
            writer.println(JSONObject().put("accepted", false).put("error", "Pairing code mismatch"))
            error("Pairing code mismatch")
        }
        val peer = parseCapability(hello.getJSONObject("capability"))
        writer.println(JSONObject().put("accepted", true).put("capability", capabilityJson()).toString())
        return LanConnection(peer, socket.inetAddress.hostAddress ?: "unknown")
    }

    private fun capabilityJson(): JSONObject {
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        val battery = context.getSystemService(android.os.BatteryManager::class.java)
            ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return JSONObject().put("name", android.os.Build.MODEL)
            .put("cpuCores", Runtime.getRuntime().availableProcessors())
            .put("ramMb", info.totalMem / (1024 * 1024)).put("battery", battery)
    }

    private fun parseCapability(value: JSONObject) = DeviceCapability(
        value.optString("name", "Unknown device"), value.optInt("cpuCores", 1),
        value.optLong("ramMb", 0), value.optInt("battery", -1)
    )

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val IO_TIMEOUT_MS = 15_000
    }
}
