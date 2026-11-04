package com.audiostats.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.audiostats.data.AudioDb
import com.audiostats.data.Table
import com.audiostats.data.TrackSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class InstalledApp(val pkg: String, val label: String, val hasRecords: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppFilterScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val settings = remember { TrackSettings(context) }

    var allowed by remember { mutableStateOf(settings.allowed) }
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps(context) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记录范围") },
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
                Panel(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (allowed.isEmpty()) "当前：记录全部应用" else "当前：只记录勾选的 ${allowed.size} 个应用",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "不勾选任何应用即记录全部。修改只影响之后的记录，已有记录不会删除。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (allowed.isNotEmpty()) {
                            TextButton(onClick = {
                                allowed = emptySet()
                                settings.allowed = allowed
                            }) { Text("清空") }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索应用") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                )
            }
            val list = apps
            if (list == null) {
                item { Text("正在加载应用列表…") }
            } else {
                val shown = list
                    .filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
                    // 已勾选的在前，其次是有过播放记录的
                    .sortedWith(compareBy({ it.pkg !in allowed }, { !it.hasRecords }, { it.label }))
                items(shown, key = { it.pkg }) { app ->
                    val checked = app.pkg in allowed
                    AppCheckRow(app, checked) {
                        allowed = if (checked) allowed - app.pkg else allowed + app.pkg
                        settings.allowed = allowed
                    }
                }
            }
        }
    }
}

@Composable
private fun AppCheckRow(app: InstalledApp, checked: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val icon by produceState<Bitmap?>(null, app.pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(app.pkg).toBitmap(96, 96) }.getOrNull()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bmp = icon
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.size(36.dp))
        } else {
            Box(Modifier.size(36.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (app.hasRecords) "${app.pkg} · 有播放记录" else app.pkg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

private fun loadApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val recorded = AudioDb.get(context).overlapping(Table.SEGMENTS, 0, Long.MAX_VALUE).map { it.pkg }.toSet()
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, 0)
        .map { it.activityInfo.packageName }
        .plus(recorded)
        .distinct()
        .filter { it != context.packageName }
        .map { pkg ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            InstalledApp(pkg, label, pkg in recorded)
        }
}
