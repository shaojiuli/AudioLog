package com.audiostats.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.PowerManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.audiostats.data.AudioDb
import com.audiostats.data.Bucket
import com.audiostats.data.PeriodStats
import com.audiostats.data.Segment
import com.audiostats.data.Span
import com.audiostats.data.Stats
import com.audiostats.data.TrackSettings
import com.audiostats.data.formatDuration
import com.audiostats.tracker.AudioTrackerService
import com.audiostats.tracker.OutputDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.math.ceil
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class AppInfo(val label: String, val icon: Bitmap?)

private const val MAX_TRACKS = 300

/** 记录页里卡片、每组播放记录下方的间距 */
private val ITEM_GAP = 8.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsPage(
    span: Span,
    anchor: LocalDate,
    onSpanChange: (Span) -> Unit,
    onAnchorChange: (LocalDate) -> Unit,
    bottomPadding: Dp,
    onOpenNotificationAccess: () -> Unit,
    onRequestBatteryWhitelist: () -> Unit,
) {
    val context = LocalContext.current
    val db = remember { AudioDb.get(context) }
    val settings = remember { TrackSettings(context) }

    var stats by remember { mutableStateOf<PeriodStats?>(null) }
    var selectedPkg by rememberSaveable { mutableStateOf<String?>(null) }
    var hideShort by remember { mutableStateOf(settings.hideShortRecords) }
    var batteryDismissed by remember { mutableStateOf(settings.batteryNoticeDismissed) }
    var permissionGranted by remember { mutableStateOf(true) }
    var batteryIgnored by remember { mutableStateOf(true) }
    var refreshTick by remember { mutableIntStateOf(0) }
    val appInfos = remember { mutableStateMapOf<String, AppInfo>() }

    LifecycleResumeEffect(Unit) {
        permissionGranted = AudioTrackerService.isPermissionGranted(context)
        batteryIgnored = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
        hideShort = settings.hideShortRecords
        refreshTick++
        onPauseOrDispose { }
    }

    var appsCollapsed by remember { mutableStateOf(settings.appsCollapsed) }

    // 切换跨度/日期/选中应用、回到前台时刷新；停留在页面上时每 30 秒刷新一次
    LaunchedEffect(span, anchor, selectedPkg, refreshTick) {
        while (true) {
            val s = withContext(Dispatchers.IO) { Stats.period(db, span, anchor, selectedPkg) }
            // 选中的应用在当前时段没有记录时，取消选中（会触发重新加载）
            if (selectedPkg != null && s.apps.none { it.pkg == selectedPkg }) {
                selectedPkg = null
                return@LaunchedEffect
            }
            stats = s
            val missing = s.apps.map { it.pkg }.filter { it !in appInfos }
            if (missing.isNotEmpty()) {
                appInfos.putAll(withContext(Dispatchers.IO) {
                    missing.associateWith { loadAppInfo(context, it) }
                })
            }
            delay(30_000)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding + 24.dp),
    ) {
        // 不用统一的 spacedBy：卡片和每组记录下方留 ITEM_GAP，分组标题上下只留很小的距离
        if (!permissionGranted) {
            item {
                Box(Modifier.padding(bottom = ITEM_GAP)) {
                NoticeCard(
                    text = "需要开启「通知使用权」才能读取各 App 的播放状态。本应用不会读取通知内容，数据只保存在本机。",
                    button = "去开启",
                    onClick = onOpenNotificationAccess,
                )
                }
            }
        }
        if (permissionGranted && !batteryIgnored && !batteryDismissed) {
            item {
                Box(Modifier.padding(bottom = ITEM_GAP)) {
                NoticeCard(
                    text = "建议允许本应用在后台运行，否则可能被系统杀掉导致漏记。" +
                            VendorSettings.noticeHint,
                    button = "允许后台运行",
                    onClick = onRequestBatteryWhitelist,
                    secondaryButton = "不再提示",
                    onSecondary = {
                        batteryDismissed = true
                        settings.batteryNoticeDismissed = true
                    },
                )
                }
            }
        }

        val s = stats
        val selected = selectedPkg
        // 应用的颜色按全部应用排名固定，选中某个应用时柱子沿用它的颜色
        val appColors = assignColors(s?.apps?.map { it.pkg }.orEmpty(), useBrand = true)
        val partColors = when {
            s == null -> emptyMap()
            s.partsAreBooks -> assignColors(s.parts.map { it.first }, useBrand = false)
            else -> appColors
        }

        item {
            Box(Modifier.padding(bottom = ITEM_GAP)) { ChartCard(
                span = span,
                anchor = anchor,
                stats = s,
                partColors = partColors,
                focusLabel = selected?.let { appInfos[it]?.label ?: it },
                onSpanChange = onSpanChange,
                onAnchorChange = onAnchorChange,
                onDrill = { b ->
                    if (b.drillSpan != null && b.drillDate != null) {
                        onSpanChange(b.drillSpan)
                        onAnchorChange(b.drillDate)
                    }
                },
            ) }
        }

        if (s == null) return@LazyColumn

        // 分组标题写在灰色底层上，卡片里只放列表（参考 iPad 设置）
        item {
            SectionHeader(
                title = buildString {
                    append("应用")
                    selected?.let { append(" · ").append(appInfos[it]?.label ?: it) }
                },
            ) {
                // 不用 TextButton：它最小高度 48dp，会把标题行撑高
                Text(
                    if (appsCollapsed) "展开" else "收起",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            appsCollapsed = !appsCollapsed
                            settings.appsCollapsed = appsCollapsed
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        if (!appsCollapsed) item {
            Panel(Modifier.padding(bottom = ITEM_GAP).fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    if (s.apps.isEmpty()) {
                        Text(
                            "暂无记录",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    val max = s.apps.maxOfOrNull { it.totalMs } ?: 1L
                    s.apps.forEach { app ->
                        AppRow(
                            info = appInfos[app.pkg] ?: AppInfo(app.pkg, null),
                            totalMs = app.totalMs,
                            fraction = app.totalMs.toFloat() / max,
                            color = appColors[app.pkg] ?: OTHER_COLOR,
                            selected = app.pkg == selected,
                            onClick = { selectedPkg = if (app.pkg == selected) null else app.pkg },
                        )
                    }
                }
            }
        }

        val now = System.currentTimeMillis()
        val tracks = s.tracks
            .filter { selected == null || it.pkg == selected }
            // 进行中的记录每 30 秒写盘一次，end 距今不久的视为仍在播放，先显示
            .filter { !hideShort || it.endMs - it.startMs >= 60_000 || now - it.endMs < 45_000 }

        item {
            SectionHeader(
                title = buildString {
                    append("播放记录")
                    selected?.let { append(" · ").append(appInfos[it]?.label ?: it) }
                },
            ) {
                TextToggle(
                    options = listOf("详细" to false, "粗略" to true),
                    selected = hideShort,
                    onSelect = {
                        hideShort = it
                        settings.hideShortRecords = it
                    },
                )
            }
        }
        if (tracks.isEmpty()) {
            item { Text("暂无记录", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(groupTracks(tracks.take(MAX_TRACKS)), key = { it.first().id }) { group ->
            val pkg = group.first().pkg
            Box(Modifier.padding(bottom = ITEM_GAP)) {
                TrackGroup(group, appInfos[pkg]?.label ?: pkg, showDate = span != Span.DAY)
            }
        }
        if (tracks.size > MAX_TRACKS) {
            item {
                Text(
                    "仅显示最近 $MAX_TRACKS 条，完整记录可在设置里导出",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChartCard(
    span: Span,
    anchor: LocalDate,
    stats: PeriodStats?,
    partColors: Map<String, Color>,
    focusLabel: String?,
    onSpanChange: (Span) -> Unit,
    onAnchorChange: (LocalDate) -> Unit,
    onDrill: (Bucket) -> Unit,
) {
    val today = LocalDate.now()
    Panel(Modifier.fillMaxWidth()) {
        // 顶部比「日周月年」下方少 2dp：上 4 + 文字内边距 4 = 8dp；下 4 + 翻页按钮内 6 = 10dp
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
            TextToggle(
                options = listOf("日" to Span.DAY, "周" to Span.WEEK, "月" to Span.MONTH, "年" to Span.YEAR),
                selected = span,
                onSelect = onSpanChange,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                spacing = 16.dp,
                fontSize = 16.sp,
            )

            // 翻页按钮缩到 32dp，和上面的切换、下面的合计贴近
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onAnchorChange(Stats.shift(span, anchor, -1)) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一段")
                }
                Text(
                    Stats.label(span, anchor),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                )
                val canNext = Stats.dateRange(span, anchor).second < today
                IconButton(
                    onClick = {
                        val next = Stats.shift(span, anchor, 1)
                        onAnchorChange(if (next > today) today else next)
                    },
                    enabled = canNext,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一段")
                }
            }

            if (stats != null) {
                val average = averageOf(stats)
                // 左：「合计」（及日均/月均）；右：合计时长，右对齐
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f).padding(bottom = 6.dp)) {
                        Text(
                            focusLabel?.let { "$it · 合计" } ?: "合计",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (average != null) {
                            Text(
                                (if (span == Span.YEAR) "月均 " else "日均 ") + formatDuration(average),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(formatDuration(stats.totalMs), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                BarChart(stats.buckets, span, stats.parts.map { it.first }, partColors, average, onDrill)
                if (stats.partsAreBooks && stats.parts.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    BookLegend(stats.parts, partColors)
                }
            }
        }
    }
}

/**
 * 柱状图（参考 iPad 屏幕使用时间）：横纵轴线 + 4 等分浅色网格，刻度在右侧，
 * 堆叠柱按分组着色，绿色虚线为平均值。
 */
@Composable
private fun BarChart(
    buckets: List<Bucket>,
    span: Span,
    partOrder: List<String>,
    partColors: Map<String, Color>,
    averageMs: Long?,
    onClick: (Bucket) -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val axisColor = labelColor.copy(alpha = 0.6f)
    val labelPx = with(LocalDensity.current) { 10.sp.toPx() }
    val gutter = 44.dp

    // 日视图：柱高 = 这一小时里播放的比例，固定 0–100%；其他视图取整到便于 4 等分的刻度
    val hourMs = 3_600_000L
    val scaleMs = if (span == Span.DAY) hourMs else
        niceScale(maxOf(buckets.maxOfOrNull { it.ms } ?: 0L, averageMs ?: 0L))
    val labels = if (span == Span.DAY) listOf("0", "50%", "100%")
    else listOf("0", formatAxis(scaleMs / 2), formatAxis(scaleMs))

    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .pointerInput(buckets) {
                    detectTapGestures { pos ->
                        val plotW = size.width - gutter.toPx()
                        if (pos.x < plotW) buckets.getOrNull((pos.x / (plotW / buckets.size)).toInt())?.let(onClick)
                    }
                }
        ) {
            val plotW = size.width - gutter.toPx()
            val top = labelPx
            val bottom = size.height - 1.dp.toPx()
            fun yOf(frac: Float) = bottom - (bottom - top) * frac.coerceIn(0f, 1f)

            // 网格：4 等分
            for (k in 1..4) {
                val y = yOf(k / 4f)
                drawLine(gridColor, Offset(0f, y), Offset(plotW, y), 1.dp.toPx())
            }

            val slot = plotW / buckets.size
            val barW = slot * if (buckets.size > 20) 0.6f else 0.5f
            val r = CornerRadius(barW / 4, barW / 4)
            for ((i, b) in buckets.withIndex()) {
                if (b.ms <= 0) continue
                val x = i * slot + (slot - barW) / 2
                val yTop = yOf(b.ms.toFloat() / scaleMs)
                val h = bottom - yTop
                // 堆叠：时长排名靠前的分组在下面；先裁出整根柱子的圆角轮廓，再按段着色
                val parts = partOrder.mapNotNull { k -> b.parts[k]?.let { k to it } }
                val partSum = parts.sumOf { it.second }.toFloat()
                if (partSum <= 0f) {
                    drawRoundRect(primary, Offset(x, yTop), Size(barW, h), r)
                    continue
                }
                clipPath(Path().apply { addRoundRect(RoundRect(x, yTop, x + barW, bottom + r.y, r)) }) {
                    var y = bottom
                    for ((k, ms) in parts) {
                        val ph = h * ms / partSum
                        drawRect(partColors[k] ?: OTHER_COLOR, Offset(x, y - ph), Size(barW, ph))
                        y -= ph
                    }
                }
            }

            // 坐标轴
            drawLine(axisColor, Offset(0f, bottom), Offset(plotW, bottom), 1.dp.toPx())
            drawLine(axisColor, Offset(0f, top), Offset(0f, bottom), 1.dp.toPx())

            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                textSize = labelPx
                color = labelColor.toArgb()
            }
            val textX = plotW + 6.dp.toPx()
            val native = drawContext.canvas.nativeCanvas
            native.drawText(labels[2], textX, yOf(1f) + labelPx / 3, paint)
            native.drawText(labels[1], textX, yOf(0.5f) + labelPx / 3, paint)
            native.drawText(labels[0], textX, yOf(0f), paint)

            if (averageMs != null && averageMs > 0) {
                val y = yOf(averageMs.toFloat() / scaleMs)
                drawLine(
                    AVERAGE_COLOR, Offset(0f, y), Offset(plotW, y), 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                )
                paint.color = AVERAGE_COLOR.toArgb()
                native.drawText("平均", textX, y + labelPx / 3, paint)
            }
        }
        Row(Modifier.fillMaxWidth().padding(end = gutter, top = 4.dp)) {
            buckets.forEachIndexed { i, b ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (showXLabel(span, i)) {
                        // 月视图每格只有约 10dp 宽，放开宽度限制，两位数日期才不会被截掉
                        Text(
                            b.label, fontSize = 10.sp, color = labelColor, maxLines = 1, softWrap = false,
                            modifier = Modifier.wrapContentWidth(unbounded = true),
                        )
                    }
                }
            }
        }
    }
}

private val AVERAGE_COLOR = Color(0xFF34C759)

/** 选中 QQ 浏览器时，图表下方按书列出颜色和时长；超出调色板的合并为"其他" */
@Composable
private fun BookLegend(parts: List<Pair<String, Long>>, colors: Map<String, Color>) {
    val named = parts.filter { colors[it.first] != OTHER_COLOR }
    val otherMs = parts.filter { colors[it.first] == OTHER_COLOR }.sumOf { it.second }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        named.forEach { (name, ms) -> LegendRow(colors[name] ?: OTHER_COLOR, name, ms) }
        if (otherMs > 0) LegendRow(OTHER_COLOR, "其他 ${parts.size - named.size} 本", otherMs)
    }
}

@Composable
private fun LegendRow(color: Color, label: String, ms: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(formatDuration(ms), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun showXLabel(span: Span, i: Int) = when (span) {
    Span.DAY -> i % 3 == 0
    Span.MONTH -> i == 0 || (i + 1) % 5 == 0
    else -> true
}

/** 满格刻度：小时长用 20/40/60/120/240/480 分钟，再往上取 4 的整数倍小时，保证 4 等分是整数 */
private fun niceScale(maxMs: Long): Long {
    val minutes = maxMs / 60_000.0
    for (step in listOf(20, 40, 60, 120, 240, 480)) if (minutes <= step) return step * 60_000L
    return (ceil(minutes / 60 / 4) * 4).toLong() * 3_600_000L
}

private fun formatAxis(ms: Long): String {
    val min = ms / 60_000
    return when {
        min < 60 -> "${min}分"
        min % 60 == 0L -> "${min / 60}小时"
        else -> "%.1f小时".format(min / 60f)
    }
}

/** 平均值：周/月按天、年按月，只算到今天为止的柱子；日视图不显示 */
private fun averageOf(stats: PeriodStats): Long? {
    if (stats.span == Span.DAY) return null
    val today = LocalDate.now()
    val past = stats.buckets.filter { it.drillDate != null && it.drillDate <= today }
    if (past.isEmpty()) return null
    return past.sumOf { it.ms } / past.size
}

/** 灰色底层上的分组标题，右侧放操作 */
@Composable
private fun SectionHeader(title: String, action: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        action()
    }
}

@Composable
private fun NoticeCard(
    text: String,
    button: String,
    onClick: () -> Unit,
    secondaryButton: String? = null,
    onSecondary: () -> Unit = {},
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onClick) { Text(button) }
                if (secondaryButton != null) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onSecondary) { Text(secondaryButton) }
                }
            }
        }
    }
}

@Composable
private fun AppRow(info: AppInfo, totalMs: Long, fraction: Float, color: Color, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (info.icon != null) {
            Image(info.icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(36.dp))
        } else {
            Box(Modifier.size(36.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(
                    info.label,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
                Text(formatDuration(totalMs), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(4.dp))
            // 自绘连续进度条：Material 3 的 LinearProgressIndicator 在进度和底槽之间留缝、末端还有圆点
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color.copy(alpha = 0.2f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(color)
                )
            }
        }
    }
}

/** 连续的同一应用、同一标题的记录归为一组，标题只显示一次 */
private fun groupTracks(tracks: List<Segment>): List<List<Segment>> {
    val groups = ArrayList<MutableList<Segment>>()
    for (seg in tracks) {
        val last = groups.lastOrNull()?.first()
        if (last != null && last.pkg == seg.pkg && last.title == seg.title && last.artist == seg.artist) {
            groups.last() += seg
        } else {
            groups += mutableListOf(seg)
        }
    }
    return groups
}

/**
 * 一组记录放在一个背景框里：标题一行；下面每次播放一行（时间段、时长、连接方式），
 * 连接方式只在和上一行不同时显示；应用名在第一条播放行的最右侧。
 */
@Composable
private fun TrackGroup(group: List<Segment>, appLabel: String, showDate: Boolean) {
    val zone = ZoneId.systemDefault()
    val startFmt = DateTimeFormatter.ofPattern(if (showDate) "M-d HH:mm" else "HH:mm")
    val endFmt = DateTimeFormatter.ofPattern("HH:mm")
    val first = group.first()
    val sub = MaterialTheme.typography.bodySmall
    val subColor = MaterialTheme.colorScheme.onSurfaceVariant

    Panel(Modifier.fillMaxWidth()) { Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            listOfNotNull(first.title, first.artist).joinToString(" — ").ifEmpty { "（无标题）" },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        var prevOutput: String? = null
        group.forEachIndexed { i, seg ->
            val start = Instant.ofEpochMilli(seg.startMs).atZone(zone).format(startFmt)
            val end = Instant.ofEpochMilli(seg.endMs).atZone(zone).format(endFmt)
            Row(Modifier.padding(top = 2.dp)) {
                Text("$start–$end", style = sub, color = subColor, modifier = Modifier.width(if (showDate) 128.dp else 96.dp))
                Text(formatDuration(seg.endMs - seg.startMs), style = sub, color = subColor, modifier = Modifier.width(72.dp))
                Text(
                    if (seg.output != prevOutput) OutputDetector.label(seg.output) else "",
                    style = sub,
                    color = subColor,
                    modifier = Modifier.weight(1f),
                )
                if (i == 0) Text(appLabel, style = sub, color = subColor, maxLines = 1)
            }
            prevOutput = seg.output
        }
    } }
}

private fun loadAppInfo(context: Context, pkg: String): AppInfo {
    val pm = context.packageManager
    return try {
        val ai = pm.getApplicationInfo(pkg, 0)
        AppInfo(
            label = pm.getApplicationLabel(ai).toString(),
            icon = pm.getApplicationIcon(ai).toBitmap(96, 96),
        )
    } catch (e: PackageManager.NameNotFoundException) {
        AppInfo(pkg, null)
    }
}
