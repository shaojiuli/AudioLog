package com.audiostats.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import org.json.JSONObject

/**
 * 一段连续的播放记录。
 * - segments 表：来自 MediaSession，能知道是哪个 App、哪首歌
 * - activity 表：来自 AudioManager.isMusicActive，只知道"系统在放声音"，用来对照总时长
 */
data class Segment(
    val id: Long,
    val pkg: String,
    val title: String?,
    val artist: String?,
    val startMs: Long,
    val endMs: Long,
    val output: String,
    /** 播放器给的媒体 ID（网易云是歌曲 ID）；旧记录和不提供的应用为空 */
    val mediaId: String? = null,
    val attribution: String = Attribution.LEGACY,
    val originalInfo: String? = null,
)

/**
 * 媒体信息：播放器给的媒体 ID 在同一应用内唯一（网易云是歌曲 ID、B 站是视频 ID），
 * 以「应用 + ID」为键，存歌名、歌手、专辑、单曲时长和封面，辅助统计
 */
data class SongMeta(
    val pkg: String,
    val mediaId: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val artFile: String?,
)

enum class Table(val sqlName: String) { SEGMENTS("segments"), ACTIVITY("activity") }

class AudioDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "audio_stats.db", null, 7) {

    private val appContext = context.applicationContext
    private val settings by lazy { TrackSettings(appContext) }

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        for (t in Table.entries) {
            db.execSQL(
                """CREATE TABLE ${t.sqlName} (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    pkg TEXT NOT NULL,
                    title TEXT,
                    artist TEXT,
                    start_ms INTEGER NOT NULL,
                    end_ms INTEGER NOT NULL,
                    output TEXT NOT NULL,
                    media_id TEXT,
                    attribution TEXT NOT NULL DEFAULT 'legacy',
                    original_info TEXT
                )"""
            )
            db.execSQL("CREATE INDEX idx_${t.sqlName}_start ON ${t.sqlName}(start_ms)")
            db.execSQL("CREATE INDEX idx_${t.sqlName}_end ON ${t.sqlName}(end_ms)")
        }
        createBookAlias(db)
        createBookNote(db)
        createMediaMeta(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 → v2（App 1.4）：新增书名合并表，原有记录不动
        if (oldVersion < 2) createBookAlias(db)
        // v2 → v3（App 1.16）：新增书的备注表
        if (oldVersion < 3) createBookNote(db)
        // v4 → v5（App 1.19）：记录媒体 ID；歌曲信息改为以歌曲 ID 为键。
        // v4 的 song_info 按标题存、只用了一个版本，直接换掉，封面会在下次播放时重新保存
        if (oldVersion < 5) {
            for (t in Table.entries) db.execSQL("ALTER TABLE ${t.sqlName} ADD COLUMN media_id TEXT")
            db.execSQL("DROP TABLE IF EXISTS song_info")
            File(appContext.filesDir, "art").deleteRecursively()
        }
        // v5 → v6（App 1.20）：媒体信息从「只存网易云」改为所有应用，键改为 应用 + ID，并增加专辑。
        // v5 的 song_meta 都是网易云的，原样搬过去（封面文件名不变）
        createMediaMeta(db)
        if (oldVersion == 5) {
            db.execSQL(
                "INSERT OR IGNORE INTO media_meta (pkg, media_id, title, artist, duration_ms, art_file) " +
                        "SELECT ?, media_id, title, artist, duration_ms, art_file FROM song_meta",
                arrayOf(Songs.SOURCE_PKG)
            )
        }
        db.execSQL("DROP TABLE IF EXISTS song_meta")
        if (oldVersion < 7) {
            for (t in Table.entries) {
                db.execSQL("ALTER TABLE ${t.sqlName} ADD COLUMN attribution TEXT NOT NULL DEFAULT 'legacy'")
                db.execSQL("ALTER TABLE ${t.sqlName} ADD COLUMN original_info TEXT")
            }
        }
    }

    /** 听书合并：把某个播放标题归到另一本书（书名即目标书的标题） */
    private fun createBookAlias(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS book_alias (title TEXT PRIMARY KEY, book TEXT NOT NULL)")
    }

    fun bookAliases(): Map<String, String> {
        val map = HashMap<String, String>()
        readableDatabase.rawQuery("SELECT title, book FROM book_alias", null).use { c ->
            while (c.moveToNext()) map[c.getString(0)] = c.getString(1)
        }
        return map
    }

    fun setBookAliases(titles: Collection<String>, book: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (t in titles) {
                val v = ContentValues().apply {
                    put("title", t)
                    put("book", book)
                }
                db.insertWithOnConflict("book_alias", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun removeBookAlias(title: String) {
        writableDatabase.delete("book_alias", "title = ?", arrayOf(title))
    }

    private fun createMediaMeta(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS media_meta (pkg TEXT NOT NULL, media_id TEXT NOT NULL, title TEXT, " +
                    "artist TEXT, album TEXT, duration_ms INTEGER, art_file TEXT, PRIMARY KEY (pkg, media_id))"
        )
    }

    /** 歌名、歌手、专辑以最新一次为准；时长和封面只在拿到时覆盖，避免某次缺失把已有的清掉 */
    fun saveMediaMeta(
        pkg: String, mediaId: String, title: String?, artist: String?, album: String?,
        durationMs: Long?, artFile: String?,
    ) {
        val db = writableDatabase
        val key = arrayOf(pkg, mediaId)
        db.execSQL("INSERT OR IGNORE INTO media_meta (pkg, media_id) VALUES (?, ?)", key)
        db.execSQL(
            "UPDATE media_meta SET title = ?, artist = ?, album = ? WHERE pkg = ? AND media_id = ?",
            arrayOf(title, artist, album, pkg, mediaId)
        )
        if (durationMs != null) {
            db.execSQL("UPDATE media_meta SET duration_ms = ? WHERE pkg = ? AND media_id = ?", arrayOf(durationMs, pkg, mediaId))
        }
        if (artFile != null) {
            db.execSQL("UPDATE media_meta SET art_file = ? WHERE pkg = ? AND media_id = ?", arrayOf(artFile, pkg, mediaId))
        }
    }

    fun mediaArtFile(pkg: String, mediaId: String): String? =
        readableDatabase.rawQuery(
            "SELECT art_file FROM media_meta WHERE pkg = ? AND media_id = ?", arrayOf(pkg, mediaId)
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /** 某个应用的全部媒体信息：媒体 ID → 信息 */
    fun mediaMetas(pkg: String): Map<String, SongMeta> {
        val map = HashMap<String, SongMeta>()
        readableDatabase.rawQuery(
            "SELECT pkg, media_id, title, artist, album, duration_ms, art_file FROM media_meta WHERE pkg = ?",
            arrayOf(pkg)
        ).use { c ->
            while (c.moveToNext()) {
                map[c.getString(1)] = SongMeta(
                    c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5), c.getString(6)
                )
            }
        }
        return map
    }

    /** 给还没有媒体 ID 的旧记录补上 ID（按同一应用、同标题同歌手匹配） */
    fun linkMediaId(pkg: String, title: String, artist: String?, mediaId: String) {
        writableDatabase.execSQL(
            "UPDATE segments SET media_id = ? WHERE pkg = ? AND media_id IS NULL AND title = ? AND IFNULL(artist, '') = ?",
            arrayOf(mediaId, pkg, title, artist.orEmpty())
        )
    }

    /** 书的备注，以书名为键 */
    private fun createBookNote(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS book_note (book TEXT PRIMARY KEY, note TEXT NOT NULL)")
    }

    fun bookNotes(): Map<String, String> {
        val map = HashMap<String, String>()
        readableDatabase.rawQuery("SELECT book, note FROM book_note", null).use { c ->
            while (c.moveToNext()) map[c.getString(0)] = c.getString(1)
        }
        return map
    }

    /** 备注为空时删除该行 */
    fun setBookNote(book: String, note: String?) {
        if (note.isNullOrBlank()) {
            writableDatabase.delete("book_note", "book = ?", arrayOf(book))
        } else {
            val v = ContentValues().apply {
                put("book", book)
                put("note", note.trim())
            }
            writableDatabase.insertWithOnConflict("book_note", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private val segmentColumns = "id, pkg, title, artist, start_ms, end_ms, output, media_id, attribution, original_info"

    private fun Cursor.toSegment() = Segment(
        id = getLong(0),
        pkg = getString(1),
        title = getString(2),
        artist = getString(3),
        startMs = getLong(4),
        endMs = getLong(5),
        output = getString(6),
        mediaId = getString(7),
        attribution = getString(8),
        originalInfo = getString(9),
    )

    fun segment(id: Long): Segment? =
        readableDatabase.rawQuery(
            "SELECT $segmentColumns FROM segments WHERE id = ?", arrayOf(id.toString())
        ).use { if (it.moveToFirst()) it.toSegment() else null }

    /** 只更正归属，不新增时长；第一次更正前的信息随备份和表格保留。 */
    fun archiveSegment(id: Long, bookName: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = segment(id) ?: return false
            require(Books.load(this).any { it.name == bookName }) { "目标小说已不存在，请重新选择" }
            val original = current.originalInfo ?: JSONObject().apply {
                put("pkg", current.pkg)
                put("title", current.title ?: JSONObject.NULL)
                put("artist", current.artist ?: JSONObject.NULL)
                put("media_id", current.mediaId ?: JSONObject.NULL)
                put("attribution", current.attribution)
            }.toString()
            val values = ContentValues().apply {
                put("pkg", Books.SOURCE_PKG)
                put("title", bookName)
                putNull("artist")
                putNull("media_id")
                put("attribution", Attribution.MANUAL)
                put("original_info", original)
            }
            val changed = db.update("segments", values, "id = ?", arrayOf(id.toString())) == 1
            db.setTransactionSuccessful()
            return changed
        } finally {
            db.endTransaction()
        }
    }

    fun segmentsOf(pkg: String): List<Segment> {
        val result = ArrayList<Segment>()
        readableDatabase.rawQuery(
            "SELECT $segmentColumns FROM segments WHERE pkg = ? ORDER BY start_ms",
            arrayOf(pkg)
        ).use { c ->
            while (c.moveToNext()) result += c.toSegment()
        }
        return result
    }

    /** 开始一段记录，end 先等于 start，之后由 checkpoint / close 推进 */
    fun open(
        table: Table, pkg: String, title: String?, artist: String?, startMs: Long, output: String,
        mediaId: String? = null,
        attribution: String = Attribution.REPORTED,
    ): Long {
        val v = ContentValues().apply {
            put("pkg", pkg)
            put("title", title)
            put("artist", artist)
            put("start_ms", startMs)
            put("end_ms", startMs)
            put("output", output)
            put("media_id", mediaId)
            put("attribution", attribution)
        }
        return writableDatabase.insert(table.sqlName, null, v)
    }

    fun updateEnd(table: Table, id: Long, endMs: Long) {
        val v = ContentValues().apply { put("end_ms", endMs) }
        writableDatabase.update(table.sqlName, v, "id = ?", arrayOf(id.toString()))
    }

    fun markOutputChange(id: Long, output: String) {
        writableDatabase.execSQL(
            "UPDATE segments SET output = CASE WHEN output = ? THEN output ELSE 'mixed' END WHERE id = ?",
            arrayOf(output, id)
        )
    }

    fun updateMeta(table: Table, id: Long, title: String?, artist: String?, mediaId: String?) {
        val v = ContentValues().apply {
            put("title", title)
            put("artist", artist)
            put("media_id", mediaId)
        }
        writableDatabase.update(
            table.sqlName, v, "id = ? AND attribution != ?",
            arrayOf(id.toString(), Attribution.MANUAL)
        )
    }

    /** Apply destructive short-record filtering only at close, never to checkpointed or imported data. */
    fun close(
        table: Table, id: Long, startMs: Long, endMs: Long, filterShortRecord: Boolean = true,
    ): Boolean {
        val duration = endMs - startMs
        if (table == Table.SEGMENTS && duration > 0) {
            updateEnd(table, id, endMs)
            return !filterShortRecord || filterFinishedSegments(listOf(id), duration)
        }
        val threshold = if (table == Table.ACTIVITY) MIN_SEGMENT_MS else 0L
        val keep = duration > 0 && duration >= threshold
        if (!keep) {
            writableDatabase.delete(table.sqlName, "id = ?", arrayOf(id.toString()))
            PlaybackDebugLog.get(appContext).record("short_record_deleted", JSONObject()
                .put("table", table.sqlName).put("id", id).put("duration_ms", duration)
                .put("threshold_ms", threshold))
        } else {
            updateEnd(table, id, endMs)
        }
        return keep
    }

    /** Output-device splits belong to one playback; do not filter them independently. */
    internal fun filterFinishedSegments(ids: List<Long>, durationMs: Long): Boolean {
        if (durationMs > 0 && durationMs >= settings.shortRecordThresholdMs) return true
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.delete(Table.SEGMENTS.sqlName, "id = ?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        PlaybackDebugLog.get(appContext).record("short_record_deleted", JSONObject()
            .put("table", Table.SEGMENTS.sqlName).put("ids", org.json.JSONArray(ids))
            .put("duration_ms", durationMs).put("threshold_ms", settings.shortRecordThresholdMs))
        return false
    }

    /** 查询与 [fromMs, toMs) 有交集的记录 */
    fun overlapping(table: Table, fromMs: Long, toMs: Long): List<Segment> {
        val result = ArrayList<Segment>()
        readableDatabase.rawQuery(
            "SELECT $segmentColumns FROM ${table.sqlName} WHERE start_ms < ? AND end_ms > ? ORDER BY start_ms",
            arrayOf(toMs.toString(), fromMs.toString())
        ).use { c ->
            while (c.moveToNext()) result += c.toSegment()
        }
        return result
    }

    fun allSegments(): List<Segment> = overlapping(Table.SEGMENTS, Long.MIN_VALUE, Long.MAX_VALUE)

    data class ImportResult(val inserted: Int, val updated: Int, val unchanged: Int)

    /** 匹配记录原地覆盖；归档改过应用时，优先按保留的原应用和开始时间定位。 */
    fun importSegments(segments: List<Segment>): ImportResult {
        val db = writableDatabase
        var inserted = 0
        var updated = 0
        var unchanged = 0
        db.beginTransaction()
        try {
            for (s in segments) {
                val originalPkg = s.originalInfo?.let {
                    runCatching { JSONObject(it).optString("pkg").takeIf(String::isNotBlank) }.getOrNull()
                }
                var current: Segment? = null
                for (pkg in listOfNotNull(originalPkg, s.pkg).distinct()) {
                    val matches = db.rawQuery(
                        "SELECT $segmentColumns FROM segments WHERE pkg = ? AND start_ms = ?",
                        arrayOf(pkg, s.startMs.toString()),
                    ).use { c -> buildList { while (c.moveToNext()) add(c.toSegment()) } }
                    require(matches.size <= 1) { "同一应用存在多条相同开始时间的记录，无法安全覆盖" }
                    if (matches.isNotEmpty()) {
                        current = matches.single()
                        break
                    }
                }
                if (current != null && current.copy(id = 0) == s.copy(id = 0)) {
                    unchanged++
                    continue
                }
                val v = ContentValues().apply {
                    put("pkg", s.pkg)
                    put("title", s.title)
                    put("artist", s.artist)
                    put("start_ms", s.startMs)
                    put("end_ms", s.endMs)
                    put("output", s.output)
                    put("media_id", s.mediaId)
                    put("attribution", s.attribution)
                    put("original_info", s.originalInfo)
                }
                if (current == null) {
                    db.insertOrThrow(Table.SEGMENTS.sqlName, null, v)
                    inserted++
                } else {
                    check(db.update(Table.SEGMENTS.sqlName, v, "id = ?", arrayOf(current.id.toString())) == 1)
                    updated++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ImportResult(inserted, updated, unchanged)
    }

    companion object {
        const val MIN_SEGMENT_MS = 1_000L

        @Volatile
        private var instance: AudioDb? = null

        fun get(context: Context): AudioDb =
            instance ?: synchronized(this) {
                instance ?: AudioDb(context).also { instance = it }
            }
    }
}
