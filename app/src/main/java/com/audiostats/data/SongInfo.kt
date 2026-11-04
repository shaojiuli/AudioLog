package com.audiostats.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * 媒体 ID 在同一应用内唯一，凡是提供 ID 的应用（网易云、B 站等）都以「应用 + ID」为键
 * 存一份歌名/歌手/专辑/单曲时长/封面，播放记录里只记 ID，统计时再关联。
 * 封面缩成 144px 的 JPEG（每张几 KB），存在 App 私有目录 files/art/<应用>_<ID>.jpg。
 */
object SongInfoStore {
    private const val ART_SIZE = 144
    private val io = Executors.newSingleThreadExecutor()

    /** 只存信息、不存封面的应用：B 站的是视频封面，数量多且对统计没用 */
    private val NO_ART_PKGS = setOf("tv.danmaku.bili")

    fun artDir(context: Context) = File(context.filesDir, "art").apply { mkdirs() }

    fun save(context: Context, pkg: String, mediaId: String?, title: String, artist: String?, metadata: MediaMetadata) {
        if (mediaId.isNullOrBlank()) return
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim()?.ifEmpty { null }
        // 元数据里带了图才存封面；排除名单里的应用不存
        val art = if (pkg in NO_ART_PKGS) null else metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        // 缩图在主线程做（元数据里的 Bitmap 之后可能被回收），压缩写盘放到后台
        val thumb = art?.let { Bitmap.createScaledBitmap(it, ART_SIZE, ART_SIZE, true) }
        val app = context.applicationContext
        io.execute {
            val db = AudioDb.get(app)
            val fileName = thumb?.let { writeArt(app, "${pkg}_$mediaId", it) }
            // 文件名规则变过（v1.19 只用 ID），换了新文件就删掉旧的，免得留下没人引用的封面
            val old = db.mediaArtFile(pkg, mediaId)
            if (fileName != null && old != null && old != fileName) File(artDir(app), old).delete()
            db.saveMediaMeta(pkg, mediaId, title, artist, album, duration.takeIf { it > 0 }, fileName)
            db.linkMediaId(pkg, title, artist, mediaId)
        }
    }

    /**
     * 内容没变就不重写；有的播放器先给占位图、稍后才给真封面，内容变了会覆盖。
     * 先写临时文件再改名，避免写到一半被读到损坏的图。
     */
    private fun writeArt(context: Context, key: String, bmp: Bitmap): String? {
        val name = "${key.map { if (it.isLetterOrDigit() || it == '.' || it == '_') it else '_' }.joinToString("")}.jpg"
        val bytes = ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
        val file = File(artDir(context), name)
        if (file.exists() && file.length() == bytes.size.toLong() && file.readBytes().contentEquals(bytes)) return name
        return runCatching {
            val tmp = File(file.parentFile, "$name.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            name
        }.getOrNull()
    }
}
