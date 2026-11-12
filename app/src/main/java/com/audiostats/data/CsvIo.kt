package com.audiostats.data

import android.content.Context
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 播放记录与 CSV 互转。
 * 导出的表格既给人看（应用名、可读时间、时长），也能原样导回；
 * 导入时按表头取列，用 Excel 编辑过、毫秒时间戳被改成科学计数法也能靠可读时间恢复。
 */
object CsvIo {
    private val HEADER = listOf(
        "app_name", "pkg", "title", "artist", "start_time", "end_time",
        "duration_sec", "output", "start_ms", "end_ms", "media_id", "attribution", "original_info",
    )
    private val TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val PARSE_FMTS = listOf(
        TIME_FMT,
        DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/M/d H:mm"),
        DateTimeFormatter.ofPattern("yyyy-M-d H:mm"),
    )

    data class ImportResult(val inserted: Int, val updated: Int, val duplicates: Int, val invalid: Int)

    fun export(context: Context): String {
        val pm = context.packageManager
        val labels = HashMap<String, String>()
        val zone = ZoneId.systemDefault()
        val sb = StringBuilder(HEADER.joinToString(",")).append('\n')
        for (s in AudioDb.get(context).allSegments()) {
            val label = labels.getOrPut(s.pkg) {
                if (s.pkg == Attribution.UNKNOWN_PKG) "待归档"
                else runCatching { pm.getApplicationLabel(pm.getApplicationInfo(s.pkg, 0)).toString() }.getOrDefault(s.pkg)
            }
            val row = listOf(
                label, s.pkg, s.title, s.artist,
                Instant.ofEpochMilli(s.startMs).atZone(zone).format(TIME_FMT),
                Instant.ofEpochMilli(s.endMs).atZone(zone).format(TIME_FMT),
                ((s.endMs - s.startMs) / 1000).toString(), s.output,
                s.startMs.toString(), s.endMs.toString(), s.mediaId,
                s.attribution, s.originalInfo,
            )
            sb.append(row.joinToString(",") { field(it) }).append('\n')
        }
        return sb.toString()
    }

    fun import(context: Context, text: String): ImportResult {
        val rows = parse(text.removePrefix("﻿"))
        if (rows.isEmpty()) return ImportResult(0, 0, 0, 0)
        val header = rows.first().map { it.trim().lowercase() }
        fun col(row: List<String>, name: String) = header.indexOf(name).takeIf { it >= 0 }?.let { row.getOrNull(it) }

        val segments = ArrayList<Segment>()
        var invalid = 0
        for (row in rows.drop(1)) {
            if (row.all { it.isBlank() }) continue
            val pkg = col(row, "pkg")?.trim()
            val start = time(col(row, "start_ms"), col(row, "start_time"))
            val end = time(col(row, "end_ms"), col(row, "end_time"))
            if (pkg.isNullOrEmpty() || start == null || end == null || end < start) {
                invalid++
                continue
            }
            segments += Segment(
                id = 0,
                pkg = pkg,
                title = col(row, "title")?.ifEmpty { null },
                artist = col(row, "artist")?.ifEmpty { null },
                startMs = start,
                endMs = end,
                output = col(row, "output")?.ifEmpty { null } ?: "speaker",
                mediaId = col(row, "media_id")?.trim()?.ifEmpty { null },
                attribution = Attribution.parse(col(row, "attribution")),
                originalInfo = col(row, "original_info")?.ifEmpty { null },
            )
        }
        val result = AudioDb.get(context).importSegments(segments)
        return ImportResult(result.inserted, result.updated, result.unchanged, invalid)
    }

    private fun time(ms: String?, readable: String?): Long? {
        ms?.trim()?.toLongOrNull()?.takeIf { it > 1_000_000_000_000L }?.let { return it }
        val s = readable?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        for (f in PARSE_FMTS) {
            runCatching {
                return LocalDateTime.parse(s, f).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        }
        return null
    }

    private fun field(v: String?): String {
        if (v == null) return ""
        return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    /** RFC 4180：支持引号内的逗号、换行和 "" 转义 */
    private fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        cur.append('"'); i++
                    } else inQuotes = false
                } else cur.append(c)
            } else when (c) {
                '"' -> inQuotes = true
                ',' -> { row += cur.toString(); cur.clear() }
                '\r' -> Unit
                '\n' -> { row += cur.toString(); cur.clear(); rows += row; row = ArrayList() }
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty() || row.isNotEmpty()) {
            row += cur.toString()
            rows += row
        }
        return rows
    }
}
