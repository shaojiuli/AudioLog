package com.audiostats.tracker

import com.audiostats.data.Books

internal data class PlaybackCandidate(
    val pkg: String,
    val stateKnown: Boolean,
    val title: String?,
    val local: Boolean,
    val reportedPlaying: Boolean,
)

/** Package-scoped compatibility, not proof of the underlying audio player's UID. */
internal object QqPlaybackPolicy {
    fun select(musicActive: Boolean, sessions: List<PlaybackCandidate>): Int? {
        if (!musicActive || sessions.any { it.reportedPlaying }) return null
        val unknown = sessions.indices.filter { !sessions[it].stateKnown && sessions[it].local }
        val index = unknown.singleOrNull() ?: return null
        val candidate = sessions[index]
        return index.takeIf {
            candidate.pkg == Books.SOURCE_PKG && !candidate.title.isNullOrBlank()
        }
    }
}
