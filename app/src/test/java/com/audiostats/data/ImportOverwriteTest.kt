package com.audiostats.data

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class ImportOverwriteTest {
    private lateinit var context: Context
    private lateinit var db: AudioDb
    private val start = 1_790_000_000_000L

    @Before fun setUp() {
        singleton(null)
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("audio_stats.db")
        db = AudioDb.get(context)
    }

    @After fun tearDown() {
        db.close()
        singleton(null)
    }

    private fun singleton(value: AudioDb?) {
        AudioDb::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, value)
    }

    private fun segment(pkg: String = "old.player", time: Long = start) =
        Segment(0, pkg, "Old title", "Artist", time, time + 60_000, "bluetooth", "123", Attribution.LEGACY)

    @Test fun sameApplicationOverwritesInPlaceAndPreservesUnrelatedRecords() {
        assertEquals(2, db.importSegments(listOf(segment(), segment("other.player"))).inserted)
        val id = db.segmentsOf("old.player").single().id
        val edited = segment().copy(title = "New title", artist = null, mediaId = null)
        val result = db.importSegments(listOf(edited))
        assertEquals(AudioDb.ImportResult(0, 1, 0), result)
        assertEquals(edited.copy(id = id), db.segment(id))
        assertEquals(2, db.allSegments().size)
        assertEquals("Old title", db.segmentsOf("other.player").single().title)
        assertEquals(AudioDb.ImportResult(0, 0, 1), db.importSegments(listOf(edited)))
    }

    @Test fun changedApplicationMatchesOriginalSourceRatherThanInserting() {
        db.importSegments(listOf(segment()))
        val id = db.allSegments().single().id
        val corrected = segment().copy(
            pkg = Books.SOURCE_PKG, title = "Novel", artist = null, mediaId = null,
            attribution = Attribution.MANUAL,
            originalInfo = JSONObject().put("pkg", "old.player").put("media_id", "123").toString(),
        )
        assertEquals(AudioDb.ImportResult(0, 1, 0), db.importSegments(listOf(corrected)))
        assertEquals(corrected.copy(id = id), db.segment(id))
        assertTrue(db.segmentsOf("old.player").isEmpty())
        assertEquals(AudioDb.ImportResult(0, 0, 1), db.importSegments(listOf(corrected)))
        assertEquals(1, db.allSegments().size)
    }

    @Test fun ambiguousMatchRollsBackTheWholeImport() {
        db.importSegments(listOf(segment()))
        val old = db.allSegments().single()
        val duplicate = db.open(Table.SEGMENTS, old.pkg, "Duplicate", null, old.startMs, old.output)
        db.updateEnd(Table.SEGMENTS, duplicate, old.endMs)
        val before = db.allSegments()
        assertTrue(runCatching {
            db.importSegments(listOf(segment("new.player"), segment().copy(title = "Changed")))
        }.isFailure)
        assertEquals(before, db.allSegments())
    }

    @Test fun csvHandlesEscapingInvalidRowsAndRepeatedImport() {
        val csv = "pkg,title,artist,start_ms,end_ms,output\n" +
            "old.player,\"Novel, \"\"quoted\"\"\nsecond line\",,$start,${start + 60_000},speaker\n" +
            "invalid.player,Invalid,,${start + 60_000},$start,speaker\n"
        val first = CsvIo.import(context, csv)
        assertEquals(CsvIo.ImportResult(1, 0, 0, 1), first)
        assertEquals("Novel, \"quoted\"\nsecond line", db.allSegments().single().title)
        assertEquals(CsvIo.ImportResult(0, 0, 1, 1), CsvIo.import(context, csv))
        val changed = CsvIo.import(context, csv.replace("second line", "edited line"))
        assertEquals(CsvIo.ImportResult(0, 1, 0, 1), changed)
    }

    @Test fun zipUpdatesRecordsAndNotesWithoutDeletingOtherRecords() {
        db.importSegments(listOf(segment()))
        val id = db.allSegments().single().id
        db.setBookNote("Novel", "Backup note")
        val bytes = ByteArrayOutputStream().also { Backup.export(context, it) }.toByteArray()
        db.importSegments(listOf(segment().copy(title = "Changed"), segment("unrelated.player")))
        db.setBookNote("Novel", "Changed note")
        val result = Backup.import(context, bytes.inputStream())
        assertEquals(0, result.segments)
        assertEquals(1, result.updated)
        assertEquals(2, db.allSegments().size)
        assertEquals("Old title", db.segment(id)!!.title)
        assertEquals("Backup note", db.bookNotes()["Novel"])
        val repeated = Backup.import(context, bytes.inputStream())
        assertEquals(0, repeated.updated)
        assertEquals(1, repeated.duplicates)
    }

    @Test fun providedHistoryFileUpdatesExactly38RecordsWithoutChangingTiming() {
        val beforePath = System.getenv("AUDIOLOG_CSV_BEFORE")
        val correctedPath = System.getenv("AUDIOLOG_CSV_CORRECTED")
        assumeTrue(beforePath != null && correctedPath != null)
        assertEquals(2712, CsvIo.import(context, File(beforePath!!).readText()).inserted)
        val before = db.allSegments().associateBy { it.id }
        val corrected = File(correctedPath!!).readText()
        assertEquals(CsvIo.ImportResult(0, 38, 2674, 0), CsvIo.import(context, corrected))
        val after = db.allSegments()
        assertEquals(2712, after.size)
        after.forEach { s ->
            val old = before.getValue(s.id)
            assertEquals(old.startMs, s.startMs)
            assertEquals(old.endMs, s.endMs)
            assertEquals(old.output, s.output)
            if (old.pkg != "com.netease.cloudmusic" || old.mediaId != "1854100697") assertEquals(old, s)
        }
        assertEquals(38, after.count { it.attribution == Attribution.MANUAL })
        assertEquals(36, after.count { it.attribution == Attribution.MANUAL && it.startMs < 1_794_326_400_000L })
        assertEquals(2, after.count { it.attribution == Attribution.MANUAL && it.startMs >= 1_794_326_400_000L })
        assertTrue(after.none { it.pkg == "com.netease.cloudmusic" && it.mediaId == "1854100697" })
        assertEquals(CsvIo.ImportResult(0, 0, 2712, 0), CsvIo.import(context, corrected))
    }
}
