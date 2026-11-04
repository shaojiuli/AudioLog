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
import com.audiostats.data.SongInfoStore
import com.audiostats.data.Table

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
    private var playing = false
    val isPlaying get() = playing

    /**
     * 是否上报过 PlaybackState。有的应用（如 QQ 浏览器）注册了会话、给了歌曲信息，
     * 但 PlaybackState 始终为 null，这类会话由服务根据系统音乐流状态推断是否在播放。
     */
    var stateKnown = false
        private set
    var lastMetaChangeMs = 0L
        private set

    private var segmentId: Long? = null
    private var segmentStart = 0L

    /**
     * 不在记录范围内的应用仍然跟踪播放状态（推断其他会话时要用），只是不写记录。
     */
    // 必须声明在 init 块之前，init 里就可能开始记录
    var recording = initialRecording
        set(value) {
            if (field == value) return
            field = value
            if (!value) closeSegment() else if (playing) openSegment()
        }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = onState(state)
        override fun onMetadataChanged(metadata: MediaMetadata?) = onMeta(metadata)
        override fun onSessionDestroyed() {
            release()
            onDestroyed(token)
        }
    }

    init {
        controller.registerCallback(callback, handler)
        onMeta(controller.metadata)
        onState(controller.playbackState)
    }

    private fun onState(state: PlaybackState?) {
        if (state == null) return
        stateKnown = true
        val nowPlaying = when (state.state) {
            PlaybackState.STATE_PLAYING -> !isRemotePlayback(state)
            // 缓冲、跳转等过渡状态：保持原状态，不打断也不新开
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> playing

            else -> false
        }
        // 很多播放器会重复上报同一状态，只在真正变化时处理
        if (nowPlaying == playing) return
        playing = nowPlaying
        if (playing) openSegment() else closeSegment()
        onPlayingChanged()
    }

    /** 仅对不上报 PlaybackState 的会话生效 */
    fun setInferredPlaying(value: Boolean) {
        if (stateKnown || value == playing) return
        playing = value
        if (playing) openSegment() else closeSegment()
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
        lastMetaChangeMs = System.currentTimeMillis()

        val id = segmentId ?: return
        if (System.currentTimeMillis() - segmentStart < META_GRACE_MS) {
            // 刚开始播放时信息常常是分几次到达的，直接补到当前这段上
            db.updateMeta(Table.SEGMENTS, id, title, artist, mediaId)
        } else {
            closeSegment()
            openSegment()
        }
    }

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
        if (!recording) return
        segmentStart = System.currentTimeMillis()
        segmentId = db.open(Table.SEGMENTS, pkg, title, artist, segmentStart, output(), mediaId)
        Log.i(TAG, "start $pkg ${if (stateKnown) "" else "(inferred) "}$title / $artist")
    }

    private fun closeSegment() {
        val id = segmentId ?: return
        val end = System.currentTimeMillis()
        db.close(Table.SEGMENTS, id, segmentStart, end)
        segmentId = null
        Log.i(TAG, "stop  $pkg ${(end - segmentStart) / 1000}s")
    }

    /** 定期把进行中的记录写盘，进程被杀时最多丢一个间隔 */
    fun checkpoint() {
        segmentId?.let { db.updateEnd(Table.SEGMENTS, it, System.currentTimeMillis()) }
    }

    fun splitForOutputChange() {
        if (segmentId == null || !recording) return
        closeSegment()
        openSegment()
    }

    fun release() {
        closeSegment()
        playing = false
        controller.unregisterCallback(callback)
    }

    private companion object {
        const val TAG = "AudioTracker"
        const val META_GRACE_MS = 3_000L
        const val PACKAGE_SPOTIFY = "com.spotify.music"
        const val SPOTIFY_EXTRA_LOCAL = "com.spotify.music.extra.ACTIVE_PLAYBACK_LOCAL"
    }
}
