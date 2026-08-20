package com.sa.computebridge

import android.app.ActivityManager
import android.content.Context
import kotlin.math.ceil
import kotlin.math.max

/**
 * Persistent worker resource policy. The percentage is a real policy input:
 * it limits CPU threads used by llama.cpp, maximum generated tokens, and the
 * pre-load memory budget check. Android does not expose a portable hard CPU
 * percentage throttle for an app, so the UI deliberately calls this a
 * "compute usage limit" rather than claiming to cap CPU at an exact percent.
 */
class ResourceLimitStore(context: Context) {
    private val prefs = context.getSharedPreferences("compute_bridge_limits", Context.MODE_PRIVATE)

    var percent: Int
        get() = prefs.getInt(KEY_PERCENT, DEFAULT_PERCENT).coerceIn(MIN_PERCENT, MAX_PERCENT)
        set(value) = prefs.edit().putInt(KEY_PERCENT, value.coerceIn(MIN_PERCENT, MAX_PERCENT)).apply()

    fun maxCpuThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return ceil(cores * (percent / 100.0)).toInt().coerceIn(1, cores)
    }

    fun maxTokens(requested: Int): Int {
        val cap = when {
            percent <= 25 -> 256
            percent <= 50 -> 512
            percent <= 75 -> 1024
            else -> 4096
        }
        return requested.coerceIn(1, cap)
    }

    fun maxContextSize(): Int = when {
        percent <= 25 -> 1024
        percent <= 50 -> 2048
        percent <= 75 -> 3072
        else -> 4096
    }

    /** Returns the approximate native/app memory budget for this worker. */
    fun memoryBudgetBytes(context: Context): Long {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        return max(1L, memory.totalMem * percent / 100L)
    }

    companion object {
        const val MIN_PERCENT = 10
        const val MAX_PERCENT = 100
        const val DEFAULT_PERCENT = 50
        private const val KEY_PERCENT = "compute_percent"
    }
}
