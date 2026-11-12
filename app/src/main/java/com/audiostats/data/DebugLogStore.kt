package com.audiostats.data

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Called only on PlaybackDebugLog's serial I/O executor. */
internal class DebugLogStore(
    private val directory: File,
    private val chunkBytes: Long = 2L * 1024 * 1024,
    private val maxFiles: Int = 16,
) {
    private var active: File? = null
    private var sequence = 0L

    fun append(line: String) {
        directory.mkdirs()
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        val file = active?.takeIf { it.exists() && it.length() + bytes.size <= chunkBytes }
            ?: File(directory, "playback-${System.currentTimeMillis()}-${(sequence++).toString().padStart(8, '0')}.jsonl")
                .also { active = it }
        file.appendBytes(bytes)
        files().dropLast(maxFiles).forEach { check(it.delete()) { "Cannot remove old debug log" } }
    }

    fun size(): Long = files().sumOf { it.length() }

    fun clear() {
        files().forEach { check(it.delete()) { "Cannot remove debug log" } }
        active = null
    }

    fun export(output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(
                ("AudioLog playback diagnostics. Timestamps include timezone and monotonic uptime.\n" +
                    "qq_compat is package-scoped inference, not verified audio UID attribution.\n" +
                    "Contains private media titles and session data. Share only with trusted people.\n" +
                    "Logs are opt-in, limited to 16 files of about 2 MiB; oldest files rotate out.\n")
                    .toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
            files().forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun files() = directory.listFiles().orEmpty()
        .filter { it.isFile && it.extension == "jsonl" }
        .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
}
