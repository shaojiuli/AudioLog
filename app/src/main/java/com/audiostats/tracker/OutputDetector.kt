package com.audiostats.tracker

import android.media.AudioDeviceInfo
import android.media.AudioManager

/** 当前音频输出设备类别，用于区分"耳机时长"和"外放时长" */
object OutputDetector {
    const val WIRED = "wired"
    const val BLUETOOTH = "bluetooth"
    const val SPEAKER = "speaker"

    private val wiredTypes = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
    )

    private val bluetoothTypes = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        26, // TYPE_BLE_HEADSET (API 31)
        27, // TYPE_BLE_SPEAKER (API 31)
    )

    fun current(am: AudioManager): String {
        val types = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        return when {
            types.any { it in bluetoothTypes } -> BLUETOOTH
            types.any { it in wiredTypes } -> WIRED
            else -> SPEAKER
        }
    }

    fun label(output: String) = when (output) {
        WIRED -> "有线耳机"
        BLUETOOTH -> "蓝牙"
        "mixed" -> "多种连接"
        else -> "外放"
    }
}
