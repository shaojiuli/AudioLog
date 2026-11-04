package com.audiostats.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 一段连续收听的日期（单日时 start == end）及这些天的收听总时长 */
data class ListenRange(val start: LocalDate, val end: LocalDate, val ms: Long)

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
    /** 按连续日期合并的收听记录，最新的在前 */
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
                Book(
                    name = name,
                    titles = titles,
                    totalMs = segs.sumOf { it.endMs - it.startMs },
                    lastMs = segs.maxOf { it.endMs },
                    sessionCount = segs.size,
                    ranges = ranges(segs),
                    note = notes[name],
                )
            }
    }

    /** 按开始日期汇总每天的收听时长，再把相邻的日期合并成一段 */
    private fun ranges(segs: List<Segment>): List<ListenRange> {
        val zone = ZoneId.systemDefault()
        val daily = segs.groupBy { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() }
            .mapValues { (_, l) -> l.sumOf { it.endMs - it.startMs } }
            .toSortedMap()
        val out = ArrayList<ListenRange>()
        for ((day, ms) in daily) {
            val last = out.lastOrNull()
            if (last != null && last.end.plusDays(1) == day) {
                out[out.size - 1] = last.copy(end = day, ms = last.ms + ms)
            } else {
                out += ListenRange(day, day, ms)
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
