package com.audiostats.data

import android.content.Context
import android.content.SharedPreferences

class TrackSettings(context: Context) {
    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        // 1.1 版有"记录全部应用"开关，开着时勾选列表不生效
        if (prefs.contains(KEY_LEGACY_RECORD_ALL)) {
            val editor = prefs.edit().remove(KEY_LEGACY_RECORD_ALL)
            if (prefs.getBoolean(KEY_LEGACY_RECORD_ALL, true)) editor.remove(KEY_ALLOWED)
            editor.apply()
        }
    }

    /** 记录范围：为空表示记录全部应用 */
    var allowed: Set<String>
        get() = prefs.getStringSet(KEY_ALLOWED, emptySet())!!.toSet()
        set(v) = prefs.edit().putStringSet(KEY_ALLOWED, v).apply()

    fun shouldTrack(pkg: String) = allowed.let { it.isEmpty() || pkg in it }

    /** 已结束且不足 1 分钟的记录不在播放记录列表中显示 */
    var hideShortRecords: Boolean
        get() = prefs.getBoolean(KEY_HIDE_SHORT, true)
        set(v) = prefs.edit().putBoolean(KEY_HIDE_SHORT, v).apply()

    /** 媒体音量为 0 时仍计时；关闭后静音期间暂停计时 */
    var countWhenMuted: Boolean
        get() = prefs.getBoolean(KEY_COUNT_MUTED, true)
        set(v) = prefs.edit().putBoolean(KEY_COUNT_MUTED, v).apply()

    var showSongsPage: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SONGS, true)
        set(v) = prefs.edit().putBoolean(KEY_SHOW_SONGS, v).apply()

    var showBooksPage: Boolean
        get() = prefs.getBoolean(KEY_SHOW_BOOKS, true)
        set(v) = prefs.edit().putBoolean(KEY_SHOW_BOOKS, v).apply()

    var appsCollapsed: Boolean
        get() = prefs.getBoolean(KEY_APPS_COLLAPSED, false)
        set(v) = prefs.edit().putBoolean(KEY_APPS_COLLAPSED, v).apply()

    var batteryNoticeDismissed: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_DISMISSED, false)
        set(v) = prefs.edit().putBoolean(KEY_BATTERY_DISMISSED, v).apply()

    companion object {
        const val KEY_ALLOWED = "allowed_packages"
        const val KEY_HIDE_SHORT = "hide_short_records"
        const val KEY_COUNT_MUTED = "count_when_muted"
        const val KEY_APPS_COLLAPSED = "apps_collapsed"
        const val KEY_SHOW_SONGS = "show_songs_page"
        const val KEY_SHOW_BOOKS = "show_books_page"
        const val KEY_BATTERY_DISMISSED = "battery_notice_dismissed"
        private const val KEY_LEGACY_RECORD_ALL = "record_all"
    }
}
