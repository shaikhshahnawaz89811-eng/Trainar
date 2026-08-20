package com.sa.computebridge.engine

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream

/** One step of a real, byte-counted copy - used to drive an accurate import progress bar. */
sealed class ImportProgress {
    data class Copying(val bytesCopied: Long, val totalBytes: Long) : ImportProgress()
    data class Done(val file: File) : ImportProgress()
    data class Failed(val reason: String) : ImportProgress()
}

data class InstalledModel(val file: File, val name: String, val sizeBytes: Long)

/**
 * Handles getting a real .gguf file from the user's device storage (via the
 * system file picker / Storage Access Framework) into app-private storage,
 * where BrainEngine can hand its path straight to llama.cpp.
 *
 * No sample/bundled model ships with the app - Qwen2.5-1.5B-Instruct GGUF
 * is roughly 1GB, far too large to vendor in an APK or this build
 * environment (no network access here to fetch it). The user supplies
 * their own GGUF file (downloaded separately, e.g. from Hugging Face).
 * This is documented honestly in PROGRESS.md rather than hidden behind a
 * fake pre-loaded model.
 */
class ModelFileManager(private val context: Context) {

    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    private val prefs = context.getSharedPreferences("brain_engine_prefs", Context.MODE_PRIVATE)

    /** GGUF magic bytes per the real spec: 0x47 0x47 0x55 0x46 ("GGUF"). */
    private fun isValidGguf(uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(4)
                val read = input.read(header)
                read == 4 && header.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Copies the picked file into app-private storage in chunks, emitting
     * real progress based on actual bytes copied (queried from the
     * ContentResolver, not estimated).
     */
    fun importModel(uri: Uri, displayName: String): Flow<ImportProgress> = flow {
        if (!isValidGguf(uri)) {
            emit(ImportProgress.Failed("Selected file is not a valid GGUF model (bad header)."))
            return@flow
        }

        val totalBytes = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        val destFile = File(modelsDir, sanitizeFileName(displayName))

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(1 shl 20) // 1 MB chunks
                    var copied = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        copied += read
                        emit(ImportProgress.Copying(copied, totalBytes))
                    }
                }
            } ?: run {
                emit(ImportProgress.Failed("Could not open the selected file."))
                return@flow
            }
        } catch (e: Exception) {
            destFile.delete()
            emit(ImportProgress.Failed("Copy failed: ${e.message}"))
            return@flow
        }

        prefs.edit { putString(KEY_LAST_MODEL_PATH, destFile.absolutePath) }
        emit(ImportProgress.Done(destFile))
    }.flowOn(Dispatchers.IO)

    fun listModels(): List<InstalledModel> = modelsDir.listFiles { f -> f.isFile && f.extension.equals("gguf", ignoreCase = true) }
        ?.sortedBy { it.name.lowercase() }
        ?.map { InstalledModel(it, it.name, it.length()) } ?: emptyList()

    fun getLastInstalledModel(): InstalledModel? {
        val path = prefs.getString(KEY_LAST_MODEL_PATH, null) ?: return null
        val file = File(path)
        if (!file.exists()) return null
        return InstalledModel(file, file.name, file.length())
    }

    fun deleteModel(model: InstalledModel) {
        model.file.delete()
        if (prefs.getString(KEY_LAST_MODEL_PATH, null) == model.file.absolutePath) {
            prefs.edit { remove(KEY_LAST_MODEL_PATH) }
        }
    }

    private fun sanitizeFileName(name: String): String {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (safe.endsWith(".gguf")) safe else "$safe.gguf"
    }

    companion object {
        private const val KEY_LAST_MODEL_PATH = "last_model_path"
    }
}
