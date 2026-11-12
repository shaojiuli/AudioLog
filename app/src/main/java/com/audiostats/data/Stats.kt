package com.audiostats.data

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Span { DAY, WEEK, MONTH, YEAR }

data class AppUsage(val pkg: String, val totalMs: Long, val segments: Int)

/**
 * 图表的一根柱子。
 * 日视图按小时，[capacityMs] 为 1 小时，柱高 = 这一小时里播放的比例；
 * 其他视图按天/月，柱高按时长。[drillSpan]/[drillDate] 是点击后跳转到的视图。
 * [parts] 是堆叠的各段：未选应用时按应用分，选中 QQ 浏览器时按书分。
 */
data class Bucket(
    val label: String,
    val ms: Long,
    val capacityMs: Long,
    val drillSpan: Span?,
    val drillDate: LocalDate?,
    val parts: Map<String, Long>,
)

data class PeriodStats(
    val span: Span,
    val anchor: LocalDate,
    /** 图表对应的总时长：未选应用时为所有 App 的并集（同时播放只算一次），选中时为该应用的时长 */
    val totalMs: Long,
    val apps: List<AppUsage>,
    val buckets: List<Bucket>,
    /** 图表堆叠用的分组及其总时长，按时长降序；分组键是包名或书名 */
    val parts: List<Pair<String, Long>>,
    /** 分组键是否为书名（选中了 QQ 浏览器） */
    val partsAreBooks: Boolean,
    val tracks: List<Segment>,
)

object Stats {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private fun startOf(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** 视图覆盖的日期范围 [first, last]（含） */
    fun dateRange(span: Span, anchor: LocalDate): Pair<LocalDate, LocalDate> = when (span) {
        Span.DAY -> anchor to anchor
        Span.WEEK -> anchor.minusDays(6) to anchor
        Span.MONTH -> anchor.withDayOfMonth(1) to anchor.withDayOfMonth(anchor.lengthOfMonth())
        Span.YEAR -> anchor.withDayOfYear(1) to anchor.withDayOfYear(anchor.lengthOfYear())
    }

    fun shift(span: Span, anchor: LocalDate, delta: Long): LocalDate = when (span) {
        Span.DAY -> anchor.plusDays(delta)
        Span.WEEK -> anchor.plusDays(7 * delta)
        Span.MONTH -> anchor.plusMonths(delta)
        Span.YEAR -> anchor.plusYears(delta)
    }

    fun label(span: Span, anchor: LocalDate): String {
        val today = LocalDate.now()
        val (first, last) = dateRange(span, anchor)
        return when (span) {
            Span.DAY -> when (anchor) {
                today -> "今天"
                today.minusDays(1) -> "昨天"
                else -> anchor.format(DateTimeFormatter.ofPattern("M月d日 EEE", Locale.CHINA))
            }
            Span.WEEK -> if (last == today) "近 7 天" else
                "${first.format(DateTimeFormatter.ofPattern("M月d日"))} – ${last.format(DateTimeFormatter.ofPattern("M月d日"))}"
            Span.MONTH -> anchor.format(DateTimeFormatter.ofPattern("yyyy年M月"))
            Span.YEAR -> "${anchor.year}年"
        }
    }

    /** [focusPkg] 不为空时，图表和总时长只统计这个应用 */
    fun period(db: AudioDb, span: Span, anchor: LocalDate, focusPkg: String? = null): PeriodStats {
        val (first, last) = dateRange(span, anchor)
        val from = startOf(first)
        val to = startOf(last.plusDays(1))
        val tracks = db.overlapping(Table.SEGMENTS, from, to)
        val clipped = tracks.map { it.clip(from, to) }

        val apps = clipped.groupBy { it.pkg }
            .map { (pkg, list) -> AppUsage(pkg, length(union(list.map { it.startMs to it.endMs })), list.size) }
            .sortedByDescending { it.totalMs }

        val chartSegs = if (focusPkg == null) clipped else clipped.filter { it.pkg == focusPkg }
        val byBook = focusPkg == Books.SOURCE_PKG
        val aliases = if (byBook) db.bookAliases() else emptyMap()
        val keyOf: (Segment) -> String = when {
            focusPkg == null -> { s -> s.pkg }
            byBook -> { s -> s.title?.let { aliases[it] ?: it } ?: NO_TITLE }
            else -> { s -> s.pkg }
        }
        val chartUnion = union(chartSegs.map { it.startMs to it.endMs })
        val partUnions = chartSegs.groupBy(keyOf).mapValues { (_, l) -> union(l.map { it.startMs to it.endMs }) }

        return PeriodStats(
            span = span,
            anchor = anchor,
            totalMs = length(chartUnion),
            apps = apps,
            buckets = buckets(span, first, last, chartUnion, partUnions),
            parts = partUnions.map { (k, u) -> k to length(u) }.sortedByDescending { it.second },
            partsAreBooks = byBook,
            tracks = tracks.sortedByDescending { it.startMs },
        )
    }

    const val NO_TITLE = "（无标题）"

    private fun buckets(
        span: Span,
        first: LocalDate,
        last: LocalDate,
        union: List<Pair<Long, Long>>,
        partUnions: Map<String, List<Pair<Long, Long>>>,
    ): List<Bucket> {
        fun sumIn(u: List<Pair<Long, Long>>, s: Long, e: Long) =
            u.sumOf { (a, b) -> (minOf(b, e) - maxOf(a, s)).coerceAtLeast(0) }

        fun bucket(label: String, s: Long, e: Long, capacity: Long, drillSpan: Span?, drillDate: LocalDate?): Bucket {
            val ms = sumIn(union, s, e)
            var parts = partUnions.mapValues { (_, u) -> sumIn(u, s, e) }.filterValues { it > 0 }
            // 多个应用同时播放时各段之和会超过并集，按比例缩到柱高
            val sum = parts.values.sum()
            if (sum > ms && sum > 0) parts = parts.mapValues { it.value * ms / sum }
            return Bucket(label, ms, capacity, drillSpan, drillDate, parts)
        }

        return when (span) {
            Span.DAY -> {
                val dayStart = first.atStartOfDay(zone)
                (0 until 24).map { h ->
                    // 用带时区的时间推进，兼容夏令时
                    val s = dayStart.plusHours(h.toLong()).toInstant().toEpochMilli()
                    val e = dayStart.plusHours(h + 1L).toInstant().toEpochMilli()
                    bucket("$h", s, e, e - s, null, null)
                }
            }
            Span.WEEK, Span.MONTH -> generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.map { d ->
                val label = if (span == Span.WEEK)
                    d.format(DateTimeFormatter.ofPattern("EEE", Locale.CHINA)).removePrefix("周")
                else d.dayOfMonth.toString()
                bucket(label, startOf(d), startOf(d.plusDays(1)), DAY_MS, Span.DAY, d)
            }.toList()
            Span.YEAR -> (1..12).map { m ->
                val d = first.withMonth(m)
                bucket("$m", startOf(d), startOf(d.plusMonths(1)), d.lengthOfMonth() * DAY_MS, Span.MONTH, d)
            }
        }
    }

    private const val DAY_MS = 86_400_000L

    private fun Segment.clip(from: Long, to: Long) =
        copy(startMs = maxOf(startMs, from), endMs = minOf(endMs, to))

    private fun length(intervals: List<Pair<Long, Long>>) = intervals.sumOf { it.second - it.first }

    /** 合并重叠区间 */
    fun union(intervals: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        if (intervals.isEmpty()) return emptyList()
        val sorted = intervals.filter { it.second > it.first }.sortedBy { it.first }
        val out = ArrayList<Pair<Long, Long>>()
        for (iv in sorted) {
            val last = out.lastOrNull()
            if (last != null && iv.first <= last.second) {
                out[out.size - 1] = last.first to maxOf(last.second, iv.second)
            } else {
                out += iv
            }
        }
        return out
    }
}

fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h}小时${m}分"
        m > 0 -> "${m}分钟"
        else -> "${s}秒"
    }
}
