package com.training.app

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipInputStream

data class ZipAnalysis(
    val root: File,
    val fileCount: Int,
    val totalBytes: Long,
    val names: List<String>
)

object ZipProjectAnalyzer {
    private const val MAX_FILES = 5_000
    private const val MAX_UNCOMPRESSED_BYTES = 100L * 1024L * 1024L
    private const val BUFFER_SIZE = 64 * 1024

    fun extractSafely(context: Context, uri: Uri): ZipAnalysis {
        val root = File(context.cacheDir, "project-${System.currentTimeMillis()}").apply { mkdirs() }
        var count = 0
        var bytes = 0L
        val names = mutableListOf<String>()
        try {
            context.contentResolver.openInputStream(uri)?.use { source ->
                ZipInputStream(source.buffered(BUFFER_SIZE)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        require(++count <= MAX_FILES) { "ZIP contains too many files (limit $MAX_FILES)" }
                        val cleanName = entry.name.replace('\\', '/')
                        require(cleanName.isNotBlank() && !cleanName.startsWith("/") && !cleanName.split('/').contains("..")) {
                            "Unsafe ZIP path: ${entry.name}"
                        }
                        val output = File(root, cleanName)
                        require(output.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
                            "Unsafe ZIP path: ${entry.name}"
                        }
                        if (entry.isDirectory) {
                            output.mkdirs()
                        } else {
                            output.parentFile?.mkdirs()
                            output.outputStream().use { destination ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                while (true) {
                                    val read = zip.read(buffer)
                                    if (read <= 0) break
                                    bytes += read
                                    require(bytes <= MAX_UNCOMPRESSED_BYTES) { "ZIP expands beyond the 100 MB safety limit" }
                                    destination.write(buffer, 0, read)
                                }
                            }
                            if (names.size < 100) names += cleanName
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: error("Could not read selected ZIP")
            return ZipAnalysis(root, count, bytes, names)
        } catch (error: Throwable) {
            root.deleteRecursively()
            throw error
        }
    }
}
