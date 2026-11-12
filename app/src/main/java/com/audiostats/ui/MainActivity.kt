package com.audiostats.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.audiostats.data.Backup
import com.audiostats.data.CsvIo
import com.audiostats.data.PlaybackDebugLog
import com.audiostats.tracker.AudioTrackerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private enum class Page { MAIN, SETTINGS, FILTER }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                var page by rememberSaveable { mutableStateOf(Page.MAIN) }
                // 从设置返回时保留主页面的当前页、统计跨度和日期
                val stateHolder = rememberSaveableStateHolder()
                when (page) {
                    Page.MAIN -> stateHolder.SaveableStateProvider(Page.MAIN.name) {
                        MainScreen(
                            onOpenNotificationAccess = {
                                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            },
                            onRequestBatteryWhitelist = ::requestBatteryWhitelist,
                            onOpenSettings = { page = Page.SETTINGS },
                        )
                    }

                    Page.SETTINGS -> SettingsScreen(
                        onBack = { page = Page.MAIN },
                        onExportTable = { exportCsv.launch("audio_stats_${LocalDate.now()}.csv") },
                        onExportData = { exportData.launch("audio_stats_backup_${LocalDate.now()}.zip") },
                        onExportDebug = { exportDebug.launch("audio_stats_debug_${LocalDate.now()}.zip") },
                        onImport = {
                            importData.launch(arrayOf("application/zip", "text/*", "application/vnd.ms-excel", "application/octet-stream"))
                        },
                        onOpenFilter = { page = Page.FILTER },
                        onRequestBatteryWhitelist = ::requestBatteryWhitelist,
                        onOpenAppDetails = ::openAppDetails,
                    )

                    Page.FILTER -> AppFilterScreen(onBack = { page = Page.SETTINGS })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 某些国产系统会把监听服务杀掉且不自动恢复，打开 App 时顺手请求重新绑定
        if (AudioTrackerService.isPermissionGranted(this)) {
            AudioTrackerService.rebind(this)
        }
    }

    // 让用户选保存位置，避免记录多时 Intent 传大文本超出 Binder 限制
    private val exportCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = CsvIo.export(this@MainActivity)
                    contentResolver.openOutputStream(uri)!!.use { out ->
                        // 带 BOM，Excel 打开中文不乱码
                        out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                        out.write(text.toByteArray())
                    }
                }.isSuccess
            }
            toast(if (ok) "已导出" else "导出失败")
        }
    }

    /** 完整备份：数据库 + 封面图片，打成一个 zip */
    private val exportData = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)!!.use { Backup.export(this@MainActivity, it) }
                }.isSuccess
            }
            toast(if (ok) "已导出" else "导出失败")
        }
    }

    private val exportDebug = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)!!.use { PlaybackDebugLog.get(this@MainActivity).export(it) }
                }.isSuccess
            }
            toast(if (ok) "调试日志已导出" else "调试日志导出失败")
        }
    }

    /** 匹配记录覆盖信息，未匹配的新增；文件以外的记录不删除。 */
    private val importData = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val isZip = bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
                    if (isZip) {
                        val r = Backup.import(this@MainActivity, bytes.inputStream())
                        buildString {
                            append("新增 ${r.segments} 条，覆盖 ${r.updated} 条")
                            if (r.duplicates > 0) append("，未变 ${r.duplicates} 条")
                            if (r.songs > 0) append("，歌曲信息 ${r.songs} 首")
                            if (r.covers > 0) append("，封面 ${r.covers} 张")
                        }
                    } else {
                        val r = CsvIo.import(this@MainActivity, bytes.toString(Charsets.UTF_8))
                        buildString {
                            append("新增 ${r.inserted} 条，覆盖 ${r.updated} 条")
                            if (r.duplicates > 0) append("，未变 ${r.duplicates} 条")
                            if (r.invalid > 0) append("，无法识别 ${r.invalid} 行")
                        }
                    }
                }.getOrElse { "导入失败：${it.message}" }
            }
            toast(message)
        }
    }

    /** 弹出系统的"允许在后台运行"对话框，同意后加入电池优化白名单 */
    @SuppressLint("BatteryLife")
    private fun requestBatteryWhitelist() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /** 应用详情页：电池、通知等系统权限 */
    private fun openAppDetails() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
