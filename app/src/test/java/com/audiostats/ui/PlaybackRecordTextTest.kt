package com.audiostats.ui

import com.audiostats.data.Attribution
import com.audiostats.data.Books
import com.audiostats.data.Segment
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class PlaybackRecordTextTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val start = ZonedDateTime.of(2026, 11, 12, 18, 10, 0, 0, zone).toInstant().toEpochMilli()
    private val novel = Segment(1, Books.SOURCE_PKG, "Novel", "未知艺术家", start, start + 60_000, "bluetooth")

    @Test fun qqRecordsAlwaysShowOnlyTheTitleWithoutChangingTheStoredArtist() {
        assertEquals("Novel", playbackRecordTitle(novel))
        assertEquals("Novel", playbackRecordTitle(novel.copy(artist = "Another artist")))
        assertEquals("未知艺术家", novel.artist)
        assertEquals("（无标题）", playbackRecordTitle(novel.copy(title = null)))
    }

    @Test fun otherApplicationsKeepTheirArtistAndPendingAudioKeepsNeutralTitle() {
        assertEquals("Novel — Artist", playbackRecordTitle(novel.copy(pkg = "music", artist = "Artist")))
        assertEquals("待确认音频", playbackRecordTitle(novel.copy(pkg = Attribution.UNKNOWN_PKG)))
    }

    @Test fun timeDurationOutputAndArchiveStatusAreOneTextLine() {
        val text = playbackRecordText(novel.copy(attribution = Attribution.MANUAL), false, true, zone)
        assertEquals("18:10–18:11 · 1分钟 · 蓝牙 · 已归档", text)
        assertFalse(text.contains('\n'))
        assertEquals("18:10–18:11 · 1分钟", playbackRecordText(novel, false, false, zone))
    }

    @Test fun crossDayRangeRetainsBothDatesInTheSameLine() {
        val segment = novel.copy(startMs = start + 5 * 3_600_000, endMs = start + 7 * 3_600_000)
        assertEquals("11-12 23:10–11-13 01:10 · 2小时0分 · 蓝牙",
            playbackRecordText(segment, false, true, zone))
    }

    @Test fun minuteAndSecondInputsAreCombinedWithoutOverflowOrInvalidSeconds() {
        assertEquals(5, parseFilterDuration("0", "5"))
        assertEquals(65, parseFilterDuration("1", "5"))
        assertEquals(120, parseFilterDuration("2", ""))
        assertEquals(5, parseFilterDuration("", "5"))
        assertEquals(Int.MAX_VALUE, parseFilterDuration("35791394", "7"))
        listOf("0" to "0", "1" to "60", "-1" to "5", "1.5" to "0",
            "35791394" to "59", "999999999999999999999" to "0").forEach { (m, s) ->
            assertNull(parseFilterDuration(m, s))
        }
    }
}
