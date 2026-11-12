package com.audiostats.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import com.audiostats.data.Span
import com.audiostats.data.TrackSettings
import kotlinx.coroutines.launch
import java.time.LocalDate

private enum class MainPage(val title: String) { RECORDS("记录"), SONGS("歌单"), BOOKS("书库") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenNotificationAccess: () -> Unit,
    onRequestBatteryWhitelist: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // 歌单、书库可在设置里隐藏；从设置返回时重新进入组合，会读到最新的开关
    val context = LocalContext.current
    val pages = remember {
        val s = TrackSettings(context)
        buildList {
            add(MainPage.RECORDS)
            if (s.showSongsPage) add(MainPage.SONGS)
            if (s.showBooksPage) add(MainPage.BOOKS)
        }
    }
    val pager = rememberPagerState { pages.size }
    val current = pages.getOrElse(pager.currentPage) { MainPage.RECORDS }
    val scope = rememberCoroutineScope()

    var span by rememberSaveable { mutableStateOf(Span.DAY) }
    var anchor by rememberSaveable { mutableStateOf(LocalDate.now()) }
    var mergeMode by rememberSaveable { mutableStateOf(false) }
    var dataRevision by remember { mutableIntStateOf(0) }

    // 离开书库页时退出合并模式
    LaunchedEffect(current) {
        if (current != MainPage.BOOKS) mergeMode = false
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                expandedHeight = 48.dp,
                navigationIcon = {
                    if (current == MainPage.RECORDS) {
                        TextButton(onClick = { anchor = LocalDate.now() }) { Text("今天") }
                    }
                },
                title = {
                    PageTabs(pages.map { it.title }, current = pager.currentPage) { scope.launch { pager.animateScrollToPage(it) } }
                },
                actions = {
                    if (current == MainPage.BOOKS) {
                        TextButton(onClick = { mergeMode = !mergeMode }) { Text(if (mergeMode) "完成" else "合并") }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        }
    ) { padding ->
        HorizontalPager(
            state = pager,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) { page ->
            val bottom = padding.calculateBottomPadding()
            when (pages[page]) {
                MainPage.RECORDS -> StatsPage(
                    span = span,
                    anchor = anchor,
                    onSpanChange = { span = it },
                    onAnchorChange = { anchor = it },
                    bottomPadding = bottom,
                    onOpenNotificationAccess = onOpenNotificationAccess,
                    onRequestBatteryWhitelist = onRequestBatteryWhitelist,
                    onArchived = { dataRevision++ },
                )

                MainPage.SONGS -> SongsPage(bottomPadding = bottom, refreshKey = dataRevision)
                MainPage.BOOKS -> BooksPage(mergeMode = mergeMode, bottomPadding = bottom, refreshKey = dataRevision)
            }
        }
    }
}

@Composable
private fun PageTabs(names: List<String>, current: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        names.forEachIndexed { i, name ->
            val selected = i == current
            Column(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .width(16.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0f))
                )
            }
        }
    }
}
