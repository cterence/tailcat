package com.tailcat.android.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tailcat.android.TailcatClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SelectedFile(val uri: Uri, val name: String, val size: Long)

/**
 * An in-flight or finished transfer. Hoisted above the tabs in
 * MainActivity and run on the app-level scope, so it keeps running
 * while the Send tab is out of composition — the old screen-local
 * coroutine was cancelled by the tab switch, killing the upload
 * mid-file.
 */
data class SendTransfer(
    val files: List<SelectedFile>,
    val phase: String, // connecting / sending / done / cancelled / error
    val index: Int = 0,
    val sent: Long = 0,
    val total: Long = 0,
    val path: String? = null,
    val status: String = "",
    val error: String? = null,
)

@Composable
fun SendScreen(
    address: String,
    transfer: SendTransfer?,
    onTransferChange: ((SendTransfer?) -> SendTransfer?) -> Unit,
    onSendJobChange: (Job?) -> Unit,
    cancelSend: () -> Unit,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
    permCache: PermissionCache,
) {
    val context = LocalContext.current
    var selectedFiles by remember { mutableStateOf<List<SelectedFile>>(emptyList()) }

    // Probe write permission when address changes — use cache to avoid re-probing.
    val cachedInfo = if (address.isNotEmpty()) permCache.get(address) else null
    var canWrite by remember { mutableStateOf(cachedInfo?.canWrite ?: false) }
    var probing by remember { mutableStateOf(cachedInfo == null && address.isNotEmpty()) }

    LaunchedEffect(address) {
        if (address.isNotEmpty()) {
            probing = true
            try {
                val info = probeAddress(address, permCache)
                canWrite = info.canWrite
            } catch (_: Exception) {
                canWrite = false
            }
            probing = false
        } else {
            canWrite = false
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedFiles = uris.map { uri ->
                val (name, size) = queryFileInfo(context, uri)
                SelectedFile(uri, name ?: "file", size)
            }
            // New selection clears any previous transfer result.
            onTransferChange { null }
        }
    }

    val busy = transfer?.phase == "connecting" || transfer?.phase == "sending"
    val totalSize = selectedFiles.sumOf { it.size }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Send", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        if (address.isEmpty()) {
            Text(
                "Connect to a remote device first (Connect tab).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        if (probing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Checking permissions...", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
        }

        if (!probing && !canWrite) {
            Text(
                "This server does not allow file uploads.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }

        if (!probing && canWrite) {
            Button(
                onClick = { filePicker.launch("*/*") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                Text(if (selectedFiles.isNotEmpty()) "Selected ${selectedFiles.size} file(s)" else "Choose files (long press to select multiple)")
            }

            if (selectedFiles.isNotEmpty()) {
                Text(
                    "Total: ${formatBytes(totalSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                LazyColumn(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                    items(selectedFiles) { file ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(file.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(formatBytes(file.size), style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.height(8.dp))

            // While a transfer runs, this button is the cancel
            // control: the go-side abort makes it effective mid-file
            // (within one progress interval) instead of at the next
            // coroutine suspension point.
            Button(
                onClick = {
                    if (busy) {
                        cancelSend()
                        return@Button
                    }
                    if (selectedFiles.isEmpty()) return@Button
                    val files = selectedFiles
                    onTransferChange { SendTransfer(files = files, phase = "connecting") }
                    onSendJobChange(scope.launch {
                        var pathPoller: Job? = null
                        try {
                            withContext(Dispatchers.IO) {
                                val client = TailcatClient(address, "")
                                client.ping()
                                val sftp = client.dialSFTP()
                                var tmpFile: java.io.File? = null
                                try {
                                    onTransferChange {
                                        it?.copy(
                                            phase = "sending",
                                            status = "Sending 1/${files.size}: ${files[0].name}",
                                            sent = 0,
                                            total = files[0].size,
                                        )
                                    }
                                    // Show the live connection path while the
                                    // transfer runs: disco pings reveal direct
                                    // upgrades within a tick or two. The pings
                                    // run on IO so the UI thread never blocks.
                                    pathPoller = scope.launch {
                                        while (true) {
                                            val res = runCatching {
                                                withContext(Dispatchers.IO) { client.discoPing() }
                                            }.getOrNull()
                                            if (res != null) {
                                                onTransferChange { t ->
                                                    t?.copy(path = if (res.direct) "direct" else "relayed (${res.via})")
                                                }
                                            }
                                            delay(2000)
                                        }
                                    }

                                    for ((i, file) in files.withIndex()) {
                                        ensureActive()
                                        onTransferChange {
                                            it?.copy(
                                                index = i,
                                                status = "Sending ${i + 1}/${files.size}: ${file.name}",
                                                sent = 0,
                                                total = file.size,
                                            )
                                        }
                                        tmpFile = java.io.File(context.cacheDir, "tailcat-upload-${file.name}")
                                        context.contentResolver.openInputStream(file.uri)?.use { input ->
                                            java.io.FileOutputStream(tmpFile).use { out ->
                                                input.copyTo(out)
                                            }
                                        } ?: throw IllegalStateException("Cannot open ${file.name}")
                                        sftp.uploadFile(tmpFile.absolutePath, "/${file.name}") { sent, total ->
                                            // Cooperative cancel: the job is cancelled from
                                            // the UI; the next progress tick tells the Go
                                            // side to abort, so even a huge file stops
                                            // within one progress interval.
                                            if (!isActive) sftp.cancel()
                                            onTransferChange { t -> t?.copy(sent = sent, total = total) }
                                        }
                                        tmpFile?.delete()
                                        tmpFile = null
                                    }
                                } finally {
                                    // Runs on cancel too: release the tunnel
                                    // session and drop any partial temp file.
                                    pathPoller?.cancel()
                                    sftp.close()
                                    client.close()
                                    tmpFile?.delete()
                                }
                            }
                            onTransferChange { it?.copy(phase = "done") }
                        } catch (e: CancellationException) {
                            onTransferChange { it?.copy(phase = "cancelled") }
                        } catch (e: Exception) {
                            if (!isActive) {
                                // Cancel observed as an error from the Go side.
                                onTransferChange { it?.copy(phase = "cancelled") }
                            } else {
                                onTransferChange { it?.copy(phase = "error", error = e.message) }
                            }
                        }
                    })
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = busy || (!busy && selectedFiles.isNotEmpty()),
                colors = if (busy) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ) else ButtonDefaults.buttonColors(),
            ) {
                Text(if (busy) "Cancel" else "Send ${selectedFiles.size} file(s)")
            }

            when (transfer?.phase) {
                "connecting" -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Establishing tunnel connection...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                "sending" -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        transfer.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (transfer.total > 0) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { transfer.sent.toFloat() / transfer.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${formatBytes(transfer.sent)} / ${formatBytes(transfer.total)} (${transfer.sent * 100 / transfer.total}%)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    transfer.path?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Connection: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it == "direct") MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                "cancelled" -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Transfer cancelled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                "error" -> {
                    transfer.error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("Error: $it", color = MaterialTheme.colorScheme.error)
                    }
                }
                "done" -> {
                    Spacer(Modifier.height(8.dp))
                    Text("${transfer.files.size} file(s) sent successfully.", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private fun queryFileInfo(context: Context, uri: Uri): Pair<String?, Long> {
    var name: String? = null
    var size = 0L
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (cursor.moveToFirst()) {
            if (nameIdx >= 0) name = cursor.getString(nameIdx)
            if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
        }
    }
    return Pair(name, size)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var size = bytes.toDouble() / 1024
    var unit = 0
    while (size >= 1024 && unit < units.size - 1) {
        size /= 1024
        unit++
    }
    return "%.1f %s".format(size, units[unit])
}
