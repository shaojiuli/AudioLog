package com.audiostats.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.audiostats.data.AudioDb
import com.audiostats.data.Book
import com.audiostats.data.Books
import com.audiostats.data.Segment
import com.audiostats.data.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArchiveBookSheet(segment: Segment, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AudioDb.get(context) }
    val scope = rememberCoroutineScope()
    var books by remember(segment.id) { mutableStateOf<List<Book>?>(null) }
    var query by remember(segment.id) { mutableStateOf("") }
    var selected by remember(segment.id) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")

    LaunchedEffect(segment.id) {
        books = withContext(Dispatchers.IO) { Books.load(db).sortedByDescending { it.lastMs } }
    }

    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("归档到小说", style = MaterialTheme.typography.titleMedium)
            Text(
                "${Instant.ofEpochMilli(segment.startMs).atZone(zone).format(formatter)}–" +
                    "${Instant.ofEpochMilli(segment.endMs).atZone(zone).format(formatter)}" +
                    " · ${formatDuration(segment.endMs - segment.startMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索小说") },
                singleLine = true,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            val candidates = books?.filter { book ->
                query.isBlank() || book.name.contains(query, true) ||
                    book.titles.any { it.contains(query, true) }
            }
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 360.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                if (candidates.isNullOrEmpty()) {
                    item {
                        Text(
                            if (books == null) "加载中…" else if (books!!.isEmpty()) "暂无小说记录" else "没有匹配的小说",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 20.dp),
                        )
                    }
                }
                items(candidates.orEmpty(), key = { it.name }) { book ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = !saving) { selected = book.name }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(selected = selected == book.name, onClick = null)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    book.name, style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    formatDuration(book.totalMs),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                            Text(
                                "最近 ${relativeTime(book.lastMs)} · 收听 ${book.sessionCount} 次",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            book.ranges.take(2).forEach { range ->
                                Text(
                                    "${listenRangeText(range)} · ${formatDuration(range.ms)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            book.note?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
                TextButton(
                    enabled = selected != null && !saving,
                    onClick = {
                        val target = selected ?: return@TextButton
                        saving = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { check(db.archiveSegment(segment.id, target)) { "记录已不存在" } }
                            }
                            saving = false
                            if (result.isSuccess) onSaved()
                            else Toast.makeText(context, "归档失败：${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                        }
                    },
                ) { Text(if (saving) "归档中…" else "确认归档") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
