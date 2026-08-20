package com.sa.computebridge

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import java.io.File

/** Real runtime guard for the configured worker resource policy. */
class ResourceGuard(private val context: Context, private val limits: ResourceLimitStore) {
    data class Status(
        val totalRamBytes: Long,
        val availableRamBytes: Long,
        val appPssBytes: Long,
        val budgetBytes: Long,
        val withinBudget: Boolean
    )

    fun status(extraBytes: Long = 0L): Status {
        val memoryInfo = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memoryInfo)
        val debugInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(debugInfo)
        val appPssBytes = debugInfo.totalPss.toLong() * 1024L
        val budget = limits.memoryBudgetBytes(context)
        val projected = appPssBytes + extraBytes.coerceAtLeast(0L)
        return Status(memoryInfo.totalMem, memoryInfo.availMem, appPssBytes, budget, projected <= budget)
    }

    fun canLoadModel(modelFile: File): Boolean {
        // Model file size is only a lower-bound signal for native memory use;
        // the actual llama.cpp allocation is validated by the native loader.
        return status(modelFile.length()).withinBudget
    }

    fun canStartTask(): Boolean = status().withinBudget
}
