package com.audiostats.ui

import android.os.PowerManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.audiostats.data.TrackSettings
import com.audiostats.data.PlaybackDebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onExportTable: () -> Unit,
    onExportData: () -> Unit,
    onExportDebug: () -> Unit,
    onImport: () -> Unit,
    onOpenFilter: () -> Unit,
    onRequestBatteryWhitelist: () -> Unit,
    onOpenAppDetails: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val settings = remember { TrackSettings(context) }
    val debug = remember { PlaybackDebugLog.get(context) }
    val scope = rememberCoroutineScope()

    var hideShort by remember { mutableStateOf(settings.hideShortRecords) }
    var countMuted by remember { mutableStateOf(settings.countWhenMuted) }
    var showSongs by remember { mutableStateOf(settings.showSongsPage) }
    var showBooks by remember { mutableStateOf(settings.showBooksPage) }
    var allowedCount by remember { mutableIntStateOf(settings.allowed.size) }
    var batteryIgnored by remember { mutableStateOf(false) }
    var debugEnabled by remember { mutableStateOf(settings.debugPlayback) }
    var debugBytes by remember { mutableStateOf(0L) }
    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var shortFilter by remember { mutableStateOf(settings.shortRecordFilterEnabled) }
    var shortSeconds by remember { mutableIntStateOf(settings.shortRecordFilterSeconds) }
    var editShortDuration by remember { mutableStateOf(false) }

    LaunchedEffect(debugEnabled) {
        while (true) {
            debugBytes = withContext(Dispatchers.IO) { debug.size() }
            delay(10_000)
        }
    }

    fun setDebug(value: Boolean) {
        if (!value) debug.record("debug_disabled")
        settings.debugPlayback = value
        debugEnabled = value
        if (value) debug.record("debug_enabled", PlaybackDebugLog.environment(context))
    }

    LifecycleResumeEffect(Unit) {
        allowedCount = settings.allowed.size
        batteryIgnored = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item {
                Group("页面") {
                    SettingRow(
                        "歌单",
                        "在「记录」右侧显示网易云音乐的听歌统计",
                        onClick = {
                            showSongs = !showSongs
                            settings.showSongsPage = showSongs
                        },
                    ) {
                        Switch(checked = showSongs, onCheckedChange = {
                            showSongs = it
                            settings.showSongsPage = it
                        })
                    }
                    HorizontalDivider()
                    SettingRow(
                        "书库",
                        "显示 QQ 浏览器的听书统计",
                        onClick = {
                            showBooks = !showBooks
                            settings.showBooksPage = showBooks
                        },
                    ) {
                        Switch(checked = showBooks, onCheckedChange = {
                            showBooks = it
                            settings.showBooksPage = it
                        })
                    }
                }
            }
            item {
                Group("数据") {
                    SettingRow("导出表格", "把全部播放记录保存为 CSV 表格，可用 Excel 打开查看", onClick = onExportTable)
                    HorizontalDivider()
                    SettingRow("导出数据", "完整备份为 zip：全部记录、歌曲信息、书库合并与备注、封面图片", onClick = onExportData)
                    HorizontalDivider()
                    SettingRow("导入数据", "选择备份 zip 或 CSV，覆盖匹配记录的信息，新增其他记录，不删除文件以外的数据", onClick = onImport)
                }
            }
            item {
                Group("记录") {
                    SettingRow(
                        "记录范围",
                        if (allowedCount == 0) "全部应用" else "仅 $allowedCount 个应用",
                        onClick = onOpenFilter,
                    )
                    HorizontalDivider()
                    SettingRow(
                        "短时间过滤",
                        "播放结束后删除短记录，不计入统计；不清理已有历史",
                        onClick = {
                            shortFilter = !shortFilter
                            settings.shortRecordFilterEnabled = shortFilter
                        },
                    ) {
                        Switch(checked = shortFilter, onCheckedChange = {
                            shortFilter = it
                            settings.shortRecordFilterEnabled = it
                        })
                    }
                    HorizontalDivider()
                    SettingRow(
                        "过滤时长",
                        "短于 ${shortSeconds / 60} 分钟 ${shortSeconds % 60} 秒",
                        onClick = { editShortDuration = true },
                    )
                    HorizontalDivider()
                    SettingRow(
                        "静音时也计时",
                        "媒体音量为 0 时仍计入播放时长；关闭后静音期间暂停计时",
                        onClick = {
                            countMuted = !countMuted
                            settings.countWhenMuted = countMuted
                        },
                    ) {
                        Switch(checked = countMuted, onCheckedChange = {
                            countMuted = it
                            settings.countWhenMuted = it
                        })
                    }
                }
            }
            item {
                Group("显示") {
                    SettingRow(
                        "隐藏短记录",
                        "已结束且不足 1 分钟的记录不在播放记录列表中显示（仍计入时长）",
                        onClick = {
                            hideShort = !hideShort
                            settings.hideShortRecords = hideShort
                        },
                    ) {
                        Switch(checked = hideShort, onCheckedChange = {
                            hideShort = it
                            settings.hideShortRecords = it
                        })
                    }
                }
            }
            item {
                Group("调试") {
                    SettingRow(
                        "播放调试",
                        "包含所有应用的媒体会话、标题、状态和判定过程，仅存本机",
                        onClick = { setDebug(!debugEnabled) },
                    ) {
                        Switch(checked = debugEnabled, onCheckedChange = ::setDebug)
                    }
                    HorizontalDivider()
                    SettingRow(
                        "导出调试日志",
                        "ZIP · 当前 ${"%.2f".format(debugBytes / 1024.0 / 1024.0)} MB · 滚动保留约 32 MB",
                        onClick = onExportDebug,
                    )
                    HorizontalDivider()
                    SettingRow(
                        "清除全部调试缓存",
                        if (clearing) "正在清除…" else "保留播放记录、书库、备注和封面",
                        onClick = { if (!clearing) confirmClear = true },
                    )
                }
            }
            item {
                Group("后台运行") {
                    SettingRow(
                        "电池优化白名单",
                        if (batteryIgnored) "已允许后台运行" else "未加入，点击后在系统对话框中选择「允许」",
                        onClick = onRequestBatteryWhitelist,
                    )
                    HorizontalDivider()
                    SettingRow(
                        VendorSettings.autoStartTitle,
                        VendorSettings.autoStartHint,
                        onClick = { VendorSettings.openAutoStart(context) },
                    )
                    HorizontalDivider()
                    SettingRow(
                        "应用详情",
                        "系统的应用信息页：电池、通知等权限",
                        onClick = onOpenAppDetails,
                    )
                }
            }
        }
    }

    if (editShortDuration) {
        ShortRecordDurationDialog(
            seconds = shortSeconds,
            onDismiss = { editShortDuration = false },
            onSave = {
                settings.shortRecordFilterSeconds = it
                shortSeconds = it
                editShortDuration = false
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除全部调试缓存？") },
            text = { Text("删除本机现有的全部播放调试日志，无法恢复。播放记录、书库、备注和封面不会删除。若调试仍开启，将继续记录新日志。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    clearing = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { debug.clear(); debug.size() } }
                        result.onSuccess { debugBytes = it }
                        clearing = false
                        Toast.makeText(context, if (result.isSuccess) "已清除调试缓存" else "清除失败，请重试", Toast.LENGTH_LONG).show()
                    }
                }) { Text("确认清除") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

internal fun parseFilterDuration(minutes: String, seconds: String): Int? {
    if (minutes.any { it !in '0'..'9' } || seconds.any { it !in '0'..'9' }) return null
    val m = if (minutes.isEmpty()) 0L else minutes.toLongOrNull() ?: return null
    val s = if (seconds.isEmpty()) 0L else seconds.toLongOrNull() ?: return null
    if (m > Int.MAX_VALUE / 60L || s !in 0..59) return null
    val total = m * 60 + s
    return total.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
}

@Composable
private fun ShortRecordDurationDialog(seconds: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var minutes by remember { mutableStateOf((seconds / 60).toString()) }
    var remainder by remember { mutableStateOf((seconds % 60).toString()) }
    val parsed = parseFilterDuration(minutes, remainder)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("过滤时长") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { minutes = it.filter { c -> c in '0'..'9' }.take(9) },
                        label = { Text("分钟") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = remainder,
                        onValueChange = { remainder = it.filter { c -> c in '0'..'9' }.take(2) },
                        label = { Text("秒") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = remainder.toIntOrNull()?.let { it > 59 } == true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    if (parsed == null) "时长须大于 0，秒数为 0–59。" else "结束后删除短于此时长的播放记录，无法恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (parsed == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = { parsed?.let(onSave) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
    )
    Panel(Modifier.fillMaxWidth()) {
        Column { content() }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing?.invoke()
    }
}
