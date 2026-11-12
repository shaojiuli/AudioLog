package com.audiostats.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.time.OffsetDateTime
import java.util.concurrent.Executors

class PlaybackDebugLog private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = TrackSettings(app).prefs
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "playback-debug-io").apply { isDaemon = true }
    }
    private val store = DebugLogStore(File(app.noBackupFilesDir, "playback-debug"))
    val enabled get() = prefs.getBoolean(TrackSettings.KEY_DEBUG, false)

    fun record(event: String, details: JSONObject = JSONObject()) {
        if (!enabled) return
        val line = JSONObject()
            .put("time", OffsetDateTime.now().toString())
            .put("elapsed_ms", SystemClock.elapsedRealtime())
            .put("event", event)
            .put("details", details)
            .toString()
        executor.execute {
            runCatching { store.append(line) }.onFailure { Log.w(TAG, "Cannot write diagnostics", it) }
        }
    }

    fun session(event: String, controller: MediaController, extra: JSONObject = JSONObject()) {
        if (!enabled) return
        runCatching {
            val state = controller.playbackState
            val info = controller.playbackInfo
            extra.put("pkg", controller.packageName)
                .put("session", controller.sessionToken.toString())
                .put("state", state?.let {
                    JSONObject().put("value", it.state).put("position_ms", it.position)
                        .put("buffered_ms", it.bufferedPosition).put("speed", it.playbackSpeed)
                        .put("updated_elapsed_ms", it.lastPositionUpdateTime).put("actions", it.actions)
                        .put("active_queue_id", it.activeQueueItemId)
                        .put("error", it.errorMessage ?: JSONObject.NULL)
                        .put("extras", bundle(it.extras))
                        .put("custom_actions", JSONArray(it.customActions.map { action ->
                            JSONObject().put("action", action.action).put("name", action.name)
                                .put("extras", bundle(action.extras))
                        }))
                } ?: JSONObject.NULL)
                .put("metadata", metadata(controller.metadata))
                .put("queue_title", controller.queueTitle ?: JSONObject.NULL)
                .put("queue", JSONArray(controller.queue.orEmpty().map { item ->
                    JSONObject().put("id", item.queueId).put("description", item.description.toString())
                        .put("media_id", item.description.mediaId ?: JSONObject.NULL)
                        .put("extras", bundle(item.description.extras))
                }))
                .put("extras", bundle(controller.extras))
                .put("playback_info", info?.let {
                    JSONObject().put("type", it.playbackType).put("control", it.volumeControl)
                        .put("volume", it.currentVolume).put("max_volume", it.maxVolume)
                        .put("audio_attributes", it.audioAttributes.toString())
                } ?: JSONObject.NULL)
            record(event, extra)
        }.onFailure {
            record("session_read_error", extra.put("error", it.toString()))
        }
    }

    /** Call from Dispatchers.IO; export/clear are serialized with all pending writes. */
    fun size(): Long = executor.submit<Long> { store.size() }.get()
    fun clear() = executor.submit { store.clear() }.get()
    fun export(output: OutputStream) = executor.submit { store.export(output) }.get()

    companion object {
        private const val TAG = "AudioDebug"
        @Volatile private var instance: PlaybackDebugLog? = null

        fun get(context: Context): PlaybackDebugLog = instance ?: synchronized(this) {
            instance ?: PlaybackDebugLog(context).also { instance = it }
        }

        private val longKeys = setOf(
            MediaMetadata.METADATA_KEY_DURATION, MediaMetadata.METADATA_KEY_YEAR,
            MediaMetadata.METADATA_KEY_TRACK_NUMBER, MediaMetadata.METADATA_KEY_NUM_TRACKS,
            MediaMetadata.METADATA_KEY_DISC_NUMBER, MediaMetadata.METADATA_KEY_BT_FOLDER_TYPE,
            "android.media.metadata.ADVERTISEMENT", "android.media.metadata.DOWNLOAD_STATUS",
        )
        private val bitmapKeys = setOf(
            MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        )
        private fun metadata(value: MediaMetadata?): Any {
            value ?: return JSONObject.NULL
            return JSONObject().apply {
                value.keySet().sorted().forEach { key ->
                    put(key, runCatching {
                        when (key) {
                            in longKeys -> value.getLong(key)
                            in bitmapKeys -> value.getBitmap(key)?.let { "${it.width}x${it.height} bitmap" }
                            MediaMetadata.METADATA_KEY_RATING,
                            MediaMetadata.METADATA_KEY_USER_RATING -> value.getRating(key)?.toString()
                            else -> value.getText(key)?.toString()
                        }
                    }.getOrNull() ?: JSONObject.NULL)
                }
            }
        }

        @Suppress("DEPRECATION")
        fun bundle(value: Bundle?, depth: Int = 0): Any {
            value ?: return JSONObject.NULL
            if (depth >= 4) return "[nested bundle]"
            return JSONObject().apply {
                value.keySet().sorted().forEach { key ->
                    put(key, runCatching {
                        when (val item = value.get(key)) {
                            null -> JSONObject.NULL
                            is Bundle -> bundle(item, depth + 1)
                            is Bitmap -> "${item.width}x${item.height} bitmap"
                            is Number, is Boolean, is String -> item
                            else -> item.toString().take(8_192)
                        }
                    }.getOrElse { "[unreadable: ${it.javaClass.simpleName}]" })
                }
            }
        }

        fun environment(context: Context) = JSONObject()
            .put("version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
            .put("sdk", Build.VERSION.SDK_INT).put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
    }
}
