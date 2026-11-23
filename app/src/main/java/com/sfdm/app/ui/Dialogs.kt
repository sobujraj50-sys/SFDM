package com.sfdm.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sfdm.app.data.AppSettings
import com.sfdm.app.data.NetworkMode
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import com.sfdm.app.engine.LinkInfo
import com.sfdm.app.engine.LinkResolver
import com.sfdm.app.util.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun NetworkModePicker(selected: NetworkMode, onSelect: (NetworkMode) -> Unit) {
    Column {
        NetworkMode.entries.forEach { m ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = m == selected, onClick = { onSelect(m) })
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = m == selected, onClick = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(Labels.mode(m), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        Labels.modeHint(m),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun AddDownloadDialog(
    initialUrl: String,
    settings: AppSettings,
    inspect: suspend (String) -> LinkInfo,
    onAdd: (url: String, name: String?, connections: Int, mode: NetworkMode, quality: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(initialUrl) }
    var name by remember { mutableStateOf("") }
    var nameEdited by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var connections by remember { mutableFloatStateOf(settings.defaultConnections.toFloat()) }
    var mode by remember { mutableStateOf(settings.defaultNetworkMode) }
    var info by remember { mutableStateOf<LinkInfo?>(null) }
    var infoError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf<String?>(null) }
    val trimmed = url.trim()
    val valid = trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)

    // Look up name, size and qualities shortly after the link stops changing.
    LaunchedEffect(trimmed) {
        info = null; infoError = null; quality = null
        if (!valid) return@LaunchedEffect
        delay(600)
        loading = true
        try {
            val i = inspect(trimmed)
            info = i
            quality = i.variants.firstOrNull()?.quality?.ifBlank { null }
            if (!nameEdited) name = i.fileName.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            infoError = e.message
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("নতুন ডাউনলোড") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("লিংক (URL)") },
                    singleLine = false, maxLines = 4,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                    isError = url.isNotBlank() && !valid,
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it; nameEdited = true },
                    label = { Text("ফাইলের নাম") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("  লিংকের তথ্য আনা হচ্ছে…", style = MaterialTheme.typography.bodySmall)
                    }
                    infoError != null -> Text(
                        infoError ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    info != null -> LinkInfoView(info!!, quality) { quality = it }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { advanced = !advanced }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Advanced", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(Labels.mode(mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
                if (advanced) {
                    Text("কানেকশন: ${connections.roundToInt()}", style = MaterialTheme.typography.bodyMedium)
                    Slider(value = connections, onValueChange = { connections = it }, valueRange = 1f..32f, steps = 30)
                    HorizontalDivider()
                    Text("নেটওয়ার্ক", style = MaterialTheme.typography.bodyMedium)
                    NetworkModePicker(mode) { mode = it }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onAdd(trimmed, name.trim().ifBlank { null }, connections.roundToInt(), mode, quality)
            }) { Text("ডাউনলোড") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } },
    )
}

@Composable
private fun LinkInfoView(info: LinkInfo, selected: String?, onSelect: (String) -> Unit) {
    val single = info.variants.singleOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (single != null && single.quality.isBlank()) {
            val size = if (single.sizeBytes > 0) Format.bytes(single.sizeBytes) else "সাইজ অজানা"
            val resume = if (single.resumable) "রিজিউম করা যাবে" else "রিজিউম সাপোর্ট নেই"
            Text("$size • $resume", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("ভিডিও কোয়ালিটি", style = MaterialTheme.typography.titleSmall)
            info.variants.forEach { v ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = v.quality == selected, onClick = { onSelect(v.quality) })
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = v.quality == selected, onClick = null)
                    Text(
                        "  ${qualityLabel(v.quality)}",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (v.sizeBytes > 0) Format.bytes(v.sizeBytes) else "?",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun qualityLabel(q: String) = when (q) {
    LinkResolver.HD -> "HD (উচ্চ মান)"
    LinkResolver.SD -> "SD (কম ডেটা)"
    else -> q
}

@Composable
fun SettingsDialog(settings: AppSettings, onSave: (AppSettings) -> Unit, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf(settings.defaultNetworkMode) }
    var connections by remember { mutableFloatStateOf(settings.defaultConnections.toFloat()) }
    var parallel by remember { mutableFloatStateOf(settings.maxParallelDownloads.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("সেটিংস") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ডিফল্ট নেটওয়ার্ক", style = MaterialTheme.typography.titleSmall)
                NetworkModePicker(mode) { mode = it }
                HorizontalDivider()
                Text("প্রতি ডাউনলোডে কানেকশন: ${connections.roundToInt()}")
                Slider(value = connections, onValueChange = { connections = it }, valueRange = 1f..32f, steps = 30)
                Text("একসাথে সর্বোচ্চ ডাউনলোড: ${parallel.roundToInt()}")
                Slider(value = parallel, onValueChange = { parallel = it }, valueRange = 1f..10f, steps = 8)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    AppSettings(
                        defaultNetworkMode = mode,
                        defaultConnections = connections.roundToInt(),
                        maxParallelDownloads = parallel.roundToInt(),
                    ),
                )
            }) { Text("সেভ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } },
    )
}

@Composable
fun DeleteDialog(fileName: String, completed: Boolean, onConfirm: (deleteFile: Boolean) -> Unit, onDismiss: () -> Unit) {
    var deleteFile by remember { mutableStateOf(!completed) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ডিলিট করবেন?") },
        text = {
            Column {
                Text(fileName)
                if (completed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = deleteFile, onCheckedChange = { deleteFile = it })
                        Text("ফোন থেকে ফাইলটিও মুছে ফেলুন")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(deleteFile) }) { Text("ডিলিট") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } },
    )
}

@Composable
fun ChangeNetworkDialog(current: NetworkMode, onSelect: (NetworkMode) -> Unit, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("এই ডাউনলোডের নেটওয়ার্ক") },
        text = { NetworkModePicker(mode) { mode = it } },
        confirmButton = { TextButton(onClick = { onSelect(mode) }) { Text("ঠিক আছে") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } },
    )
}
