package com.sfdm.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sfdm.app.data.DownloadStatus
import com.sfdm.app.data.NetworkMode
import com.sfdm.app.util.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel, incomingUrl: String?, onIncomingConsumed: () -> Unit) {
    val list by vm.items.collectAsState()
    val filter by vm.filter.collectAsState()
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current

    var addUrl by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(incomingUrl) {
        if (incomingUrl != null) {
            addUrl = incomingUrl
            onIncomingConsumed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SF Download Manager") },
                actions = {
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "সেটিংস") }
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
                FilterChip(filter == Filter.ALL, { vm.filter.value = Filter.ALL }, { Text("সব") })
                FilterChip(filter == Filter.ACTIVE, { vm.filter.value = Filter.ACTIVE }, { Text("চলমান") })
                FilterChip(filter == Filter.DONE, { vm.filter.value = Filter.DONE }, { Text("সম্পন্ন") })
            }
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "কোনো ডাউনলোড নেই\nনিচের বাটনে চাপ দিন বা অন্য অ্যাপ থেকে লিংক Share করুন",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(list, key = { it.entity.id }) { item -> DownloadCard(item, vm) }
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
                Column(Modifier.weight(1f)) {
                    Text(
                        d.fileName.ifBlank { d.url },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    val sizeText = if (item.total > 0) "${Format.bytes(item.downloaded)} / ${Format.bytes(item.total)}"
                    else Format.bytes(item.downloaded)
                    Text(
                        "${Labels.status(d.status)} • $sizeText",
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
                        if (d.status == DownloadStatus.COMPLETED && d.target != null) {
                            DropdownMenuItem(text = { Text("ফাইল খুলুন") }, onClick = {
                                menu = false
                                openFile(context, vm, d.target, d.mimeType)
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
                            add(Format.speed(item.speed))
                            if (item.total > 0) Format.eta(item.total - item.downloaded, item.speed).takeIf { it.isNotEmpty() }?.let { add(it) }
                            if (item.connections > 0) add("${item.connections} কানেকশন")
                        }
                        d.quality?.let { add(it) }
                        if (d.networkMode != NetworkMode.AUTO) add(Labels.mode(d.networkMode))
                    }.joinToString(" • ")
                    Text(info, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
                if ((d.status == DownloadStatus.FAILED || d.status == DownloadStatus.WAITING_NETWORK) && d.error != null) {
                    Text(d.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
