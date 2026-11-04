package com.audiostats.ui

import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.audiostats.data.TrackSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onExportTable: () -> Unit,
    onExportData: () -> Unit,
    onImport: () -> Unit,
    onOpenFilter: () -> Unit,
    onRequestBatteryWhitelist: () -> Unit,
    onOpenAppDetails: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val settings = remember { TrackSettings(context) }

    var hideShort by remember { mutableStateOf(settings.hideShortRecords) }
    var countMuted by remember { mutableStateOf(settings.countWhenMuted) }
    var showSongs by remember { mutableStateOf(settings.showSongsPage) }
    var showBooks by remember { mutableStateOf(settings.showBooksPage) }
    var allowedCount by remember { mutableIntStateOf(settings.allowed.size) }
    var batteryIgnored by remember { mutableStateOf(false) }

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
                    SettingRow("导入数据", "选择备份 zip 或 CSV 表格，合并到现有数据，重复的自动跳过、不会覆盖", onClick = onImport)
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
