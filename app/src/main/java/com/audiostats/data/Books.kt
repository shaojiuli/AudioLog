package com.audiostats.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** 一段连续收听的实际起止时间；短暂的设备切换间隙不计入收听时长。 */
data class ListenRange(val start: LocalDateTime, val end: LocalDateTime, val ms: Long)

/**
 * 听书记录：QQ 浏览器里同一个播放标题算一本书。
 * 同一本书可能有不同标题，用 book_alias 表把标题归到目标书名下（不改原始记录，可随时拆分）。
 */
data class Book(
    /** 书名：主标题 */
    val name: String,
    /** 归到这本书的全部原始标题，主标题排第一 */
    val titles: List<String>,
    val totalMs: Long,
    val lastMs: Long,
    val sessionCount: Int,
    /** 按连续播放合并的收听记录，最新的在前 */
    val ranges: List<ListenRange>,
    val note: String?,
)

object Books {
    const val SOURCE_PKG = "com.tencent.mtt"

    fun load(db: AudioDb): List<Book> {
        val alias = db.bookAliases()
        val notes = db.bookNotes()
        return db.segmentsOf(SOURCE_PKG)
            .filter { !it.title.isNullOrBlank() }
            .groupBy { alias[it.title] ?: it.title!! }
            .map { (name, segs) ->
                val titles = segs.map { it.title!! }.distinct().sortedBy { it != name }
                val listening = ranges(segs)
                Book(
                    name = name,
                    titles = titles,
                    totalMs = listening.sumOf { it.ms },
                    lastMs = segs.maxOf { it.endMs },
                    sessionCount = listening.size,
                    ranges = listening,
                    note = notes[name],
                )
            }
    }

    /** 合并重叠区间和设备切换产生的不足一秒间隙，不再按整天或连续日期折叠。 */
    private fun ranges(segs: List<Segment>): List<ListenRange> {
        val zone = ZoneId.systemDefault()
        fun local(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()
        val out = ArrayList<ListenRange>()
        var lastEnd = Long.MIN_VALUE
        for ((start, end) in Stats.union(segs.map { it.startMs to it.endMs })) {
            val last = out.lastOrNull()
            if (last != null && start - lastEnd <= 1_000L) {
                out[out.size - 1] = last.copy(end = local(end), ms = last.ms + end - start)
            } else {
                out += ListenRange(local(start), local(end), end - start)
            }
            lastEnd = end
        }
        return out.reversed()
    }

    /** Display only: adjacent calendar days share a row, but gaps add no listening time. */
    fun displayRanges(ranges: List<ListenRange>): List<ListenRange> {
        val out = ArrayList<ListenRange>()
        for (range in ranges.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && !range.start.toLocalDate().isAfter(last.end.toLocalDate().plusDays(1))) {
                out[out.lastIndex] = last.copy(end = maxOf(last.end, range.end), ms = last.ms + range.ms)
            } else {
                out += range
            }
        }
        return out.reversed()
    }

    /** 把 [source] 的全部标题并入 [target]；目标没有备注时沿用来源的备注 */
    fun merge(db: AudioDb, source: Book, target: Book) {
        db.setBookAliases(source.titles, target.name)
        if (target.note.isNullOrBlank() && !source.note.isNullOrBlank()) {
            db.setBookNote(target.name, source.note)
        }
    }

    /** 把某个标题从所在的书里拆出来，恢复成独立的一本 */
    fun split(db: AudioDb, title: String) {
        db.removeBookAlias(title)
    }

    fun setNote(db: AudioDb, book: String, note: String?) = db.setBookNote(book, note)
}
