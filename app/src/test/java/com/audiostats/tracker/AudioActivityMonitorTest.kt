package com.audiostats.tracker

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.audiostats.data.Attribution
import com.audiostats.data.AudioDb
import com.audiostats.data.PlaybackDebugLog
import com.audiostats.data.TrackSettings
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
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
class AudioActivityMonitorTest {
    private lateinit var context: Context
    private lateinit var db: AudioDb

    @Before fun setUp() {
        resetDebugLog()
        AudioDb::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("audio_stats.db")
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        db = AudioDb.get(context)
    }

    @After fun tearDown() {
        resetDebugLog()
        db.close()
        AudioDb::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    private fun resetDebugLog() {
        val field = PlaybackDebugLog::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (field.get(null) as? PlaybackDebugLog)?.let { logger ->
            val executor = PlaybackDebugLog::class.java.getDeclaredField("executor")
                .apply { isAccessible = true }.get(logger) as ExecutorService
            executor.shutdown()
            check(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
        field.set(null, null)
    }

    @Test fun compatibilityIsEvaluatedBeforeDecidingWhetherToOpenPendingAudio() {
        var compatibility = false
        var eligible = true
        val monitor = AudioActivityMonitor(
            db, { true }, Handler(Looper.getMainLooper()), { "speaker" },
            keepUnconfirmed = { !compatibility },
            beforeEvaluate = { active -> compatibility = active && eligible },
        )
        monitor.evaluate()
        assertTrue(compatibility)
        assertTrue(db.allSegments().isEmpty())
        eligible = false
        monitor.evaluate()
        assertEquals(Attribution.UNCONFIRMED, db.allSegments().single().attribution)
        Thread.sleep(1_100)
        monitor.stop()
    }

    @Test fun pendingAudioSurvivesOutputChangesAsOneWholeSegment() {
        TrackSettings(context).shortRecordFilterEnabled = false
        var active = true
        var output = "speaker"
        val monitor = AudioActivityMonitor(
            db, { active }, Handler(Looper.getMainLooper()), { output }, { true },
        )
        monitor.evaluate()
        val original = db.allSegments().single()
        Thread.sleep(1_100)
        output = "bluetooth"
        monitor.splitForOutputChange()
        monitor.checkpoint()
        assertEquals(original.id, db.allSegments().single().id)
        assertEquals("mixed", db.allSegments().single().output)
        active = false
        monitor.evaluate()
        assertEquals(1, db.allSegments().size)
        assertTrue(db.allSegments().single().endMs > original.startMs)
        monitor.stop()
    }

    @Test fun debugIsOptInAndClearDoesNotTouchPlaybackDatabase() {
        val debug = PlaybackDebugLog.get(context)
        debug.clear()
        debug.record("must_not_be_logged")
        assertEquals(0L, debug.size())
        TrackSettings(context).debugPlayback = true
        debug.record("enabled_event")
        assertTrue(debug.size() > 0)
        val zip = ByteArrayOutputStream().also { debug.export(it) }.toByteArray()
        val contents = buildString {
            ZipInputStream(zip.inputStream()).use { input ->
                while (true) {
                    input.nextEntry ?: break
                    append(input.readBytes().toString(Charsets.UTF_8))
                }
            }
        }
        assertTrue(contents.contains("enabled_event"))
        assertFalse(contents.contains("must_not_be_logged"))
        TrackSettings(context).debugPlayback = false
        val size = debug.size()
        debug.record("disabled_event")
        assertEquals(size, debug.size())
        val id = db.open(com.audiostats.data.Table.SEGMENTS, "test.player", "Keep", null, 1000L, "speaker")
        debug.clear()
        assertEquals(0L, debug.size())
        assertEquals("Keep", db.segment(id)!!.title)
    }
}
