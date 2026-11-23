package com.sfdm.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sfdm.app.data.Category
import com.sfdm.app.data.DownloadStatus
import com.sfdm.app.data.NetworkMode
import com.sfdm.app.util.Format
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel, incomingUrl: String?, onIncomingConsumed: () -> Unit) {
    val list by vm.items.collectAsState()
    val query by vm.query.collectAsState()
    val counts by vm.categoryCounts.collectAsState()
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current

    var addUrl by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(incomingUrl) {
        if (incomingUrl != null) {
            addUrl = incomingUrl
            onIncomingConsumed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query.search,
                            onValueChange = { vm.setSearch(it) },
                            placeholder = { Text("নাম বা লিংক খুঁজুন") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("SF Download Manager")
                    }
                },
                actions = {
                    if (searching) {
                        IconButton(onClick = { searching = false; vm.setSearch("") }) { Icon(Icons.Default.Close, "বন্ধ") }
                    } else {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, "খুঁজুন") }
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "সাজান") }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            listOf(SortBy.DATE to "তারিখ অনুযায়ী", SortBy.NAME to "নাম অনুযায়ী", SortBy.SIZE to "সাইজ অনুযায়ী")
                                .forEach { (s, label) ->
                                    DropdownMenuItem(
                                        text = { Text(if (query.sort == s) "✓ $label" else label) },
                                        onClick = { vm.setSort(s); sortMenu = false },
                                    )
                                }
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "আরও") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("সব চালু করুন") }, onClick = { menu = false; vm.resumeAll() })
                            DropdownMenuItem(text = { Text("সব থামান") }, onClick = { menu = false; vm.pauseAll() })
                            DropdownMenuItem(text = { Text("সম্পন্ন তালিকা মুছুন") }, onClick = { menu = false; confirmClear = true })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("সেটিংস") }, onClick = { menu = false; showSettings = true })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { addUrl = Format.firstUrl(clipboardText(context)).orEmpty() },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("ডাউনলোড") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(query.filter == Filter.ALL, { vm.setFilter(Filter.ALL) }, { Text("সব") })
                FilterChip(query.filter == Filter.ACTIVE, { vm.setFilter(Filter.ACTIVE) }, { Text("চলমান") })
                FilterChip(query.filter == Filter.DONE, { vm.setFilter(Filter.DONE) }, { Text("সম্পন্ন") })
            }
            if (counts.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(query.category == null, { vm.setCategory(null) }, { Text("সব ধরণ") })
                    }
                    items(Category.entries.filter { (counts[it] ?: 0) > 0 }) { c ->
                        FilterChip(
                            selected = query.category == c,
                            onClick = { vm.setCategory(if (query.category == c) null else c) },
                            label = { Text("${c.label} (${counts[c]})") },
                            leadingIcon = { Icon(categoryIcon(c), null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }

            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.search.isNotBlank()) "কিছু পাওয়া যায়নি"
                        else "কোনো ডাউনলোড নেই\nনিচের বাটনে চাপ দিন বা অন্য অ্যাপ থেকে লিংক Share করুন",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                val grouped = query.sort == SortBy.DATE
                LazyColumn(
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (grouped) {
                        list.groupBy { dayLabel(it.entity.finishedAt ?: it.entity.createdAt) }.forEach { (day, group) ->
                            item(key = "h_$day") {
                                Text(
                                    day,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            items(group, key = { it.entity.id }) { item -> DownloadCard(item, vm) }
                        }
                    } else {
                        items(list, key = { it.entity.id }) { item -> DownloadCard(item, vm) }
                    }
                }
            }
        }
    }

    addUrl?.let { initial ->
        AddDownloadDialog(
            initialUrl = initial,
            settings = settings,
            inspect = { vm.inspect(it) },
            onAdd = { url, name, conns, mode, quality ->
                vm.add(url, name, conns, mode, quality)
                addUrl = null
            },
            onDismiss = { addUrl = null },
        )
    }
    if (showSettings) {
        SettingsDialog(settings, onSave = { vm.updateSettings(it); showSettings = false }, onDismiss = { showSettings = false })
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("সম্পন্ন তালিকা মুছবেন?") },
            text = { Text("শুধু তালিকা থেকে সরবে। ডাউনলোড করা ফাইলগুলো ফোনেই থাকবে।") },
            confirmButton = { TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("মুছুন") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("বাতিল") } },
        )
    }
}

@Composable
private fun DownloadCard(item: DownloadItem, vm: MainViewModel) {
    val d = item.entity
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var changeNetwork by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    categoryIcon(item.category), item.category.label,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        d.fileName.ifBlank { d.url },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    val sizeText = when {
                        d.status == DownloadStatus.COMPLETED -> Format.bytes(item.total)
                        item.total > 0 -> "${Format.bytes(item.downloaded)} / ${Format.bytes(item.total)}"
                        else -> Format.bytes(item.downloaded)
                    }
                    val finished = d.finishedAt
                    val statusText = if (d.status == DownloadStatus.COMPLETED && finished != null) {
                        timeLabel(finished)
                    } else Labels.status(d.status)
                    Text(
                        "$statusText • $sizeText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (d.status) {
                    DownloadStatus.RUNNING, DownloadStatus.CONNECTING, DownloadStatus.QUEUED, DownloadStatus.WAITING_NETWORK ->
                        IconButton(onClick = { vm.pause(d.id) }) { Icon(Icons.Default.Pause, "থামান") }
                    DownloadStatus.PAUSED ->
                        IconButton(onClick = { vm.resume(d.id) }) { Icon(Icons.Default.PlayArrow, "চালু করুন") }
                    DownloadStatus.FAILED ->
                        IconButton(onClick = { vm.resume(d.id) }) { Icon(Icons.Default.Refresh, "আবার চেষ্টা") }
                    DownloadStatus.COMPLETED -> Unit
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "আরও") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val target = d.target
                        if (d.status == DownloadStatus.COMPLETED && target != null) {
                            DropdownMenuItem(text = { Text("ফাইল খুলুন") }, onClick = {
                                menu = false
                                openFile(context, vm, target, d.mimeType)
                            })
                            DropdownMenuItem(text = { Text("শেয়ার করুন") }, onClick = {
                                menu = false
                                shareFile(context, vm, target, d.mimeType)
                            })
                        }
                        if (d.status == DownloadStatus.QUEUED || d.status == DownloadStatus.WAITING_NETWORK) {
                            DropdownMenuItem(text = { Text("সারির শুরুতে নিন") }, onClick = {
                                menu = false; vm.moveToTop(d.id)
                            })
                        }
                        if (d.status != DownloadStatus.COMPLETED) {
                            DropdownMenuItem(text = { Text("নেটওয়ার্ক বদলান") }, onClick = {
                                menu = false; changeNetwork = true
                            })
                        }
                        DropdownMenuItem(text = { Text("লিংক কপি করুন") }, onClick = {
                            menu = false
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("url", d.url))
                            Toast.makeText(context, "লিংক কপি হয়েছে", Toast.LENGTH_SHORT).show()
                        })
                        DropdownMenuItem(text = { Text("ডিলিট") }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            }

            if (d.status != DownloadStatus.COMPLETED) {
                Spacer(Modifier.padding(top = 8.dp))
                if (item.total > 0) {
                    LinearProgressIndicator(
                        progress = { (item.downloaded.toFloat() / item.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (d.status == DownloadStatus.RUNNING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Row(Modifier.padding(top = 6.dp)) {
                    val info = buildList {
                        if (d.status == DownloadStatus.RUNNING) {
                            if (item.total > 0) add("${(item.downloaded * 100 / item.total)}%")
                            add(Format.speed(item.speed))
                            if (item.total > 0) Format.eta(item.total - item.downloaded, item.speed).takeIf { it.isNotEmpty() }?.let { add(it) }
                            if (item.connections > 0) add("${item.connections} কানেকশন")
                        }
                        d.quality?.let { add(it) }
                        if (d.networkMode != NetworkMode.AUTO) add(Labels.mode(d.networkMode))
                    }.joinToString(" • ")
                    Text(info, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
                val err = d.error
                if ((d.status == DownloadStatus.FAILED || d.status == DownloadStatus.WAITING_NETWORK) && err != null) {
                    Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            } else if (d.wifiBytes > 0 || d.mobileBytes > 0) {
                Row(Modifier.padding(top = 4.dp)) {
                    Text(
                        "Wi-Fi: ${Format.bytes(d.wifiBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "মোবাইল ডেটা: ${Format.bytes(d.mobileBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        DeleteDialog(
            fileName = d.fileName.ifBlank { d.url },
            completed = d.status == DownloadStatus.COMPLETED,
            onConfirm = { vm.delete(d.id, it); confirmDelete = false },
            onDismiss = { confirmDelete = false },
        )
    }
    if (changeNetwork) {
        ChangeNetworkDialog(
            current = d.networkMode,
            onSelect = { vm.setNetworkMode(d.id, it); changeNetwork = false },
            onDismiss = { changeNetwork = false },
        )
    }
}

@Suppress("DEPRECATION")
fun categoryIcon(c: Category): ImageVector = when (c) {
    Category.VIDEO -> Icons.Default.Movie
    Category.MUSIC -> Icons.Default.MusicNote
    Category.DOCUMENT -> Icons.Default.Description
    Category.APP -> Icons.Default.Android
    Category.ARCHIVE -> Icons.Default.Archive
    Category.IMAGE -> Icons.Default.Image
    Category.OTHER -> Icons.Default.InsertDriveFile
}

private fun dayLabel(millis: Long): String {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val today = cal.timeInMillis
    val day = 24L * 60 * 60 * 1000
    return when {
        millis >= today -> "আজ"
        millis >= today - day -> "গতকাল"
        else -> SimpleDateFormat("d MMMM yyyy", Locale("bn", "BD")).format(Date(millis))
    }
}

private fun timeLabel(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

private fun clipboardText(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
}

private fun openFile(context: Context, vm: MainViewModel, target: String, mime: String?) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(vm.shareableUri(target), mime ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
        .onFailure { Toast.makeText(context, "ফাইলটি খোলার মতো অ্যাপ পাওয়া যায়নি", Toast.LENGTH_SHORT).show() }
}

private fun shareFile(context: Context, vm: MainViewModel, target: String, mime: String?) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime ?: "*/*"
        putExtra(Intent.EXTRA_STREAM, vm.shareableUri(target))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "শেয়ার করুন")) }
}
