package com.sa.computebridge.network

import android.content.Context
import java.security.SecureRandom

class PairingStore(val context: Context) {
    private val prefs = context.getSharedPreferences("compute_bridge_pairing", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    val workerId: String
        get() = prefs.getString("worker_id", null) ?: createWorkerId().also { prefs.edit().putString("worker_id", it).apply() }

    val pairingToken: String
        get() = prefs.getString("pairing_token", null) ?: createToken().also { prefs.edit().putString("pairing_token", it).apply() }

    fun regeneratePairingToken(): String = createToken().also { prefs.edit().putString("pairing_token", it).apply() }

    private fun createWorkerId(): String = "WK-${randomHex(4)}-${randomHex(4)}"
    private fun createToken(): String = "CB-${randomHex(6)}-${randomHex(6)}"
    private fun randomHex(n: Int): String {
        val chars = "0123456789ABCDEF"
        return buildString { repeat(n) { append(chars[random.nextInt(chars.length)]) } }
    }
}
