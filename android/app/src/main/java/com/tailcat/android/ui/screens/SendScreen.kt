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
import com.tailcat.android.TailcatSFTPClient
import com.tailcat.android.streamToConn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SendState(
    val token: String = "",
    val fileName: String? = null,
    val fileUri: Uri? = null,
    val fileSize: Long = 0,
    val sending: Boolean = false,
    val sent: Long = 0,
    val total: Long = 0,
    val error: String? = null,
    val done: Boolean = false,
)

@Composable
fun SendScreen(
    initialToken: String,
    modifier: Modifier = Modifier,
    receiveToken: String = "",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(SendState(token = initialToken)) }

    // Sync external token changes (e.g. from Scan tab).
    LaunchedEffect(initialToken) {
        if (initialToken.isNotEmpty()) {
            state = state.copy(token = initialToken)
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val (name, size) = queryFileInfo(context, uri)
            state = state.copy(
                fileUri = uri,
                fileName = name,
                fileSize = size,
                done = false,
                error = null,
            )
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Send", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = state.token,
            onValueChange = { state = state.copy(token = it, done = false, error = null) },
            label = { Text("Destination token") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        // SFTP browse (default mode)
        Spacer(Modifier.height(16.dp))
        Text("Browse remote files:", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))

        var sftpFiles by remember { mutableStateOf<List<TailcatSFTPClient.RemoteFileEntry>>(emptyList()) }
        var sftpPath by remember { mutableStateOf("/") }
        var sftpLoading by remember { mutableStateOf(false) }
        var sftpError by remember { mutableStateOf<String?>(null) }
        var sftpStatus by remember { mutableStateOf<String?>(null) }
        var downloadedFile by remember { mutableStateOf<java.io.File?>(null) }
        var downloadedRemoteName by remember { mutableStateOf<String?>(null) }

        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    sftpError = null
                    sftpStatus = null
                    sftpLoading = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val client = TailcatClient(state.token, "")
                                client.ping()
                                val sftp = client.dialSFTP()
                                sftpFiles = sftp.listDir(sftpPath)
                                sftp.close()
                                client.close()
                            }
                        } catch (e: Exception) {
                            sftpError = e.message
                        }
                        sftpLoading = false
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !sftpLoading && state.token.isNotEmpty(),
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
            LazyColumn(modifier = Modifier.weight(1f)) {
                // Show ".." to go up when not at root
                if (sftpPath != "/") {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = {
                                val parent = sftpPath.substringBeforeLast("/")
                                sftpPath = if (parent.isEmpty()) "/" else parent
                                sftpError = null
                                sftpStatus = null
                                downloadedFile = null
                                downloadedRemoteName = null
                                sftpLoading = true
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            val client = TailcatClient(state.token, "")
                                            client.ping()
                                            val sftp = client.dialSFTP()
                                            sftpFiles = sftp.listDir(sftpPath)
                                            sftp.close()
                                            client.close()
                                        }
                                    } catch (e: Exception) {
                                        sftpError = e.message
                                    }
                                    sftpLoading = false
                                }
                            }) {
                                Text("..")
                            }
                        }
                        HorizontalDivider()
                    }
                }
                items(sftpFiles) { file ->
                    val downloading = remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (file.isDir) {
                            TextButton(
                                onClick = {
                                    sftpPath = if (sftpPath == "/") "/${file.name}" else "$sftpPath/${file.name}"
                                    sftpError = null
                                    sftpStatus = null
                                    downloadedFile = null
                                downloadedRemoteName = null
                                    sftpLoading = true
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                val client = TailcatClient(state.token, "")
                                                client.ping()
                                                val sftp = client.dialSFTP()
                                                sftpFiles = sftp.listDir(sftpPath)
                                                sftp.close()
                                                client.close()
                                            }
                                        } catch (e: Exception) {
                                            sftpError = e.message
                                        }
                                        sftpLoading = false
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("[${file.name}]")
                            }
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
                                    var contentUri: android.net.Uri? = null
                                    context.contentResolver.query(
                                        android.provider.MediaStore.Files.getContentUri("external"),
                                        projection,
                                        selection,
                                        selectionArgs,
                                        sortOrder
                                    )?.use { cursor ->
                                        if (cursor.moveToFirst()) {
                                            val id = cursor.getLong(0)
                                            contentUri = android.net.Uri.withAppendedPath(
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
                                    sftpError = null
                                    sftpStatus = null
                                    downloadedFile = null
                                downloadedRemoteName = null
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                val client = TailcatClient(state.token, "")
                                                client.ping()
                                                val sftp = client.dialSFTP()
                                                val downloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                                                val baseName = file.name.substringBeforeLast('.', file.name)
                                                val ext = if (file.name.contains('.')) ".${file.name.substringAfterLast('.')}" else ""
                                                var localFile = java.io.File(downloads, file.name)
                                                var n = 1
                                                while (localFile.exists()) {
                                                    localFile = java.io.File(downloads, "$baseName($n)$ext")
                                                    n++
                                                }
                                                sftp.downloadFile("$sftpPath/${file.name}", localFile.absolutePath)
                                                sftp.close()
                                                client.close()
                                                downloadedFile = localFile
                                                downloadedRemoteName = file.name
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
                    HorizontalDivider()
                }
            }
        }

        // Raw stream upload (secondary)
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        Text("Or send a file via SFTP:", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))

        Button(
            onClick = { filePicker.launch("*/*") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.fileName != null) "File: ${state.fileName}" else "Choose file")
        }

        if (state.fileSize > 0) {
            Text(
                "${state.fileSize} bytes",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                if (state.token.isEmpty() || state.fileUri == null) return@Button
                state = state.copy(sending = true, sent = 0, done = false, error = null)
                val token = state.token
                val uri = state.fileUri!!
                val fileName = state.fileName ?: "file"
                val total = state.fileSize
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            // Copy the content URI to a temp file first,
                            // then upload via SFTP.
                            val tmpFile = java.io.File(context.cacheDir, "tailcat-upload-$fileName")
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                java.io.FileOutputStream(tmpFile).use { out ->
                                    input.copyTo(out)
                                }
                            } ?: throw IllegalStateException("Cannot open file")

                            val client = TailcatClient(token, "")
                            client.ping()
                            val sftp = client.dialSFTP()
                            sftp.uploadFile(tmpFile.absolutePath, "/$fileName")
                            sftp.close()
                            client.close()
                            tmpFile.delete()
                        }
                        state = state.copy(sending = false, done = true)
                    } catch (e: Exception) {
                        state = state.copy(sending = false, error = e.message)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.sending && state.token.isNotEmpty() && state.fileUri != null,
        ) {
            if (state.sending) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (state.sending) "Sending..." else "Send file")
        }

        if (state.total > 0 && state.sending) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (state.total > 0) state.sent.toFloat() / state.total else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${state.sent} / ${state.total} bytes",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text("Error: $it", color = MaterialTheme.colorScheme.error)
        }

        if (state.done) {
            Spacer(Modifier.height(8.dp))
            Text("File sent successfully.", color = MaterialTheme.colorScheme.primary)
        }

        if (receiveToken.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("Or send your receive token so they can send files to you:", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    state = state.copy(sending = true, sent = 0, done = false, error = null)
                    val token = state.token
                    val payload = receiveToken.toByteArray(Charsets.UTF_8)
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val client = TailcatClient(token, "")
                                client.ping()
                                val conn = client.dial(1L)
                                conn.write(payload)
                                conn.closeWrite()
                                // Read until EOF for delivery confirmation
                                while (conn.read() != null) { }
                                conn.close()
                                client.close()
                            }
                            state = state.copy(sending = false, done = true)
                        } catch (e: Exception) {
                            state = state.copy(sending = false, error = e.message)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.sending && state.token.isNotEmpty(),
            ) {
                if (state.sending) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.sending) "Sending token..." else "Send my token to this device")
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
