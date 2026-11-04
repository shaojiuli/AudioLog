package com.audiostats.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.audiostats.data.AudioDb
import com.audiostats.data.Book
import com.audiostats.data.Books
import com.audiostats.data.ListenRange
import com.audiostats.data.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BooksPage(mergeMode: Boolean, bottomPadding: Dp) {
    val context = LocalContext.current
    val db = remember { AudioDb.get(context) }
    val scope = rememberCoroutineScope()

    var books by remember { mutableStateOf<List<Book>?>(null) }
    var sortByTotal by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var mergeSource by remember { mutableStateOf<Book?>(null) }
    var noteBook by remember { mutableStateOf<Book?>(null) }
    var reloadTick by remember { mutableIntStateOf(0) }

    LifecycleResumeEffect(Unit) {
        reloadTick++
        onPauseOrDispose { }
    }

    LaunchedEffect(reloadTick) {
        while (true) {
            books = withContext(Dispatchers.IO) { Books.load(db) }
            delay(30_000)
        }
    }

    fun mutate(block: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { block() }
            reloadTick++
        }
    }

    val list = books
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding + 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (list == null) return@LazyColumn

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "共 ${list.size} 本 · 累计 ${formatDuration(list.sumOf { it.totalMs })}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextToggle(
                    options = listOf("最近" to false, "时长" to true),
                    selected = sortByTotal,
                    onSelect = { sortByTotal = it },
                )
            }
        }

        if (mergeMode) {
            item {
                Text(
                    "点「合并到…」把一本书的时长并入另一本；合并后展开可拆分。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (list.isEmpty()) {
            item {
                Text(
                    "还没有听书记录。用 QQ 浏览器播放有声书后，会按播放标题出现在这里。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }

        val sorted = if (sortByTotal) list.sortedByDescending { it.totalMs } else list.sortedByDescending { it.lastMs }
        items(sorted, key = { it.name }) { book ->
            BookCard(
                book = book,
                expanded = book.name in expanded,
                mergeMode = mergeMode,
                onToggle = { expanded = if (book.name in expanded) expanded - book.name else expanded + book.name },
                onMerge = { mergeSource = book },
                onSplit = { title -> mutate { Books.split(db, title) } },
                onEditNote = { noteBook = book },
            )
        }
    }

    noteBook?.let { book ->
        NoteDialog(
            book = book,
            onDismiss = { noteBook = null },
            onSave = { text ->
                noteBook = null
                mutate { Books.setNote(db, book.name, text) }
            },
        )
    }

    val source = mergeSource
    if (source != null && list != null) {
        MergeSheet(
            source = source,
            candidates = list.filter { it.name != source.name }.sortedByDescending { it.lastMs },
            onDismiss = { mergeSource = null },
            onPick = { target ->
                mergeSource = null
                expanded = expanded - source.name + target.name
                mutate { Books.merge(db, source, target) }
            },
        )
    }
}

@Composable
private fun BookCard(
    book: Book,
    expanded: Boolean,
    mergeMode: Boolean,
    onToggle: () -> Unit,
    onMerge: () -> Unit,
    onSplit: (String) -> Unit,
    onEditNote: () -> Unit,
) {
    Panel(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .clickable(onClick = onToggle)
                .animateContentSize()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        book.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append("最近 ").append(relativeTime(book.lastMs))
                            append(" · 收听 ").append(book.sessionCount).append(" 次")
                            if (book.titles.size > 1) append(" · 含 ${book.titles.size} 个标题")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatDuration(book.totalMs), fontWeight = FontWeight.Bold)
                    if (mergeMode) {
                        TextButton(onClick = onMerge) { Text("合并到…") }
                    }
                }
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))
                SectionLabel("收听记录")
                // 记录跨年或不在今年时，按年份分段并单独写一行年份
                val thisYear = LocalDate.now().year
                val showYears = book.ranges.any { it.end.year != thisYear || it.start.year != it.end.year }
                var lastYear: Int? = null
                book.ranges.forEach { r ->
                    if (showYears && r.end.year != lastYear) {
                        Text(
                            "${r.end.year}年",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = if (lastYear == null) 2.dp else 8.dp, bottom = 2.dp),
                        )
                        lastYear = r.end.year
                    }
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text(rangeText(r), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text(formatDuration(r.ms), style = MaterialTheme.typography.bodySmall)
                    }
                }

                Spacer(Modifier.height(12.dp))
                SectionLabel("备注")
                Text(
                    book.note ?: "点击添加备注",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (book.note == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onEditNote)
                        .padding(vertical = 4.dp),
                )

                if (book.titles.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    Text("包含的标题", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    book.titles.forEachIndexed { i, title ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                title,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (i == 0) {
                                Text("主标题", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                TextButton(onClick = { onSplit(title) }) { Text("拆分") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

/** 单日写「11月3日」，连续多日写「11月3日–11月9日」；跨年的起始日带上年份 */
private fun rangeText(r: ListenRange): String {
    val md = DateTimeFormatter.ofPattern("M月d日")
    if (r.start == r.end) return r.start.format(md)
    val start = if (r.start.year != r.end.year) r.start.format(DateTimeFormatter.ofPattern("yyyy年M月d日")) else r.start.format(md)
    return "$start–${r.end.format(md)}"
}

@Composable
private fun NoteDialog(book: Book, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(book.note.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("备注", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(book.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("比如：作者、播讲人、听到第几章") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MergeSheet(source: Book, candidates: List<Book>, onDismiss: () -> Unit, onPick: (Book) -> Unit) {
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("把「${source.name}」合并到", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "合并后使用目标书的名称，时长相加；之后可在目标书里拆分。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
        val shown = candidates.filter { query.isBlank() || it.titles.any { t -> t.contains(query, true) } }
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (shown.isEmpty()) {
                item {
                    Text(
                        "没有可合并的书",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(shown, key = { it.name }) { book ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(book) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(book.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    Text(formatDuration(book.totalMs), style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}

private fun relativeTime(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val hm = t.format(DateTimeFormatter.ofPattern("HH:mm"))
    return when (t.toLocalDate()) {
        today -> "今天 $hm"
        today.minusDays(1) -> "昨天 $hm"
        else -> if (t.year == today.year) t.format(DateTimeFormatter.ofPattern("M月d日"))
        else t.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
    }
}
