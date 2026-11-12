package com.audiostats.tracker

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.util.Log
import com.audiostats.data.AudioDb
import com.audiostats.data.Attribution
import com.audiostats.data.Books
import com.audiostats.data.PlaybackDebugLog
import com.audiostats.data.SongInfoStore
import com.audiostats.data.Table
import org.json.JSONObject

/**
 * 跟踪单个 MediaSession：PLAYING 时开一段记录，暂停/停止/会话销毁时关闭；
 * 播放中切歌则拆分成新的一段，方便按歌统计。
 */
class SessionTracker(
    private val context: Context,
    token: MediaSession.Token,
    private val db: AudioDb,
    private val handler: Handler,
    private val output: () -> String,
    initialRecording: Boolean,
    private val onPlayingChanged: () -> Unit,
    private val onDestroyed: (MediaSession.Token) -> Unit,
) {
    // 自己 new 一个 controller，不复用 getActiveSessions 返回的实例
    private val controller = MediaController(context, token)
    val pkg: String = controller.packageName

    private var title: String? = null
    private var artist: String? = null
    private var mediaId: String? = null
    private var reportedPlaying = false
    private var compatibilityPlaying = false
    val isPlaying get() = reportedPlaying || compatibilityPlaying
    private val debug = PlaybackDebugLog.get(context)
    private val attribution get() = if (compatibilityPlaying) Attribution.QQ_COMPAT else Attribution.REPORTED
    private var segmentAttribution: String? = null
    internal fun candidate() = PlaybackCandidate(pkg, stateKnown, title, isLocalPlayback(), reportedPlaying)

    /** Only QQ Browser may use the separate package-scoped compatibility policy. */
    var stateKnown = false
        private set
    private var segmentId: Long? = null
    private var segmentStart = 0L
    private val closedParts = ArrayList<Pair<Long, Long>>()

    /**
     * 不在记录范围内的应用仍然跟踪播放状态（推断其他会话时要用），只是不写记录。
     */
    // 必须声明在 init 块之前，init 里就可能开始记录
    var recording = initialRecording
        set(value) {
            if (field == value) return
            field = value
            if (!value) closeSegment() else if (isPlaying) openSegment()
        }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            debug.session("playback_state", controller, JSONObject()
                .put("received_state", state?.toString() ?: JSONObject.NULL))
            onState(state)
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            debug.session("metadata_changed", controller)
            onMeta(metadata)
        }
        override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) {
            debug.session("queue_changed", controller)
        }
        override fun onQueueTitleChanged(title: CharSequence?) {
            debug.session("queue_title_changed", controller)
        }
        override fun onExtrasChanged(extras: android.os.Bundle?) {
            debug.session("session_extras_changed", controller)
        }
        override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) {
            debug.session("audio_info_changed", controller)
            if (!isLocalPlayback()) compatibilityPlaying = false
            onState(controller.playbackState)
        }
        override fun onSessionEvent(event: String, extras: android.os.Bundle?) {
            debug.session("session_event", controller, JSONObject()
                .put("name", event).put("event_extras", PlaybackDebugLog.bundle(extras)))
        }
        override fun onSessionDestroyed() {
            debug.session("session_destroyed", controller)
            release()
            onDestroyed(token)
        }
    }

    init {
        controller.registerCallback(callback, handler)
        onMeta(controller.metadata)
        onState(controller.playbackState)
        debugSnapshot()
    }

    private fun onState(state: PlaybackState?) {
        stateKnown = state != null
        val nowPlaying = when (state?.state) {
            PlaybackState.STATE_PLAYING -> !isRemotePlayback(state)
            // 缓冲、跳转等过渡状态：保持原状态，不打断也不新开
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> reportedPlaying

            else -> false
        }
        reportedPlaying = nowPlaying
        if (stateKnown) compatibilityPlaying = false
        reconcileSegment()
        onPlayingChanged()
    }

    fun setQqCompatibilityPlaying(value: Boolean) {
        val next = value && pkg == Books.SOURCE_PKG && !stateKnown &&
            !title.isNullOrBlank() && isLocalPlayback()
        if (next == compatibilityPlaying) return
        compatibilityPlaying = next
        reconcileSegment()
    }

    private fun reconcileSegment() {
        if (!isPlaying || (segmentId != null && segmentAttribution != attribution)) closeSegment()
        if (isPlaying) openSegment()
    }

    private fun onMeta(metadata: MediaMetadata?) {
        val newTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()?.ifEmpty { null }
        val newArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim()?.ifEmpty { null }
        val newMediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.trim()?.ifEmpty { null }
        // 封面可能在后续的元数据更新里才到，每次都尝试保存（只对提供媒体 ID 的应用生效）
        if (metadata != null && newTitle != null) SongInfoStore.save(context, pkg, newMediaId, newTitle, newArtist, metadata)
        if (newTitle == title && newArtist == artist && newMediaId == mediaId) return
        title = newTitle
        artist = newArtist
        mediaId = newMediaId

        if (compatibilityPlaying && title.isNullOrBlank()) {
            compatibilityPlaying = false
            reconcileSegment()
            onPlayingChanged()
            return
        }

        val id = segmentId
        if (id != null && System.currentTimeMillis() - segmentStart < META_GRACE_MS) {
            // 刚开始播放时信息常常是分几次到达的，直接补到当前这段上
            db.updateMeta(Table.SEGMENTS, id, title, artist, mediaId)
        } else if (id != null) {
            closeSegment()
            openSegment()
        }
        onPlayingChanged()
    }

    private fun isLocalPlayback() =
        controller.playbackInfo?.playbackType != MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE &&
            !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && controller.tag.contains("CastMediaSession"))

    /** 投屏 / Spotify Connect 等远程播放：声音不是从手机发出的，不计时 */
    private fun isRemotePlayback(state: PlaybackState): Boolean {
        if (pkg == PACKAGE_SPOTIFY &&
            state.extras?.getBoolean(SPOTIFY_EXTRA_LOCAL, true) == false
        ) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            controller.tag.contains("CastMediaSession")
        ) return true
        return controller.playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
    }

    private fun openSegment() {
        if (!recording || segmentId != null) return
        segmentStart = System.currentTimeMillis()
        segmentAttribution = attribution
        segmentId = db.open(Table.SEGMENTS, pkg, title, artist, segmentStart, output(), mediaId, attribution)
        debug.record("segment_start", JSONObject().put("id", segmentId).put("pkg", pkg)
            .put("title", title ?: JSONObject.NULL).put("attribution", attribution)
            .put("start_ms", segmentStart).put("output", output()))
        Log.i(TAG, "start $pkg ($attribution) $title / $artist")
    }

    private fun closeSegment(endPlayback: Boolean = true) {
        val id = segmentId
        if (id == null) {
            if (endPlayback) finishPlayback()
            return
        }
        val end = System.currentTimeMillis()
        val kept = db.close(Table.SEGMENTS, id, segmentStart, end, filterShortRecord = false)
        if (kept) closedParts += id to (end - segmentStart)
        debug.record("segment_stop", JSONObject().put("id", id).put("pkg", pkg)
            .put("start_ms", segmentStart).put("end_ms", end).put("attribution", segmentAttribution)
            .put("kept_before_short_filter", kept).put("playback_ended", endPlayback))
        segmentId = null
        segmentAttribution = null
        if (endPlayback) finishPlayback()
        Log.i(TAG, "stop  $pkg ${(end - segmentStart) / 1000}s")
    }

    private fun finishPlayback() {
        if (closedParts.isEmpty()) return
        db.filterFinishedSegments(closedParts.map { it.first }, closedParts.sumOf { it.second })
        closedParts.clear()
    }

    /** 定期把进行中的记录写盘，进程被杀时最多丢一个间隔 */
    fun checkpoint() {
        segmentId?.let { db.updateEnd(Table.SEGMENTS, it, System.currentTimeMillis()) }
    }

    fun debugSnapshot() {
        debug.session("session_snapshot", controller, JSONObject()
            .put("recording_allowed", recording).put("reported_playing", reportedPlaying)
            .put("qq_compat_playing", compatibilityPlaying).put("segment_id", segmentId ?: JSONObject.NULL))
    }

    fun splitForOutputChange() {
        if (segmentId == null || !recording) return
        closeSegment(endPlayback = false)
        openSegment()
    }

    fun release() {
        closeSegment()
        reportedPlaying = false
        compatibilityPlaying = false
        controller.unregisterCallback(callback)
    }

    private companion object {
        const val TAG = "AudioTracker"
        const val META_GRACE_MS = 3_000L
        const val PACKAGE_SPOTIFY = "com.spotify.music"
        const val SPOTIFY_EXTRA_LOCAL = "com.spotify.music.extra.ACTIVE_PLAYBACK_LOCAL"
    }
}
