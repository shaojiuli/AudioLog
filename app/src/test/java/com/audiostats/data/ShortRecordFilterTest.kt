package com.audiostats.data

import android.content.Context
import org.junit.After
import org.junit.Assert.*
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
class ShortRecordFilterTest {
    private lateinit var context: Context
    private lateinit var db: AudioDb
    private lateinit var settings: TrackSettings
    private val start = 1_790_000_000_000L

    @Before fun setUp() {
        AudioDb::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("audio_stats.db")
        settings = TrackSettings(context)
        settings.prefs.edit().clear().commit()
        db = AudioDb.get(context)
    }

    @After fun tearDown() {
        db.close()
        AudioDb::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    private fun open(attribution: String = Attribution.REPORTED) =
        db.open(Table.SEGMENTS, "player", "Title", null, start, "speaker", attribution = attribution)

    @Test fun defaultsToEnabledFiveSecondsAndDeletesBelowTheBoundary() {
        assertTrue(settings.shortRecordFilterEnabled)
        assertEquals(5, settings.shortRecordFilterSeconds)
        val id = open()
        assertFalse(db.close(Table.SEGMENTS, id, start, start + 4_999))
        assertNull(db.segment(id))
    }

    @Test fun exactlyFiveSecondsIsKept() {
        val id = open()
        assertTrue(db.close(Table.SEGMENTS, id, start, start + 5_000))
        assertEquals(5_000L, db.segment(id)!!.endMs - start)
    }

    @Test fun activeRecordIsNotDeletedAtOpenOrCheckpoint() {
        val id = open()
        assertNotNull(db.segment(id))
        db.updateEnd(Table.SEGMENTS, id, start + 2_000)
        assertNotNull(db.segment(id))
        assertFalse(db.close(Table.SEGMENTS, id, start, start + 3_000))
        assertNull(db.segment(id))
    }

    @Test fun disablingFilterKeepsSubsecondAudioAndZeroLengthIsStillDiscarded() {
        settings.shortRecordFilterEnabled = false
        val short = open()
        assertTrue(db.close(Table.SEGMENTS, short, start, start + 250))
        assertNotNull(db.segment(short))
        val zero = open()
        assertFalse(db.close(Table.SEGMENTS, zero, start, start))
        assertNull(db.segment(zero))
    }

    @Test fun minutesAndSecondsPersistAndApplyOnlyWhenPlaybackEnds() {
        settings.shortRecordFilterSeconds = 65
        assertEquals(65_000L, TrackSettings(context).shortRecordThresholdMs)
        val short = open()
        val boundary = open()
        assertFalse(db.close(Table.SEGMENTS, short, start, start + 64_999))
        assertTrue(db.close(Table.SEGMENTS, boundary, start, start + 65_000))
    }

    @Test fun importsAndExistingHistoryAreNotRetroactivelyFiltered() {
        val history = Segment(0, "history", "Short import", null, start, start + 200, "speaker")
        db.importSegments(listOf(history))
        settings.shortRecordFilterSeconds = 120
        settings.shortRecordFilterEnabled = false
        settings.shortRecordFilterEnabled = true
        assertEquals("Short import", db.allSegments().single().title)
        val fresh = open()
        db.close(Table.SEGMENTS, fresh, start, start + 3_000)
        assertEquals("Short import", db.allSegments().single().title)
    }

    @Test fun unconfirmedManualAndCompatibilityRecordsUseTheSameFilter() {
        listOf(Attribution.UNCONFIRMED, Attribution.MANUAL, Attribution.QQ_COMPAT).forEach { attribution ->
            val id = open(attribution)
            assertFalse(db.close(Table.SEGMENTS, id, start, start + 4_000))
            assertNull(db.segment(id))
        }
    }

    @Test fun outputSplitsAreFilteredByTheirCombinedPlaybackDuration() {
        val a = open()
        val b = open()
        assertTrue(db.close(Table.SEGMENTS, a, start, start + 3_000, filterShortRecord = false))
        assertTrue(db.close(Table.SEGMENTS, b, start, start + 3_000, filterShortRecord = false))
        assertTrue(db.filterFinishedSegments(listOf(a, b), 6_000))
        assertNotNull(db.segment(a))
        assertNotNull(db.segment(b))
        val c = open()
        val d = open()
        db.close(Table.SEGMENTS, c, start, start + 2_000, filterShortRecord = false)
        db.close(Table.SEGMENTS, d, start, start + 2_000, filterShortRecord = false)
        assertFalse(db.filterFinishedSegments(listOf(c, d), 4_000))
        assertNull(db.segment(c))
        assertNull(db.segment(d))
        assertEquals(2, db.allSegments().size)
    }

    @Test fun rawSystemActivityRemainsAnIndependentReference() {
        settings.shortRecordFilterSeconds = 600
        val id = db.open(Table.ACTIVITY, "", null, null, start, "speaker")
        assertTrue(db.close(Table.ACTIVITY, id, start, start + 2_000))
        assertEquals(1, db.overlapping(Table.ACTIVITY, start, start + 3_000).size)
    }
}
