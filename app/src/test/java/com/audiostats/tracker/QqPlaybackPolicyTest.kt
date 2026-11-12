package com.audiostats.tracker

import com.audiostats.data.Attribution
import com.audiostats.data.Books
import org.junit.Assert.*
import org.junit.Test

class QqPlaybackPolicyTest {
    private val qq = PlaybackCandidate(Books.SOURCE_PKG, false, "Novel", true, false)
    private val netease = qq.copy(pkg = "com.netease.cloudmusic", title = "Stale song")

    @Test fun qqNeedsOnlyTitleNotArtistOrMediaId() {
        assertEquals(0, QqPlaybackPolicy.select(true, listOf(qq)))
    }

    @Test fun staleNeteaseTitleNeverStartsCompatibilityRecording() {
        assertNull(QqPlaybackPolicy.select(true, listOf(netease)))
    }

    @Test fun pausedAndStoppedSessionsDoNotCompeteWithQq() {
        assertEquals(1, QqPlaybackPolicy.select(true, listOf(netease.copy(stateKnown = true), qq)))
    }

    @Test fun silenceOrMissingTitleDoesNotStartQq() {
        assertNull(QqPlaybackPolicy.select(false, listOf(qq)))
        assertNull(QqPlaybackPolicy.select(true, listOf(qq.copy(title = null))))
        assertNull(QqPlaybackPolicy.select(true, listOf(qq.copy(title = "  "))))
    }

    @Test fun anotherExplicitlyPlayingApplicationTakesPriority() {
        assertNull(QqPlaybackPolicy.select(true, listOf(qq, netease.copy(stateKnown = true, reportedPlaying = true))))
    }

    @Test fun ambiguousUnknownSessionsRemainUnconfirmed() {
        assertNull(QqPlaybackPolicy.select(true, listOf(qq, netease)))
        assertNull(QqPlaybackPolicy.select(true, listOf(qq, qq.copy(title = "Another novel"))))
        assertNull(QqPlaybackPolicy.select(true, listOf(qq, netease.copy(title = null))))
    }

    @Test fun explicitQqPauseOrRemotePlaybackDoesNotUseCompatibility() {
        assertNull(QqPlaybackPolicy.select(true, listOf(qq.copy(stateKnown = true))))
        assertNull(QqPlaybackPolicy.select(true, listOf(qq.copy(local = false))))
    }

    @Test fun compatibilityProvenanceSurvivesImportParsing() {
        assertEquals(Attribution.QQ_COMPAT, Attribution.parse("qq_compat"))
        assertEquals(Attribution.LEGACY, Attribution.parse("invalid"))
    }
}
