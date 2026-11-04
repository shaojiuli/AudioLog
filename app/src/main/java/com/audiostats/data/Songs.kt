package com.audiostats.data

import android.content.Context
import java.io.File

/**
 * 歌单页：网易云音乐的听歌统计，按歌曲汇总。
 * 有歌曲 ID 的按 ID 归并（同名不同歌不会混在一起），旧记录没有 ID 时按标题 + 歌手归并。
 * 网易云的媒体会话不提供歌单名和播放队列，所以暂时按歌曲统计。
 */
data class Song(
    val key: String,
    val title: String,
    val artist: String?,
    val totalMs: Long,
    val lastMs: Long,
    /** 听的总时长 ÷ 单曲时长；没记到单曲时长时为空 */
    val plays: Double?,
    val artFile: File?,
)

object Songs {
    const val SOURCE_PKG = "com.netease.cloudmusic"

    fun load(context: Context): List<Song> {
        val db = AudioDb.get(context)
        val metas = db.mediaMetas(SOURCE_PKG)
        val artDir = SongInfoStore.artDir(context)
        return db.segmentsOf(SOURCE_PKG)
            .filter { !it.title.isNullOrBlank() }
            .groupBy { it.mediaId?.let { id -> "id:$id" } ?: "t:${it.title}\u0001${it.artist.orEmpty()}" }
            .map { (key, segs) ->
                val total = segs.sumOf { it.endMs - it.startMs }
                val latest = segs.maxBy { it.endMs }
                val meta = latest.mediaId?.let { metas[it] }
                Song(
                    key = key,
                    title = meta?.title ?: latest.title!!,
                    artist = meta?.artist ?: latest.artist,
                    totalMs = total,
                    lastMs = latest.endMs,
                    plays = meta?.durationMs?.takeIf { it > 0 }?.let { total.toDouble() / it },
                    artFile = meta?.artFile?.let { File(artDir, it) }?.takeIf { it.exists() },
                )
            }
    }
}
