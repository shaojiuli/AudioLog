package com.audiostats.ui

import com.audiostats.data.Attribution
import com.audiostats.data.Books
import com.audiostats.data.Segment
import org.junit.Assert.*
import org.junit.Test

class TrackGroupingTest {
    private val novel = Segment(
        1, Books.SOURCE_PKG, "Novel", "Unknown artist", 100_000, 160_000, "bluetooth",
        attribution = Attribution.QQ_COMPAT,
    )

    @Test fun adjacentQqNovelsIgnoreArtistAndAttributionWithoutChangingRecords() {
        val tracks = listOf(
            novel,
            novel.copy(id = 2, artist = null, attribution = Attribution.MANUAL, endMs = 120_000),
            novel.copy(id = 3, artist = "Other artist", attribution = Attribution.REPORTED, output = "speaker"),
            novel.copy(id = 4, attribution = Attribution.LEGACY, mediaId = "different"),
        )
        val groups = groupTracks(tracks)
        assertEquals(1, groups.size)
        assertEquals(tracks, groups.single())
        tracks.zip(groups.single()).forEach { (original, grouped) -> assertSame(original, grouped) }
        assertTrue(playbackRecordText(groups.single()[1], false, true).contains("已归档"))
        assertFalse(playbackRecordText(groups.single()[0], false, true).contains("已归档"))
    }

    @Test fun otherContentBetweenSameNovelsPreventsGroupingAcrossIt() {
        val music = novel.copy(id = 2, pkg = "music")
        val tracks = listOf(novel, music, novel.copy(id = 3))
        assertEquals(listOf(listOf(tracks[0]), listOf(tracks[1]), listOf(tracks[2])), groupTracks(tracks))
        assertEquals(3, groupTracks(listOf(novel, novel.copy(title = "Other novel"), novel)).size)
    }

    @Test fun otherApplicationsStillRequireMatchingArtistAndAttribution() {
        val music = novel.copy(pkg = "music")
        val same = music.copy(id = 2)
        val otherArtist = music.copy(id = 3, artist = "Other artist")
        val manual = otherArtist.copy(id = 4, attribution = Attribution.MANUAL)
        assertEquals(listOf(listOf(music, same), listOf(otherArtist), listOf(manual)),
            groupTracks(listOf(music, same, otherArtist, manual)))
    }

    @Test fun pendingAudioAlwaysRemainsSeparate() {
        val pending = novel.copy(pkg = Attribution.UNKNOWN_PKG, attribution = Attribution.UNCONFIRMED)
        assertEquals(2, groupTracks(listOf(pending, pending.copy(id = 2))).size)
    }

    @Test fun qqRecordsWithoutBookTitlesAreNotAssumedToBeTheSameNovel() {
        listOf(null, "", " ").forEach { title ->
            val untitled = novel.copy(title = title)
            assertEquals(2, groupTracks(listOf(untitled, untitled.copy(id = 2))).size)
        }
    }

    @Test fun emptyRecordsProduceNoGroups() {
        assertTrue(groupTracks(emptyList()).isEmpty())
    }
}
