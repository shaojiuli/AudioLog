package com.audiostats

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import com.audiostats.data.Attribution
import com.audiostats.data.AudioDb
import com.audiostats.data.Backup
import com.audiostats.data.Books
import com.audiostats.data.CsvIo
import com.audiostats.data.Segment
import com.audiostats.data.Span
import com.audiostats.data.Stats
import com.audiostats.data.Table
import com.audiostats.data.TrackSettings
import com.audiostats.tracker.AudioActivityMonitor
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Device tests use the test APK's sandbox. History repair requires explicit arguments. */
class VerificationInstrumentation : Instrumentation() {
    private var options = Bundle()
    private val results = ArrayList<String>()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        options = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        try {
            when (options.getString("mode", "tests")) {
                "repair" -> repair()
                "ui" -> inspectUi()
                else -> tests()
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\nPASS\n${results.joinToString("\n")}\n") })
        } catch (e: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "\nFAIL\n${e.stackTraceToString()}\n") })
        }
    }

    private fun isolatedDb(testContext: Context): AudioDb {
        check(testContext.packageName != targetContext.packageName)
        check(testContext.getDatabasePath("audio_stats.db").canonicalPath !=
            targetContext.getDatabasePath("audio_stats.db").canonicalPath)
        testContext.deleteDatabase("audio_stats.db")
        val constructor = AudioDb::class.java.getDeclaredConstructor(Context::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(testContext)
    }

    private fun tests() {
        val testContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
        }
        val db = isolatedDb(testContext)
        TrackSettings(testContext).shortRecordFilterEnabled = false
        try {
            val day = LocalDate.of(2026, 1, 1)
            val midnight = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val bookId = db.open(Table.SEGMENTS, Books.SOURCE_PKG, "Test novel", null, midnight + 60_000, "speaker")
            db.updateEnd(Table.SEGMENTS, bookId, midnight + 120_000)
            val pending = db.open(
                Table.SEGMENTS, Attribution.UNKNOWN_PKG, null, null, midnight - 60_000, "bluetooth",
                attribution = Attribution.UNCONFIRMED,
            )
            db.updateEnd(Table.SEGMENTS, pending, midnight + 30_000)
            val raw = db.segment(pending)!!
            val stats = Stats.period(db, Span.DAY, day)
            check(stats.tracks.single { it.id == pending }.startMs == midnight - 60_000)
            check(stats.totalMs == 90_000L)
            check(db.archiveSegment(pending, "Test novel"))
            val archived = db.segment(pending)!!
            check(archived.startMs == raw.startMs && archived.endMs == raw.endMs)
            check(archived.output == "bluetooth" && archived.mediaId == null && archived.artist == null)
            check(archived.attribution == Attribution.MANUAL && archived.originalInfo!!.contains(Attribution.UNKNOWN_PKG))
            check(db.allSegments().size == 2)
            check(db.archiveSegment(pending, "Test novel"))
            check(db.segment(pending)!!.originalInfo == archived.originalInfo)
            db.updateMeta(Table.SEGMENTS, pending, "Stale song", "Singer", "123")
            check(db.segment(pending)!!.title == "Test novel")
            db.updateEnd(Table.SEGMENTS, pending, midnight + 45_000)
            check(db.segment(pending)!!.endMs == midnight + 45_000)
            check(!db.archiveSegment(-1, "Test novel"))
            check(runCatching { db.archiveSegment(pending, "Missing novel") }.isFailure)
            val book = Books.load(db).single()
            check(book.totalMs == 165_000L && book.ranges.size == 2)
            check(book.ranges.last().start.minute == 59 && book.ranges.last().end.minute == 0)
            results += "Whole-segment cross-midnight archive, provenance, idempotence, stale metadata protection, range minutes: OK"

            val export = CsvIo.export(testContext)
            check(export.contains("attribution,original_info") && export.contains("manual"))
            check(CsvIo.import(testContext, export).duplicates == 2)
            val backup = ByteArrayOutputStream().also { Backup.export(testContext, it) }.toByteArray()
            db.writableDatabase.delete("segments", null, null)
            val restored = Backup.import(testContext, backup.inputStream())
            check(restored.segments == 2)
            check(db.allSegments().single { it.attribution == Attribution.MANUAL }.originalInfo == archived.originalInfo)
            results += "CSV and ZIP backup attribution/provenance round trip: OK"

            db.writableDatabase.delete("segments", null, null)
            var active = true
            var allowed = true
            var output = "speaker"
            val monitor = AudioActivityMonitor(
                db, { active }, Handler(Looper.getMainLooper()), { output }, { allowed }
            )
            monitor.evaluate()
            val firstId = db.writableDatabase.rawQuery(
                "SELECT id FROM segments WHERE attribution = ?", arrayOf(Attribution.UNCONFIRMED)
            ).use { check(it.moveToFirst()); it.getLong(0) }
            Thread.sleep(1_100)
            monitor.checkpoint()
            output = "bluetooth"
            monitor.splitForOutputChange()
            monitor.checkpoint()
            check(db.allSegments().single().id == firstId)
            check(db.segment(firstId)!!.output == "mixed")
            check(db.segment(firstId)!!.title == null)
            active = false
            monitor.evaluate()
            check(db.allSegments().size == 1)
            active = true
            monitor.evaluate()
            Thread.sleep(1_100)
            allowed = false
            monitor.evaluate()
            check(db.allSegments().size == 2)
            monitor.stop()
            monitor.checkpoint()
            check(db.allSegments().size == 2)
            results += "Unconfirmed activity: no fabricated title, continuous across output changes, closes on silence/known playback: OK"

            val current = db.writableDatabase
            current.execSQL("DROP TABLE segments")
            current.execSQL("DROP TABLE activity")
            for (table in listOf("segments", "activity")) {
                current.execSQL(
                    "CREATE TABLE $table (id INTEGER PRIMARY KEY AUTOINCREMENT, pkg TEXT NOT NULL," +
                        " title TEXT, artist TEXT, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL," +
                        " output TEXT NOT NULL, media_id TEXT)"
                )
            }
            current.execSQL("INSERT INTO segments (pkg,title,start_ms,end_ms,output) VALUES ('old','old',1,2000,'speaker')")
            current.version = 6
            db.close()
            val constructor = AudioDb::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
            constructor.newInstance(testContext).use { upgraded ->
                check(upgraded.readableDatabase.version == 7)
                val old = upgraded.allSegments().single()
                check(old.title == "old" && old.attribution == Attribution.LEGACY && old.originalInfo == null)
            }
            results += "v6 to v7 migration preserves existing records: OK"
        } finally {
            db.close()
        }
    }

    private fun repair() {
        fun required(key: String) = requireNotNull(options.getString(key)) { "Missing $key" }
        fun decoded(key: String) = String(Base64.decode(required(key), Base64.DEFAULT), Charsets.UTF_8)
        val sourcePkg = required("source_pkg")
        val sourceId = required("source_media_id")
        val before = decoded("before_title_b64")
        val after = decoded("after_title_b64")
        val cutoff = required("cutoff_ms").toLong()
        val expected = required("expected_count").toInt()
        val db = AudioDb.get(targetContext)
        val sqlite = db.writableDatabase
        sqlite.beginTransaction()
        try {
            val source = db.segmentsOf(sourcePkg).filter { it.mediaId == sourceId }
            check(source.size == expected) { "Expected $expected records, found ${source.size}; nothing changed" }
            check(Books.load(db).map { it.name }.containsAll(listOf(before, after)))
            val countBefore = db.allSegments().size
            val durationBefore = db.allSegments().sumOf { it.endMs - it.startMs }
            source.forEach { check(db.archiveSegment(it.id, if (it.startMs < cutoff) before else after)) }
            check(db.allSegments().size == countBefore)
            check(db.allSegments().sumOf { it.endMs - it.startMs } == durationBefore)
            check(db.segmentsOf(sourcePkg).none { it.mediaId == sourceId })
            check(source.all { db.segment(it.id)!!.attribution == Attribution.MANUAL })
            sqlite.setTransactionSuccessful()
            results += "Repaired ${source.size} records: before cutoff=${source.count { it.startMs < cutoff }}, on/after=${source.count { it.startMs >= cutoff }}"
            results += "Preserved all IDs, timestamps, durations and output fields; saved original attribution"
        } finally {
            sqlite.endTransaction()
        }
    }

    private fun inspectUi() {
        val launch = targetContext.packageManager.getLaunchIntentForPackage(targetContext.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(launch)
        Thread.sleep(2_000)
        val actions = options.getString("actions").orEmpty().split('|').filter { it.isNotBlank() }
        for (action in actions) {
            val root = uiAutomation.rootInActiveWindow ?: error("No active window")
            if (action == "scroll") {
                fun scrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if (node.isScrollable) return node
                    for (i in 0 until node.childCount) node.getChild(i)?.let {
                        scrollable(it)?.let { found -> return found }
                    }
                    return null
                }
                check(scrollable(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true)
                Thread.sleep(600)
                continue
            }
            val node = findNode(root, action) ?: error("No UI node: $action")
            var click: AccessibilityNodeInfo? = node
            while (click != null && !click.isClickable) click = click.parent
            check(click?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Cannot click $action" }
            Thread.sleep(1_000)
        }
        val root = uiAutomation.rootInActiveWindow ?: error("No active window")
        val lines = ArrayList<String>()
        fun collect(node: AccessibilityNodeInfo) {
            if (!node.text.isNullOrEmpty()) lines += node.text.toString()
            if (!node.contentDescription.isNullOrEmpty()) lines += "[${node.contentDescription}]"
            for (i in 0 until node.childCount) node.getChild(i)?.let(::collect)
        }
        collect(root)
        val name = options.getString("name", "screen")!!.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        File(context.filesDir, "$name.txt").writeText(lines.joinToString("\n"))
        val image = uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(context.filesDir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        results += lines.joinToString("\n")
        results += "Screenshot saved as $name.png in test APK sandbox"
    }

    private fun findNode(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { findNode(it, text)?.let { found -> return found } }
        return null
    }
}
