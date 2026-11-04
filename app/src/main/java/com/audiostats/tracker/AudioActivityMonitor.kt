package com.audiostats.tracker

import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import com.audiostats.data.AudioDb
import com.audiostats.data.Table

/**
 * 不区分 App 的"系统正在放音乐"时长，作为 MediaSession 统计的对照。
 * 能覆盖不注册 MediaSession 的应用（部分游戏、短视频内嵌播放等）。
 */
class AudioActivityMonitor(
    private val db: AudioDb,
    private val am: AudioManager,
    private val handler: Handler,
    private val output: () -> String,
    private val onEvaluated: (musicActive: Boolean) -> Unit,
) {
    private var segmentId: Long? = null
    private var segmentStart = 0L

    private val evaluateRunnable = Runnable { evaluate() }

    val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) =
            requestEvaluate()
    }

    /** 状态变化时还没稳定，稍等再判断 */
    fun requestEvaluate() {
        handler.removeCallbacks(evaluateRunnable)
        handler.postDelayed(evaluateRunnable, 500)
    }

    fun evaluate() {
        val active = am.isMusicActive
        if (active && segmentId == null) {
            open()
        } else if (!active && segmentId != null) {
            close()
        }
        onEvaluated(active)
    }

    fun checkpoint() {
        evaluate()
        segmentId?.let { db.updateEnd(Table.ACTIVITY, it, System.currentTimeMillis()) }
    }

    fun splitForOutputChange() {
        if (segmentId == null) return
        close()
        open()
    }

    fun stop() {
        handler.removeCallbacks(evaluateRunnable)
        close()
    }

    private fun open() {
        segmentStart = System.currentTimeMillis()
        segmentId = db.open(Table.ACTIVITY, "", null, null, segmentStart, output())
    }

    private fun close() {
        val id = segmentId ?: return
        db.close(Table.ACTIVITY, id, segmentStart, System.currentTimeMillis())
        segmentId = null
    }
}
