package com.audiostats.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DebugLogStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun exportRetainsCompleteJsonLinesAndWorksWhenEmpty() {
        val store = DebugLogStore(temporary.newFolder())
        store.append("""{"event":"start","title":"Novel"}""")
        store.append("""{"event":"stop"}""")
        val bytes = ByteArrayOutputStream().also { store.export(it) }.toByteArray()
        val entries = entries(bytes)
        assertTrue(entries.containsKey("README.txt"))
        assertEquals("{\"event\":\"start\",\"title\":\"Novel\"}\n{\"event\":\"stop\"}\n",
            entries.filterKeys { it.endsWith(".jsonl") }.values.single())
        assertTrue(entries(ByteArrayOutputStream().also {
            DebugLogStore(temporary.newFolder()).export(it)
        }.toByteArray()).containsKey("README.txt"))
    }

    @Test fun rotationKeepsNewestFilesAndDoesNotDeleteUnrelatedFiles() {
        val dir = temporary.newFolder()
        val unrelated = File(dir, "notes.txt").apply { writeText("keep") }
        val store = DebugLogStore(dir, chunkBytes = 8, maxFiles = 2)
        repeat(30) { store.append("line${it.toString().padStart(3, '0')}") }
        val lines = entries(ByteArrayOutputStream().also { store.export(it) }.toByteArray())
            .filterKeys { it.endsWith(".jsonl") }.values.toSet()
        assertEquals(setOf("line028\n", "line029\n"), lines)
        assertEquals(16L, store.size())
        assertTrue(unrelated.exists())
    }

    @Test fun clearingRemovesOnlyDebugFilesAndRecordingCanResume() {
        val dir = temporary.newFolder()
        val unrelated = File(dir, "audio_stats.db").apply { writeText("keep") }
        val store = DebugLogStore(dir)
        store.append("old")
        assertTrue(store.size() > 0)
        store.clear()
        assertEquals(0L, store.size())
        assertEquals("keep", unrelated.readText())
        store.append("new")
        assertEquals("new\n", entries(ByteArrayOutputStream().also { store.export(it) }.toByteArray())
            .filterKeys { it.endsWith(".jsonl") }.values.single())
    }

    private fun entries(bytes: ByteArray) = buildMap {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
