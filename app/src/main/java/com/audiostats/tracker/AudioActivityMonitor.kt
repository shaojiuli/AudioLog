package com.audiostats.tracker

import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import com.audiostats.data.AudioDb
import com.audiostats.data.Attribution
import com.audiostats.data.Table

/**
 * 不区分 App 的"系统正在放音乐"时长，作为 MediaSession 统计的对照。
 * 能覆盖不注册 MediaSession 的应用（部分游戏、短视频内嵌播放等）。
 */
class AudioActivityMonitor(
    private val db: AudioDb,
    private val musicActive: () -> Boolean,
    private val handler: Handler,
    private val output: () -> String,
    private val keepUnconfirmed: () -> Boolean,
    private val beforeEvaluate: (Boolean) -> Unit = {},
    private val diagnostic: (String, org.json.JSONObject) -> Unit = { _, _ -> },
) {
    private var segmentId: Long? = null
    private var segmentStart = 0L
    private var unconfirmedId: Long? = null
    private var unconfirmedStart = 0L

    private val evaluateRunnable = Runnable { evaluate() }

    val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            diagnostic("audio_config_changed", org.json.JSONObject().put(
                "configs", org.json.JSONArray(configs.orEmpty().map { it.toString() })
            ))
            requestEvaluate()
        }
    }

    /** 状态变化时还没稳定，稍等再判断 */
    fun requestEvaluate() {
        handler.removeCallbacks(evaluateRunnable)
        handler.postDelayed(evaluateRunnable, 500)
    }

    fun evaluate() {
        val active = musicActive()
        beforeEvaluate(active)
        if (active && segmentId == null) {
            open()
        } else if (!active && segmentId != null) {
            close()
        }
        if (active && keepUnconfirmed()) {
            if (unconfirmedId == null) {
                unconfirmedStart = System.currentTimeMillis()
                unconfirmedId = db.open(
                    Table.SEGMENTS, Attribution.UNKNOWN_PKG, null, null, unconfirmedStart, output(),
                    attribution = Attribution.UNCONFIRMED,
                )
                diagnostic("unconfirmed_start", org.json.JSONObject()
                    .put("id", unconfirmedId).put("start_ms", unconfirmedStart))
            }
        } else {
            closeUnconfirmed()
        }
    }

    fun checkpoint() {
        evaluate()
        segmentId?.let { db.updateEnd(Table.ACTIVITY, it, System.currentTimeMillis()) }
        unconfirmedId?.let { db.updateEnd(Table.SEGMENTS, it, System.currentTimeMillis()) }
    }

    fun splitForOutputChange() {
        // 待归档记录以连续音频活动为单位，不因连接方式或候选元数据变化拆段。
        unconfirmedId?.let { db.markOutputChange(it, output()) }
        if (segmentId == null) return
        close()
        open()
    }

    fun stop() {
        handler.removeCallbacks(evaluateRunnable)
        close()
        closeUnconfirmed()
    }

    private fun open() {
        segmentStart = System.currentTimeMillis()
        segmentId = db.open(Table.ACTIVITY, "", null, null, segmentStart, output())
        diagnostic("system_audio_start", org.json.JSONObject()
            .put("id", segmentId).put("start_ms", segmentStart).put("output", output()))
    }

    private fun close() {
        val id = segmentId ?: return
        diagnostic("system_audio_stop", org.json.JSONObject()
            .put("id", id).put("end_ms", System.currentTimeMillis()))
        db.close(Table.ACTIVITY, id, segmentStart, System.currentTimeMillis())
        segmentId = null
    }

    private fun closeUnconfirmed() {
        val id = unconfirmedId ?: return
        diagnostic("unconfirmed_stop", org.json.JSONObject()
            .put("id", id).put("start_ms", unconfirmedStart).put("end_ms", System.currentTimeMillis()))
        db.close(Table.SEGMENTS, id, unconfirmedStart, System.currentTimeMillis())
        unconfirmedId = null
    }
}
