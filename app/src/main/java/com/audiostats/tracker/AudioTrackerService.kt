package com.audiostats.tracker

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.content.ContextCompat
import com.audiostats.data.AudioDb
import com.audiostats.data.TrackSettings

/**
 * 借助"通知使用权"拿到 MediaSessionManager.getActiveSessions() 的权限，
 * 本身不处理任何通知。
 */
class AudioTrackerService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private val component by lazy { ComponentName(this, AudioTrackerService::class.java) }
    private val db by lazy { AudioDb.get(this) }
    private val am by lazy { getSystemService(AudioManager::class.java) }
    private val msm by lazy { getSystemService(MediaSessionManager::class.java) }
    private val settings by lazy { TrackSettings(this) }

    // 在 App 里修改记录范围、静音计时后立即生效
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        applyRecordingFlags()
    }

    // 音量调到 0 / 静音开关变化时重新判断（设置里关闭了"静音时也计时"才有影响）
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = applyRecordingFlags()
    }

    private val trackers = HashMap<MediaSession.Token, SessionTracker>()
    private var activityMonitor: AudioActivityMonitor? = null
    private var currentOutput = OutputDetector.SPEAKER

    // onListenerConnected 有时会在没有 disconnect 的情况下被调用两次
    private var started = false

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener {
        syncSessions(it.orEmpty())
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = onDevicesChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = onDevicesChanged()
    }

    private val ticker = object : Runnable {
        override fun run() {
            applyRecordingFlags()
            trackers.values.forEach { it.checkpoint() }
            activityMonitor?.checkpoint()
            handler.postDelayed(this, CHECKPOINT_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onListenerConnected() {
        if (started) return
        started = true
        Log.i(TAG, "listener connected")

        currentOutput = OutputDetector.current(am)
        val output = { currentOutput }
        settings.prefs.registerOnSharedPreferenceChangeListener(settingsListener)
        ContextCompat.registerReceiver(
            this,
            volumeReceiver,
            IntentFilter().apply {
                addAction(ACTION_VOLUME_CHANGED)
                addAction(ACTION_STREAM_MUTE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            syncSessions(msm.getActiveSessions(component))
        } catch (e: SecurityException) {
            Log.w(TAG, "no notification access", e)
        }

        activityMonitor = AudioActivityMonitor(db, am, handler, output, ::inferUnknownSessions).also {
            am.registerAudioPlaybackCallback(it.callback, handler)
            it.evaluate()
        }
        am.registerAudioDeviceCallback(deviceCallback, handler)
        handler.postDelayed(ticker, CHECKPOINT_MS)
    }

    override fun onListenerDisconnected() {
        shutdown()
        // 部分系统会无故断开，主动请求重新绑定
        requestRebind(component)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (!started) return
        started = false
        Log.i(TAG, "shutdown")
        handler.removeCallbacks(ticker)
        settings.prefs.unregisterOnSharedPreferenceChangeListener(settingsListener)
        runCatching { unregisterReceiver(volumeReceiver) }
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        activityMonitor?.let {
            am.unregisterAudioPlaybackCallback(it.callback)
            it.stop()
        }
        activityMonitor = null
        am.unregisterAudioDeviceCallback(deviceCallback)
        trackers.values.forEach { it.release() }
        trackers.clear()
    }

    private fun syncSessions(controllers: List<MediaController>) {
        if (!started) return
        val alive = controllers.map { it.sessionToken }.toSet()

        trackers.keys.filter { it !in alive }.forEach { token ->
            trackers.remove(token)?.release()
        }

        for (c in controllers) {
            if (c.sessionToken in trackers || c.packageName == packageName) continue
            trackers[c.sessionToken] = SessionTracker(
                context = this,
                token = c.sessionToken,
                db = db,
                handler = handler,
                output = { currentOutput },
                initialRecording = shouldRecord(c.packageName),
                onPlayingChanged = { activityMonitor?.requestEvaluate() },
                onDestroyed = {
                    trackers.remove(it)
                    activityMonitor?.requestEvaluate()
                },
            )
        }
        activityMonitor?.requestEvaluate()
    }

    /**
     * 不上报 PlaybackState 的会话：系统音乐流在响、且没有其他会话明确在播放时，
     * 认为是它在播放。同时有多个这样的会话时，记给最近更新过歌曲信息的那个。
     */
    private fun inferUnknownSessions(musicActive: Boolean) {
        val unknown = trackers.values.filter { !it.stateKnown }
        if (unknown.isEmpty()) return
        val knownPlaying = trackers.values.any { it.stateKnown && it.isPlaying }
        val pick = if (musicActive && !knownPlaying) unknown.maxBy { it.lastMetaChangeMs } else null
        unknown.forEach { it.setInferredPlaying(it === pick) }
    }

    private fun isMusicMuted() =
        am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 || am.isStreamMute(AudioManager.STREAM_MUSIC)

    private fun shouldRecord(pkg: String) =
        settings.shouldTrack(pkg) && (settings.countWhenMuted || !isMusicMuted())

    private fun applyRecordingFlags() {
        trackers.values.forEach { it.recording = shouldRecord(it.pkg) }
    }

    private fun onDevicesChanged() {
        val newOutput = OutputDetector.current(am)
        if (newOutput == currentOutput) return
        Log.i(TAG, "output $currentOutput -> $newOutput")
        currentOutput = newOutput
        trackers.values.forEach { it.splitForOutputChange() }
        activityMonitor?.splitForOutputChange()
    }

    companion object {
        private const val TAG = "AudioTracker"
        private const val CHECKPOINT_MS = 30_000L
        // 系统隐藏 API 的广播，各厂商系统普遍会发；收不到时靠 30 秒的定时检查兜底
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val ACTION_STREAM_MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION"

        fun isPermissionGranted(context: Context): Boolean =
            androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)

        fun rebind(context: Context) {
            requestRebind(ComponentName(context, AudioTrackerService::class.java))
        }
    }
}
