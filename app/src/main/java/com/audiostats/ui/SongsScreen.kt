package com.audiostats.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.audiostats.data.Song
import com.audiostats.data.Songs
import com.audiostats.data.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 时长条：iPad 屏幕使用时间的浅灰 */
private val BAR_GRAY = Color(0xFFD1D1D6)
private const val PAGE_SIZE = 30

/** 歌曲列表卡片在 LazyColumn 里的位置（合计卡片、「歌曲」标题之后），翻页时滚到这里 */
private const val SONG_LIST_INDEX = 2

@Composable
fun SongsPage(bottomPadding: Dp, refreshKey: Int = 0) {
    val context = LocalContext.current

    var songs by remember { mutableStateOf<List<Song>?>(null) }
    var sortByTotal by rememberSaveable { mutableStateOf(true) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LifecycleResumeEffect(Unit) {
        reloadTick++
        onPauseOrDispose { }
    }

    LaunchedEffect(reloadTick, refreshKey) {
        while (true) {
            songs = withContext(Dispatchers.IO) { Songs.load(context) }
            delay(30_000)
        }
    }

    val list = songs
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding + 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (list == null) return@LazyColumn

        item {
            Panel(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f).padding(bottom = 6.dp)) {
                        Text("听歌合计", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "网易云音乐 · ${list.size} 首",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(formatDuration(list.sumOf { it.totalMs }), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "歌曲",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextToggle(
                    options = listOf("最近" to false, "时长" to true),
                    selected = sortByTotal,
                    onSelect = {
                        // 切换排序后回到第一页
                        sortByTotal = it
                        page = 0
                    },
                )
            }
        }

        if (list.isEmpty()) {
            item {
                Text(
                    "还没有网易云音乐的听歌记录。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            return@LazyColumn
        }

        val sorted = if (sortByTotal) list.sortedByDescending { it.totalMs } else list.sortedByDescending { it.lastMs }
        val max = list.maxOf { it.totalMs }.coerceAtLeast(1)
        val pageCount = (sorted.size + PAGE_SIZE - 1) / PAGE_SIZE
        val current = page.coerceIn(0, pageCount - 1)
        item {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    sorted.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { i, song ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        SongRow(song, song.totalMs.toFloat() / max)
                    }
                }
            }
        }
        if (pageCount > 1) {
            item {
                PageSelector(current, pageCount) { p ->
                    page = p
                    scope.launch { listState.scrollToItem(SONG_LIST_INDEX) }
                }
            }
        }
    }
}

/** 底部选页器：‹ 前后各两页的页码 ›，首尾页始终显示，中间用 … 省略 */
@Composable
private fun PageSelector(current: Int, count: Int, onSelect: (Int) -> Unit) {
    val shown = (listOf(0, count - 1) + (current - 2..current + 2)).filter { it in 0 until count }.distinct().sorted()
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PageLabel("‹", enabled = current > 0) { onSelect(current - 1) }
        var prev = -1
        for (p in shown) {
            if (prev >= 0 && p - prev > 1) PageLabel("…", enabled = false) {}
            PageLabel("${p + 1}", enabled = true, selected = p == current) { onSelect(p) }
            prev = p
        }
        PageLabel("›", enabled = current < count - 1) { onSelect(current + 1) }
    }
}

@Composable
private fun PageLabel(text: String, enabled: Boolean, selected: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = when {
            selected -> MaterialTheme.colorScheme.primary
            enabled -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        },
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled && !selected, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/**
 * 歌曲行（参考 iPad 屏幕使用时间）：左侧封面；歌名；歌手 · 次数；
 * 灰色时长条，累计时长在条的同一行右端。
 */
@Composable
private fun SongRow(song: Song, fraction: Float) {
    val cover by produceState<ImageBitmap?>(null, song.artFile) {
        value = song.artFile?.let { f ->
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val coverModifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
        cover?.let { Image(it, contentDescription = null, modifier = coverModifier, contentScale = ContentScale.Crop) }
            ?: Box(coverModifier.background(MaterialTheme.colorScheme.surfaceVariant))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 20.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp)) // 歌手 · 次数：上边距 2dp，下边距 0（紧贴时长条）
            Text(
                listOfNotNull(song.artist, song.plays?.let { "%.1f 次".format(it) }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(BAR_GRAY)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(formatDuration(song.totalMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
