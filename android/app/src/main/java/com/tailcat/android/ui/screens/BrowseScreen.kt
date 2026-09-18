package com.tailcat.android.ui.screens

import android.net.Uri
import androidx.compose.foundation.clickable
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
import com.tailcat.android.TailcatSFTPClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BrowseScreen(
    address: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sftpFiles by remember { mutableStateOf<List<TailcatSFTPClient.RemoteFileEntry>>(emptyList()) }
    var sftpPath by remember { mutableStateOf("/") }
    var sftpLoading by remember { mutableStateOf(false) }
    var sftpError by remember { mutableStateOf<String?>(null) }
    var sftpStatus by remember { mutableStateOf<String?>(null) }
    var downloadedRemoteName by remember { mutableStateOf<String?>(null) }
    var filterQuery by remember { mutableStateOf("") }

    // Persistent client + SFTP session for browsing
    var browseClient by remember { mutableStateOf<TailcatClient?>(null) }
    var browseSftp by remember { mutableStateOf<TailcatSFTPClient?>(null) }

    // Clean up when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            browseSftp?.close()
            browseClient?.close()
        }
    }

    // Reset connection when address changes
    LaunchedEffect(address) {
        if (browseClient != null) {
            browseSftp?.close()
            browseClient?.close()
            browseSftp = null
            browseClient = null
            sftpFiles = emptyList()
            sftpPath = "/"
            filterQuery = ""
        }
    }

    fun withSftp(block: (TailcatSFTPClient) -> Unit) {
        sftpError = null
        sftpStatus = null
        sftpLoading = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (browseClient == null || browseSftp == null) {
                        val client = TailcatClient(address, "")
                        client.pingWithTimeout(15)
                        browseClient = client
                        browseSftp = client.dialSFTP()
                    }
                    block(browseSftp!!)
                }
            } catch (e: Exception) {
                sftpError = e.message
                withContext(Dispatchers.IO) {
                    browseSftp?.close()
                    browseClient?.close()
                }
                browseSftp = null
                browseClient = null
            }
            sftpLoading = false
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Browse", style = MaterialTheme.typography.headlineMedium)

        if (address.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Connect to a remote device first (Connect tab).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Spacer(Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    withSftp { sftp ->
                        sftpFiles = sftp.listDir(sftpPath)
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !sftpLoading,
            ) {
                if (sftpLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(4.dp))
                }
                Text("List files")
            }
        }

        sftpError?.let {
            Spacer(Modifier.height(4.dp))
            Text("Error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        sftpStatus?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }

        if (sftpFiles.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Path: $sftpPath", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))

            OutlinedTextField(
                value = filterQuery,
                onValueChange = { filterQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Filter files") },
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
        }

        val displayedFiles = if (filterQuery.isEmpty()) {
            sftpFiles
        } else {
            sftpFiles.filter { it.isDir || it.name.contains(filterQuery, ignoreCase = true) }
        }

        if (displayedFiles.isNotEmpty()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (sftpPath != "/" && filterQuery.isEmpty()) {
                    item(key = "..") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val parent = sftpPath.substringBeforeLast("/")
                                    sftpPath = if (parent.isEmpty()) "/" else parent
                                    downloadedRemoteName = null
                                    withSftp { sftp ->
                                        sftpFiles = sftp.listDir(sftpPath)
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "..",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        HorizontalDivider()
                    }
                }
                items(displayedFiles, key = { "${sftpPath}/${it.name}/${it.isDir}" }) { file ->
                    val downloading = remember { mutableStateOf(false) }
                    // Download progress: bytes written so far (of total).
                    var downloadProgress by remember { mutableStateOf(0L to 0L) }
                    Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (file.isDir) Modifier.clickable {
                                sftpPath = if (sftpPath == "/") "/${file.name}" else "$sftpPath/${file.name}"
                                downloadedRemoteName = null
                                filterQuery = ""
                                withSftp { sftp ->
                                    sftpFiles = sftp.listDir(sftpPath)
                                }
                            } else Modifier)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (file.isDir) {
                            Text(
                                "[${file.name}]",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Text(
                                file.name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(formatSize(file.size), style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(8.dp))
                            if (downloading.value) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else if (downloadedRemoteName == file.name) {
                                TextButton(onClick = {
                                    val mime = getMimeType(file.name)
                                    val projection = arrayOf(
                                        android.provider.MediaStore.MediaColumns._ID,
                                        android.provider.MediaStore.MediaColumns.MIME_TYPE
                                    )
                                    val selection = "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                                    val selectionArgs = arrayOf(file.name, "%Download%")
                                    val sortOrder = "${android.provider.MediaStore.MediaColumns._ID} DESC"
                                    var contentUri: Uri? = null
                                    context.contentResolver.query(
                                        android.provider.MediaStore.Files.getContentUri("external"),
                                        projection,
                                        selection,
                                        selectionArgs,
                                        sortOrder
                                    )?.use { cursor ->
                                        if (cursor.moveToFirst()) {
                                            val id = cursor.getLong(0)
                                            contentUri = Uri.withAppendedPath(
                                                android.provider.MediaStore.Files.getContentUri("external"),
                                                id.toString()
                                            )
                                        }
                                    }
                                    if (contentUri != null) {
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                            setDataAndType(contentUri, mime)
                                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        try {
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            sftpError = "Cannot open: ${e.message}"
                                        }
                                    } else {
                                        sftpError = "File not found in MediaStore"
                                    }
                                }) {
                                    Text("Open")
                                }
                            } else {
                                TextButton(onClick = {
                                    downloading.value = true
                                    downloadProgress = 0L to file.size
                                    sftpError = null
                                    sftpStatus = null
                                    downloadedRemoteName = null
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                val sftp = browseSftp ?: run {
                                                    val client = TailcatClient(address, "")
                                                    client.ping()
                                                    browseClient = client
                                                    val s = client.dialSFTP()
                                                    browseSftp = s
                                                    s
                                                }
                                                val downloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                                                val baseName = file.name.substringBeforeLast('.', file.name)
                                                val ext = if (file.name.contains('.')) ".${file.name.substringAfterLast('.')}" else ""
                                                var localFile = java.io.File(downloads, file.name)
                                                var n = 1
                                                while (localFile.exists()) {
                                                    localFile = java.io.File(downloads, "$baseName($n)$ext")
                                                    n++
                                                }
                                                sftp.downloadFile("$sftpPath/${file.name}", localFile.absolutePath) { sent, total ->
                                                    downloadProgress = sent to total
                                                }
                                                downloadedRemoteName = file.name
                                            }
                                            sftpStatus = "Downloaded ${file.name} to Downloads"
                                        } catch (e: Exception) {
                                            sftpError = e.message
                                        }
                                        downloading.value = false
                                    }
                                }) {
                                    Text("Download")
                                }
                            }
                        }
                    }
                    // Per-row download progress, full width under the
                    // row rather than squeezed into it.
                    if (!file.isDir && downloading.value) {
                        val (sent, total) = downloadProgress
                        if (total > 0) {
                            LinearProgressIndicator(
                                progress = { sent.toFloat() / total },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${formatSize(sent)} / ${formatSize(total)} (${sent * 100 / total}%)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                "Downloading...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var size = bytes.toDouble() / 1024
    var unit = 0
    while (size >= 1024 && unit < units.size - 1) {
        size /= 1024
        unit++
    }
    return "%.1f%s".format(size, units[unit])
}

private fun getMimeType(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "mp4", "m4v", "mkv", "webm", "avi", "mov" -> "video/*"
        "mp3", "wav", "flac", "ogg", "m4a", "aac" -> "audio/*"
        "txt", "log", "md", "csv", "json", "xml", "yaml", "yml" -> "text/plain"
        "html", "htm" -> "text/html"
        "zip", "gz", "tar", "bz2", "xz", "7z", "rar" -> "application/*"
        "apk" -> "application/vnd.android.package-archive"
        else -> "*/*"
    }
}
