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
import com.audiostats.data.PlaybackDebugLog
import org.json.JSONArray
import org.json.JSONObject

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
    private val debug by lazy { PlaybackDebugLog.get(this) }

    // 在 App 里修改记录范围、静音计时后立即生效
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        debug.record("settings_changed", JSONObject().put("key", key))
        applyRecordingFlags()
        if (key == TrackSettings.KEY_DEBUG && settings.debugPlayback) {
            lastCompatibilityDecision = null
            debugSnapshot()
        }
    }

    // 音量调到 0 / 静音开关变化时重新判断（设置里关闭了"静音时也计时"才有影响）
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            debug.record("volume_changed", JSONObject().put("action", intent.action)
                .put("extras", PlaybackDebugLog.bundle(intent.extras))
                .put("volume", am.getStreamVolume(AudioManager.STREAM_MUSIC)).put("muted", isMusicMuted()))
            applyRecordingFlags()
        }
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
            debugSnapshot()
            handler.postDelayed(this, CHECKPOINT_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onListenerConnected() {
        if (started) return
        started = true
        Log.i(TAG, "listener connected")
        debug.record("listener_connected", PlaybackDebugLog.environment(this))

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

        activityMonitor = AudioActivityMonitor(
            db, { am.isMusicActive }, handler, output,
            keepUnconfirmed = {
                trackers.values.none { it.isPlaying } &&
                    (settings.countWhenMuted || !isMusicMuted())
            },
            beforeEvaluate = ::applyQqCompatibility,
            diagnostic = debug::record,
        ).also {
            am.registerAudioPlaybackCallback(it.callback, handler)
            it.evaluate()
        }
        am.registerAudioDeviceCallback(deviceCallback, handler)
        debugSnapshot()
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
        debug.record("listener_shutdown")
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
        debug.record("active_sessions_changed", JSONObject()
            .put("packages", JSONArray(controllers.map { it.packageName })))
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

    private var lastCompatibilityDecision: String? = null

    private fun applyQqCompatibility(musicActive: Boolean) {
        val sessions = trackers.values.toList()
        val candidates = sessions.map { it.candidate() }
        val selected = QqPlaybackPolicy.select(musicActive, candidates)
        sessions.forEachIndexed { index, tracker -> tracker.setQqCompatibilityPlaying(index == selected) }
        val decision = JSONObject().put("music_active", musicActive)
            .put("selected_pkg", selected?.let { sessions[it].pkg } ?: JSONObject.NULL)
            .put("reason", when {
                !musicActive -> "no_system_audio"
                candidates.any { it.reportedPlaying } -> "reported_playback_has_priority"
                candidates.count { !it.stateKnown && it.local } > 1 -> "conflicting_unknown_sessions"
                selected != null -> "single_qq_session_with_title"
                else -> "no_eligible_qq_session"
            })
            .put("candidates", JSONArray(candidates.map {
                JSONObject().put("pkg", it.pkg).put("state_known", it.stateKnown)
                    .put("title", it.title ?: JSONObject.NULL).put("local", it.local)
                    .put("reported_playing", it.reportedPlaying)
            })).toString()
        if (decision != lastCompatibilityDecision) {
            lastCompatibilityDecision = decision
            debug.record("qq_compat_decision", JSONObject(decision))
        }
    }

    private fun debugSnapshot() {
        if (!debug.enabled) return
        debug.record("system_snapshot", PlaybackDebugLog.environment(this)
            .put("music_active", am.isMusicActive).put("muted", isMusicMuted())
            .put("music_volume", am.getStreamVolume(AudioManager.STREAM_MUSIC))
            .put("output", currentOutput).put("count_when_muted", settings.countWhenMuted)
            .put("short_filter_enabled", settings.shortRecordFilterEnabled)
            .put("short_filter_seconds", settings.shortRecordFilterSeconds)
            .put("allowed_packages", JSONArray(settings.allowed.toList()))
            .put("playback_configs", JSONArray(am.activePlaybackConfigurations.map { it.toString() }))
            .put("output_devices", JSONArray(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map {
                JSONObject().put("id", it.id).put("type", it.type).put("name", it.productName)
            })))
        trackers.values.forEach { it.debugSnapshot() }
    }

    private fun isMusicMuted() =
        am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 || am.isStreamMute(AudioManager.STREAM_MUSIC)

    private fun shouldRecord(pkg: String) =
        settings.shouldTrack(pkg) && (settings.countWhenMuted || !isMusicMuted())

    private fun applyRecordingFlags() {
        trackers.values.forEach { it.recording = shouldRecord(it.pkg) }
        activityMonitor?.requestEvaluate()
    }

    private fun onDevicesChanged() {
        debugSnapshot()
        val newOutput = OutputDetector.current(am)
        if (newOutput == currentOutput) return
        Log.i(TAG, "output $currentOutput -> $newOutput")
        debug.record("output_changed", JSONObject().put("from", currentOutput).put("to", newOutput))
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
