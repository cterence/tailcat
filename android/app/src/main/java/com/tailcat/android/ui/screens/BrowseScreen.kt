package com.tailcat.android.ui.screens

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tailcat.android.TailcatClient
import com.tailcat.android.TailcatSFTPClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Browse tab state, hoisted above the tabs in MainActivity: the SFTP
 * connection, the listing, the current directory, the filter, the
 * scroll position, and any in-flight downloads all survive tab
 * switches. The connection's lifecycle belongs to the address, not
 * the tab: it is rebuilt when the address changes (disconnect, clear,
 * a new scan) and closed on app exit — never by tabbing out and back.
 */
class BrowseState {
    var files by mutableStateOf<List<TailcatSFTPClient.RemoteFileEntry>>(emptyList())
    var path by mutableStateOf("/")
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)
    var downloadedName by mutableStateOf<String?>(null)
    var filter by mutableStateOf("")

    // The address the client below is connected to; "" when none.
    var connectedAddress by mutableStateOf("")
    var client by mutableStateOf<TailcatClient?>(null)
    var sftp by mutableStateOf<TailcatSFTPClient?>(null)

    // Bumped when a failed operation closed the session: the connect
    // effect keys on this and reconnects on its own. The tick exists
    // so the effect never keys on the session itself — it sets the
    // session mid-run, which would flip that key under it and cancel
    // the effect in the middle of the first listing.
    var reconnectTick by mutableStateOf(0)

    // Scroll position, kept with the listing so returning to the tab
    // restores it.
    val listState = LazyListState()
    // The path the list was last snapped to the top for; navigation
    // snaps, tab re-entry does not. Plain field, not snapshot state.
    var snappedPath: String? = null

    // One download's UI state per remote path, and the job behind it.
    // The map is snapshot state, so progress writes from the Go
    // callback thread are safe; jobs are touched only from the main
    // thread (start and cancel).
    val downloads = mutableStateMapOf<String, Download>()
    val downloadJobs = mutableMapOf<String, Job>()

    /** A download: in flight with progress, or finished. */
    data class Download(
        val active: Boolean,
        val sent: Long = 0,
        val total: Long = 0,
    )

    /** Closes the SFTP session and client, if any. */
    fun closeConnection() {
        sftp?.close()
        client?.close()
        sftp = null
        client = null
        connectedAddress = ""
    }

    /**
     * Resets the listing for a new address (or none): directory,
     * filter, and any downloads, whose jobs are cancelled first.
     */
    fun resetListing() {
        for (job in downloadJobs.values) job.cancel()
        downloadJobs.clear()
        downloads.clear()
        files = emptyList()
        path = "/"
        filter = ""
        downloadedName = null
        status = null
        error = null
        loading = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    address: String,
    state: BrowseState,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
    permCache: PermissionCache,
) {
    val context = LocalContext.current

    // Permissions decide whether browsing is possible at all: a
    // write-only drop box denies listings, so connecting and listing
    // would only produce an error. The shared probe cache carries the
    // answer (whichever tab probed first), and the watchdog's
    // periodic recheck keeps it fresh — a serve mode change re-enables
    // or disables the tab live.
    val permInfo = if (address.isNotEmpty()) permCache.get(address) else null
    var permProbing by remember(address) { mutableStateOf(permInfo == null && address.isNotEmpty()) }
    var permFailed by remember(address) { mutableStateOf(false) }
    val canRead = permInfo?.canRead ?: permFailed

    LaunchedEffect(address) {
        if (address.isNotEmpty() && permCache.get(address) == null && !permFailed) {
            try {
                probeAddress(address, permCache)
            } catch (_: Exception) {
                // Unreachable or unprobed: fall through to the
                // listing attempt so the real error surfaces.
                permFailed = true
            }
            permProbing = false
        }
    }

    // Directory navigation snaps the list back to the top; every
    // other recomposition (including tab re-entry) keeps the scroll
    // position.
    LaunchedEffect(state.path) {
        if (state.snappedPath != state.path) {
            state.snappedPath = state.path
            state.listState.scrollToItem(0)
        }
    }

    // The connection lifecycle, tied to the address and never to the
    // tab: first entry with an address connects and lists the root;
    // an address change rebuilds from scratch; tabbing out and back
    // in with the same address is a no-op — no re-probe, no reload.
    // A session lost to an error (closed by withSftp) reconnects and
    // relists the current directory on the next tab entry.
    // Keyed on the reconnect tick as well: a session that died
    // mid-use (server restart, network drop) is closed by withSftp,
    // which bumps the tick, and this effect then reconnects and
    // relists on its own — the tab heals instead of spinning forever.
    LaunchedEffect(address, canRead, state.reconnectTick) {
        if (address.isEmpty()) {
            withContext(Dispatchers.IO) { state.closeConnection() }
            state.resetListing()
            return@LaunchedEffect
        }
        // A write-only server denies listings: don't connect, don't
        // list — the static message below replaces the tab. Ordered
        // before the connected check so a serve mode change to
        // write-only tears the session down.
        if (!canRead) {
            withContext(Dispatchers.IO) { state.closeConnection() }
            state.resetListing()
            return@LaunchedEffect
        }
        if (state.connectedAddress == address && state.sftp != null) return@LaunchedEffect
        val fresh = state.connectedAddress != address
        if (fresh) {
            withContext(Dispatchers.IO) { state.closeConnection() }
            state.resetListing()
        }
        state.loading = true
        state.error = null
        try {
            withContext(Dispatchers.IO) {
                if (state.sftp == null) {
                    val client = TailcatClient(address, "")
                    client.ping()
                    state.client = client
                    state.sftp = client.dialSFTP()
                }
                state.connectedAddress = address
                state.files = state.sftp!!.listDir(state.path)
            }
        } catch (e: CancellationException) {
            // Tabbing away cancels this effect mid-connect (Compose:
            // "the coroutine scope left the composition") — not a
            // connection failure, never displayed as one.
            throw e
        } catch (e: Exception) {
            state.error = e.message
        }
        state.loading = false
    }

    fun withSftp(block: (TailcatSFTPClient) -> Unit) {
        state.error = null
        state.status = null
        state.loading = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (state.client == null || state.sftp == null) {
                        val client = TailcatClient(address, "")
                        client.pingWithTimeout(15)
                        state.client = client
                        state.sftp = client.dialSFTP()
                    }
                    block(state.sftp!!)
                }
            } catch (e: CancellationException) {
                // App-exit cancellation: nothing to clean up toward
                // the user, and never an error to display.
                throw e
            } catch (e: Exception) {
                state.error = e.message
                withContext(Dispatchers.IO) {
                    state.sftp?.close()
                    state.client?.close()
                }
                state.sftp = null
                state.client = null
                // Tell the connect effect to reconnect and relist.
                state.reconnectTick++
            }
            state.loading = false
        }
    }

    // Derived loading: also true while an address is set but the
    // session has not been established yet (first entry, or between
    // the first frame and the connect effect), so the empty-directory
    // message never flashes for a frame. A failed connect must not
    // spin forever: the error line replaces the spinner.
    val loading = state.loading ||
        (address.isNotEmpty() && state.sftp == null && state.error == null)

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        if (address.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Connect to a remote device first (Connect tab).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        if (permProbing) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Checking permissions...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        if (!canRead) {
            Spacer(Modifier.height(16.dp))
            Text(
                "This server only accepts file uploads — browsing is not allowed (Send tab).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Spacer(Modifier.height(16.dp))

        // Current directory with a refresh action; the listing loads
        // by itself on connect and on navigation.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.path,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            IconButton(
                onClick = {
                    withSftp { sftp ->
                        state.files = sftp.listDir(state.path)
                    }
                },
                enabled = !loading,
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }

        state.error?.let {
            Spacer(Modifier.height(4.dp))
            Text("Error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        state.status?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }

        if (state.files.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.filter,
                onValueChange = { state.filter = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Filter files") },
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
        }

        val displayedFiles = if (state.filter.isEmpty()) {
            state.files
        } else {
            state.files.filter { it.isDir || it.name.contains(state.filter, ignoreCase = true) }
        }

        if (displayedFiles.isNotEmpty()) {
            LazyColumn(modifier = Modifier.weight(1f), state = state.listState) {
                if (state.path != "/" && state.filter.isEmpty()) {
                    item(key = "..") {
                        FileRow(
                            name = "..",
                            isDir = true,
                            size = 0,
                            icon = Icons.Default.ArrowUpward,
                            onClick = {
                                val parent = state.path.substringBeforeLast("/")
                                state.path = if (parent.isEmpty()) "/" else parent
                                state.downloadedName = null
                                withSftp { sftp ->
                                    state.files = sftp.listDir(state.path)
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
                items(displayedFiles, key = { "${state.path}/${it.name}/${it.isDir}" }) { file ->
                    val dlKey = "${state.path}/${file.name}"
                    val dl = state.downloads[dlKey]
                    Column {
                        ListItem(
                            headlineContent = {
                                Text(
                                    file.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            supportingContent = {
                                Text(
                                    formatBytes(file.size),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            leadingContent = {
                                Icon(
                                    if (file.isDir) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                    contentDescription = if (file.isDir) "Directory" else "File",
                                    tint = if (file.isDir) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            trailingContent = {
                                if (file.isDir) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else if (dl?.active == true) {
                                    TextButton(onClick = { state.downloadJobs[dlKey]?.cancel() }) {
                                        Text("Cancel")
                                    }
                                } else if (state.downloadedName == file.name) {
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
                                                state.error = "Cannot open: ${e.message}"
                                            }
                                        } else {
                                            state.error = "File not found in MediaStore"
                                        }
                                    }) {
                                        Text("Open")
                                    }
                                } else {
                                    TextButton(onClick = {
                                        state.downloads[dlKey] = BrowseState.Download(active = true, total = file.size)
                                        state.error = null
                                        state.status = null
                                        state.downloadedName = null
                                        state.downloadJobs[dlKey] = scope.launch {
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    val sftp = state.sftp ?: run {
                                                        val client = TailcatClient(address, "")
                                                        client.ping()
                                                        state.client = client
                                                        val s = client.dialSFTP()
                                                        state.sftp = s
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
                                                    sftp.downloadFile(
                                                        dlKey, localFile.absolutePath,
                                                        onProgress = { sent, total ->
                                                            state.downloads[dlKey] = BrowseState.Download(active = true, sent = sent, total = total)
                                                        },
                                                        isCancelled = { !isActive },
                                                    )
                                                }
                                                state.downloadedName = file.name
                                                state.status = "Downloaded ${file.name} to Downloads"
                                            } catch (e: CancellationException) {
                                                // User cancelled: the Go side removed the partial.
                                            } catch (e: Exception) {
                                                if (!isActive) {
                                                    // Cancel surfaced as an error from the Go side.
                                                } else {
                                                    state.error = e.message
                                                }
                                            } finally {
                                                state.downloadJobs.remove(dlKey)
                                                state.downloads[dlKey] =
                                                    (state.downloads[dlKey] ?: BrowseState.Download(false)).copy(active = false)
                                            }
                                        }
                                    }) {
                                        Text("Download")
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (file.isDir) Modifier.clickable {
                                    state.path = if (state.path == "/") "/${file.name}" else "${state.path}/${file.name}"
                                    state.downloadedName = null
                                    state.filter = ""
                                    withSftp { sftp ->
                                        state.files = sftp.listDir(state.path)
                                    }
                                } else Modifier),
                        )
                        // Per-row download progress, full width under the
                        // row rather than squeezed into it. The state is
                        // hoisted, so it survives scrolling the row out of
                        // the viewport and tab switches alike.
                        if (!file.isDir && dl?.active == true) {
                            if (dl.total > 0) {
                                LinearProgressIndicator(
                                    progress = { dl.sent.toFloat() / dl.total },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    "${formatBytes(dl.sent)} / ${formatBytes(dl.total)} (${dl.sent * 100 / dl.total}%)",
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
        } else if (state.files.isEmpty() && !loading && state.error == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "This directory is empty.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One native-looking list row: icon, name, optional size, click action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileRow(
    name: String,
    isDir: Boolean,
    size: Long,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        supportingContent = if (size > 0) {
            {
                Text(
                    formatBytes(size),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else null,
        leadingContent = {
            Icon(
                icon,
                contentDescription = if (isDir) "Directory" else "File",
                tint = if (isDir) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    )
}


/** Shared with the Receive tab's received-file Open action. */
internal fun getMimeType(name: String): String {
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
