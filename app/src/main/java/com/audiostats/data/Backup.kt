package com.audiostats.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 完整备份：一个 zip，里面是数据库文件和封面图片（art/ 目录）。
 * 导入时把备份合并进当前数据（重复的跳过），不会覆盖或删除现有记录。
 */
object Backup {
    private const val DB_ENTRY = "audio_stats.db"
    private const val ART_PREFIX = "art/"

    data class ImportResult(val segments: Int, val duplicates: Int, val songs: Int, val covers: Int)

    fun export(context: Context, out: OutputStream) {
        val db = AudioDb.get(context)
        // 把 WAL 里的内容写回主文件，复制出来的才是完整的数据库
        db.writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        val dbFile = context.getDatabasePath(DB_ENTRY)
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(DB_ENTRY))
            dbFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            SongInfoStore.artDir(context).listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") }?.forEach { f ->
                zip.putNextEntry(ZipEntry(ART_PREFIX + f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun import(context: Context, input: InputStream): ImportResult {
        val work = File(context.cacheDir, "import").apply { deleteRecursively(); mkdirs() }
        try {
            val artIn = File(work, "art").apply { mkdirs() }
            var dbFile: File? = null
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    // 只认这两类文件，并拒绝带路径的名字，防止写到目录外
                    val name = e.name
                    val target = when {
                        name == DB_ENTRY -> File(work, DB_ENTRY).also { dbFile = it }
                        name.startsWith(ART_PREFIX) && !name.contains("..") && name.endsWith(".jpg") ->
                            File(artIn, name.removePrefix(ART_PREFIX).substringAfterLast('/'))
                        else -> null
                    }
                    target?.outputStream()?.use { zip.copyTo(it) }
                }
            }
            val src = dbFile ?: error("备份里没有数据库文件")
            return merge(context, src, artIn)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun merge(context: Context, srcFile: File, artIn: File): ImportResult {
        val src = SQLiteDatabase.openDatabase(srcFile.path, null, SQLiteDatabase.OPEN_READONLY)
        val audioDb = AudioDb.get(context)
        try {
            fun hasTable(t: String) = src.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(t)
            ).use { it.moveToFirst() }
            fun hasColumn(t: String, c: String) = src.rawQuery("PRAGMA table_info($t)", null).use { cur ->
                var found = false
                while (cur.moveToNext()) if (cur.getString(1) == c) found = true
                found
            }

            // 播放记录：旧版本的备份可能没有 media_id 列
            val mediaCol = if (hasColumn("segments", "media_id")) "media_id" else "NULL"
            val segments = ArrayList<Segment>()
            src.rawQuery("SELECT pkg, title, artist, start_ms, end_ms, output, $mediaCol FROM segments", null).use { c ->
                while (c.moveToNext()) {
                    segments += Segment(0, c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getString(5), c.getString(6))
                }
            }
            val inserted = audioDb.importSegments(segments)

            val dst = audioDb.writableDatabase
            var songs = 0
            var covers = 0
            dst.beginTransaction()
            try {
                if (hasTable("media_meta")) {
                    val artDir = SongInfoStore.artDir(context)
                    src.rawQuery("SELECT pkg, media_id, title, artist, album, duration_ms, art_file FROM media_meta", null).use { c ->
                        while (c.moveToNext()) {
                            val art = c.getString(6)
                            // 封面文件已有同名的就不覆盖
                            val artOk = art != null && File(artIn, art).let { f ->
                                f.exists() && (File(artDir, art).exists() || f.copyTo(File(artDir, art)).exists().also { covers++ })
                            }
                            val v = ContentValues().apply {
                                put("pkg", c.getString(0))
                                put("media_id", c.getString(1))
                                put("title", c.getString(2))
                                put("artist", c.getString(3))
                                put("album", c.getString(4))
                                put("duration_ms", c.getLong(5))
                                put("art_file", if (artOk) art else null)
                            }
                            if (dst.insertWithOnConflict("media_meta", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L) songs++
                        }
                    }
                }
                if (hasTable("book_alias")) {
                    src.rawQuery("SELECT title, book FROM book_alias", null).use { c ->
                        while (c.moveToNext()) {
                            val v = ContentValues().apply { put("title", c.getString(0)); put("book", c.getString(1)) }
                            dst.insertWithOnConflict("book_alias", null, v, SQLiteDatabase.CONFLICT_IGNORE)
                        }
                    }
                }
                if (hasTable("book_note")) {
                    src.rawQuery("SELECT book, note FROM book_note", null).use { c ->
                        while (c.moveToNext()) {
                            val v = ContentValues().apply { put("book", c.getString(0)); put("note", c.getString(1)) }
                            dst.insertWithOnConflict("book_note", null, v, SQLiteDatabase.CONFLICT_IGNORE)
                        }
                    }
                }
                dst.setTransactionSuccessful()
            } finally {
                dst.endTransaction()
            }
            return ImportResult(inserted, segments.size - inserted, songs, covers)
        } finally {
            src.close()
        }
    }
}
