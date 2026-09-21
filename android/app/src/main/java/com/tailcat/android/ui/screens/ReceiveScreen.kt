package com.tailcat.android.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tailcat.android.SavedAddresses
import com.tailcat.android.TailcatClient
import com.tailcat.android.TailcatConn
import com.tailcat.android.TailcatConnectionListener
import com.tailcat.android.TailcatKey
import com.tailcat.android.TailcatServer
import com.tailcat.android.addressKey
import com.tailcat.android.keyAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ReceivedFile(
    val name: String,
    val size: Long,
)

data class ReceiveState(
    val address: String = "",
    val listening: Boolean = false,
    val starting: Boolean = false,
    val error: String? = null,
    val receivedFiles: List<ReceivedFile> = emptyList(),
    val receiving: Boolean = false,
    val sharedDir: String = "",
    // How the shared folder is served: "rw" (read & write), "ro"
    // (read-only), or "wo" (write-only drop box). See TailcatServer.startSFTP.
    val serveMode: String = "wo",
    // When true, the user explicitly stopped listening: tabbing back
    // into Receive does not auto-start again.
    val stoppedByUser: Boolean = false,
    // When true, only devices with an address in the saved list may
    // connect; see bridge AllowedClients. On by default: an address
    // shared casually (QR, clipboard) should not admit the world.
    val allowedOnly: Boolean = true,
)

/** One connected client, from TailcatServer.peersJSON. */
data class PeerEntry(
    val key: String,
    val curAddr: String,
    val relay: String,
    val active: Boolean,
)

// The shared-storage root. Never the default for listening — serving
// it would expose everything on the phone; it only pre-fills the
// directory chooser and appears as the "All files" suggestion. The
// SAF picker refuses to grant the root "for privacy reasons", but the
// path-based serving route under All Files Access can serve it.
private const val defaultShareRoot = "/storage/emulated/0"

@Composable
fun ReceiveScreen(
    state: ReceiveState,
    // Takes a transform applied to the latest state rather than a
    // precomputed copy: the server's connection listeners outlive this
    // composition, and closing over an old `state` snapshot would clobber
    // newer updates (e.g. the received-files list).
    onStateChange: ((ReceiveState) -> ReceiveState) -> Unit,
    server: TailcatServer?,
    onServerChange: (TailcatServer?) -> Unit,
    scannedAddress: String,
    permCache: PermissionCache,
    savedAddresses: SavedAddresses,
    // App-level scope: server callbacks (file saving) must keep running
    // while this screen is out of composition, which would cancel a
    // screen-local rememberCoroutineScope.
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    var restarting by remember { mutableStateOf(false) }
    var needsAllFilesAccess by remember { mutableStateOf(false) }
    var showDirChooser by remember { mutableStateOf(false) }
    var pathInput by remember { mutableStateOf("") }
    var showRotateDialog by remember { mutableStateOf(false) }
    var rotateWithPSK by remember { mutableStateOf(true) }
    var sendingAddress by remember { mutableStateOf(false) }
    var showAddressActions by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<ReceivedFile?>(null) }

    // The identity's default address, shown on the card before the
    // first start (and refreshed after the rotate dialog closes — a
    // rotation replaces the key behind the address).
    var defaultAddress by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showRotateDialog) {
        if (showRotateDialog) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            defaultAddress = keyAddress(TailcatKey.loadOrCreate())
        }
    }

    // Derived from persisted state so the chosen directory survives
    // tab switches (the screen leaves composition; `state` does not).
    // The base state — nothing explicitly picked — serves the public
    // Downloads folder write-only (a drop box), so tailcat cp works
    // out of the box and the settings reflect what is actually served.
    @Suppress("DEPRECATION")
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        ?.absolutePath ?: "/storage/emulated/0/Download"
    val sharedDirPath = state.sharedDir.ifEmpty { downloadsDir }
    val sharedDirLabel = when {
        state.sharedDir.isEmpty() -> "Downloads (default)"
        sharedDirPath == defaultShareRoot -> "All files"
        else -> sharedDirPath.substringAfterLast('/').ifEmpty { sharedDirPath }
    }

    // serveDir makes path the served SFTP directory, restarting the
    // server if it is already listening. The SAF picker refuses to
    // grant some sensitive top-level folders (like Download) "for
    // privacy reasons", so paths can also be entered manually; both
    // routes require All Files Access, without which scoped storage
    // makes the folder list as empty over SFTP.
    // The node keys of every saved address, for the "only saved addresses"
    // allowlist. Unparsable addresses are skipped.
    fun savedKeys(): List<String> =
        savedAddresses.addresses.mapNotNull { addressKey(it.address) }

    fun startServer(dir: String?, mode: String, allowedOnly: Boolean) {
        onStateChange { it.copy(starting = true, error = null) }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val keyJSON = TailcatKey.loadOrCreate()
                    val srv = TailcatServer("", object : TailcatConnectionListener {
                        override fun onConnection(conn: TailcatConn) {
                            scope.launch {
                                val file = saveConnectionToFile(context, conn) { receiving ->
                                    onStateChange { it.copy(receiving = receiving) }
                                }
                                onStateChange {
                                    it.copy(receivedFiles = it.receivedFiles + file, receiving = false)
                                }
                            }
                        }
                    })
                    val allowedKeys = if (allowedOnly) savedKeys() else emptyList()
                    val addr = if (dir != null) {
                        srv.startSFTP(dir, mode, keyJSON, JSONArray(allowedKeys).toString())
                    } else {
                        srv.start(keyJSON, JSONArray(allowedKeys).toString())
                    }
                    onServerChange(srv)
                    onStateChange {
                        it.copy(address = addr, listening = true, starting = false, sharedDir = dir ?: "", stoppedByUser = false)
                    }
                }
            } catch (e: Exception) {
                onStateChange { it.copy(starting = false, error = e.message) }
            }
        }
    }

    // Tabbing into Receive starts listening by default (the server
    // and its state live at the app level, so both survive tab
    // switches). An explicit Stop sets stoppedByUser and wins: re-
    // entering the tab does not silently restart.
    LaunchedEffect(Unit) {
        if (!state.listening && !state.starting && !state.stoppedByUser) {
            startServer(sharedDirPath, state.serveMode, state.allowedOnly)
        }
    }

    // Restarts a listening server with the given directory (null =
    // raw receive, no SFTP) and settings, keeping the same address.
    // Mode and allowlist changes go through here, since the tunnel has
    // no API to tighten a running engine's restrictions. Adding a
    // device to the allowlist does NOT go through here — see the
    // allow effect below.
    fun restartIfListening(dir: String?, mode: String, allowedOnly: Boolean) {
        if (!state.listening) return
        restarting = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { server?.stop() }
                onServerChange(null)
                startServer(dir, mode, allowedOnly)
            } finally {
                restarting = false
            }
        }
    }

    fun setServeMode(mode: String) {
        onStateChange { it.copy(serveMode = mode) }
        restartIfListening(sharedDirPath, mode, state.allowedOnly)
    }

    fun setAllowedOnly(on: Boolean) {
        onStateChange { it.copy(allowedOnly = on) }
        restartIfListening(sharedDirPath, state.serveMode, on)
    }

    // Stops exposing the shared directory: clears the label and, while
    // listening, restarts the server in raw receive mode (same address).
    fun forgetSharedDir() {
        onStateChange { it.copy(sharedDir = "") }
        // Falls back to the Downloads drop box, not raw mode.
        restartIfListening(downloadsDir, state.serveMode, state.allowedOnly)
    }

    // Gate + state update for making path the served directory.
    // Returns false (after showing the All Files Access dialog) when
    // the grant is missing, or with an error set when the path is not
    // a directory.
    fun adoptShareDir(path: String): Boolean {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            needsAllFilesAccess = true
            return false
        }
        if (!File(path).isDirectory) {
            onStateChange { it.copy(error = "Not a directory: $path") }
            return false
        }
        onStateChange { it.copy(sharedDir = path) }
        return true
    }

    // Opens the directory chooser. The path starts empty unless
    // changing an existing choice: what gets served must be picked
    // deliberately, never pre-filled with the storage root.
    fun openDirChooser() {
        pathInput = sharedDirPath ?: ""
        showDirChooser = true
    }

    // The system picker (launched from the chooser dialog's Browse
    // button) only locates a folder; serving always goes by path under
    // All Files Access. Its result lands in the chooser's text field
    // for the user to confirm — some folders the picker refuses to
    // even return, which the toast below covers.
    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = treeUriToPath(uri)
        if (path != null) {
            pathInput = path
        } else {
            Toast.makeText(context, "That pick can't be served — type its path instead.", Toast.LENGTH_LONG).show()
        }
    }

    // While listening with the "only saved addresses" allowlist on, a
    // device saved on the Connect tab is admitted on the running
    // engine immediately — no restart, no address change, no dropped
    // transfers. Keys already applied to this server instance are
    // tracked locally; the effect restarts when the server is replaced.
    val appliedKeys = remember(server) { mutableSetOf<String>() }
    LaunchedEffect(server, state.allowedOnly, savedAddresses.addresses.size) {
        if (server == null || !state.allowedOnly) return@LaunchedEffect
        for (key in savedKeys()) {
            if (key in appliedKeys) continue
            appliedKeys.add(key)
            withContext(Dispatchers.IO) { server.allowClient(key) }
        }
    }

    // SFTP uploads land straight in the shared folder, invisible to
    // the raw receive path; watch the folder so they appear in the
    // received-files list like raw receives do. Events arrive on a
    // watcher thread; snapshot state is safe to write from there.
    DisposableEffect(state.listening, sharedDirPath) {
        val dir = if (state.listening) sharedDirPath else null
        val observer = dir?.let { d ->
            sharedDirObserver(
                d,
                onReceiving = { receiving ->
                    onStateChange { it.copy(receiving = receiving) }
                },
            ) { name, size ->
                onStateChange { s ->
                    // Skip duplicate events for the same file (same name
                    // and size as the last recorded entry).
                    val last = s.receivedFiles.lastOrNull()
                    if (last?.name == name && last.size == size) s
                    else s.copy(receivedFiles = s.receivedFiles + ReceivedFile(name, size))
                }
            }
        }
        observer?.startWatching()
        onDispose { observer?.stopWatching() }
    }

    // The whole screen scrolls: the listening card, peers list, and
    // settings can outgrow small screens, and the inner lists are
    // plain columns (not LazyColumns) so nesting is safe here.
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // The address card mirrors the Connect tab's connection card:
        // a fixed address slot, plain status rows, and compact action
        // rows at the bottom. The start/stop toggle lives in those
        // rows, not in a big button above the card.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Your address:", style = MaterialTheme.typography.labelMedium)
                // Same slot style as the Connect card: a fixed
                // two-line bodyMedium, copyable via selection. The
                // identity's default address shows even before the
                // first start.
                val shownAddress = state.address.ifEmpty { defaultAddress }
                // Tap the address for the full address and its
                // actions (Share, Rotate, device pairing) in a popup.
                SelectionContainer {
                    Text(
                        shownAddress ?: "Loading address...",
                        style = MaterialTheme.typography.bodyMedium,
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (shownAddress == null) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAddressActions = true },
                    )
                }
                Spacer(Modifier.height(8.dp))

                // Relay health: the shared address is unreachable
                // while the engine has no DERP relay connection, even
                // though the server is listening. The bridge's
                // watchdog rebuilds the engine automatically; while
                // that is in progress, show the live retry count and
                // a link to rebuild immediately.
                var relayOnline by remember { mutableStateOf(true) }
                var relayMisses by remember { mutableStateOf(0) }
                var peers by remember { mutableStateOf<List<PeerEntry>>(emptyList()) }
                LaunchedEffect(server) {
                    while (true) {
                        relayOnline = server?.relayOnline() ?: true
                        relayMisses = server?.relayMisses() ?: 0
                        peers = server?.peersJSON()?.let(::parsePeers) ?: emptyList()
                        delay(3000)
                    }
                }
                if (server == null) {
                    Text(
                        "Relay: –",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (relayOnline) {
                    Text(
                        "Relay: connected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    // Plain status row like the others: the bridge's
                    // watchdog rebuilds the engine on its own, so no
                    // manual repair control is needed.
                    Text(
                        "Relay: offline (retry ${relayMisses + 1})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // Named peers (a saved address whose identity key
                // matches) get a row each; unnamed one-off clients —
                // every CLI command is a fresh ephemeral key —
                // collapse into a single count line instead of
                // stacking up as raw nodekeys.
                val activePeers = peers.filter { it.active }
                val keyAliases = savedAddresses.addresses.mapNotNull { s ->
                    addressKey(s.address)?.let { it to s.alias }
                }.toMap()
                val named = activePeers.mapNotNull { p ->
                    keyAliases[p.key]?.let { p to it }
                }
                val unnamed = activePeers.size - named.size
                if (named.isNotEmpty() || unnamed > 0) {
                    Text(
                        "Connections: ${activePeers.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for ((p, alias) in named) {
                        Text(
                            "$alias — ${if (p.curAddr.isNotEmpty()) "direct" else "relayed via ${p.relay}"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (p.curAddr.isNotEmpty()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    if (unnamed > 0) {
                        Text(
                            "+$unnamed unnamed connection${if (unnamed == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Start/stop is the only inline action; tapping the
                // address above opens a popup with Share, Rotate, and
                // device pairing, so nothing ever expands the card.
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            if (state.listening) {
                                server?.stop()
                                onServerChange(null)
                                onStateChange { it.copy(listening = false, stoppedByUser = true) }
                            } else {
                                // Nothing explicitly picked serves the
                                // base state: Downloads as a drop box.
                                startServer(sharedDirPath, state.serveMode, state.allowedOnly)
                            }
                        },
                        enabled = !state.starting,
                    ) {
                        if (state.starting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (state.listening) "Stop listening" else "Start listening")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Sharing settings", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        // Directory and serve settings: identical composables in both
        // listening states, so starting or stopping the server never
        // redraws this section.
        if (sharedDirLabel != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Shared directory: $sharedDirLabel",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = ::forgetSharedDir,
                    enabled = !restarting,
                ) {
                    Text("Reset")
                }
            }
        }
        OutlinedButton(
            onClick = ::openDirChooser,
            modifier = Modifier.fillMaxWidth(),
            enabled = !restarting,
        ) {
            if (restarting) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (sharedDirLabel != null) "Change directory" else "Share directory")
        }
        Spacer(Modifier.height(8.dp))
        ServeSettings(
            mode = state.serveMode,
            allowedOnly = state.allowedOnly,
            enabled = !restarting && !state.starting,
            onModeChange = ::setServeMode,
            onAllowedOnlyChange = ::setAllowedOnly,
        )


        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text("Error: $it", color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(16.dp))

        // Received files: the same row shape as the Browse tab, with
        // Open and Remove actions.
        Text("Received files:", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        for (file in state.receivedFiles) {
            ListItem(
                headlineContent = {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                        Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = "File",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { actionTarget = file },
            )
            HorizontalDivider()
        }
        // Receiving indicator while a file is incoming; it sits below
        // the list so nothing above ever shifts.
        if (state.receiving) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Receiving file...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    // Tapping a received file opens this: its actions, keeping the
    // list rows free of buttons.
    if (actionTarget != null) {
        val file = actionTarget!!
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(file.name, style = MaterialTheme.typography.titleMedium) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uri = findDownloadsUri(context, file.name)
                        if (uri != null) {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, getMimeType(file.name))
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Cannot open: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "File not found in MediaStore", Toast.LENGTH_SHORT).show()
                        }
                        actionTarget = null
                    },
                ) {
                    Text("Open")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (removeReceivedFile(context, sharedDirPath, file.name)) {
                            onStateChange { s -> s.copy(receivedFiles = s.receivedFiles - file) }
                        } else {
                            Toast.makeText(context, "Could not remove ${file.name}", Toast.LENGTH_SHORT).show()
                        }
                        actionTarget = null
                    },
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }

    // All Files Access gate: shown when a folder was picked but the
    // app lacks the grant. Scoped storage would otherwise make the
    // folder list as empty over SFTP.
    if (needsAllFilesAccess) {
        AlertDialog(
            onDismissRequest = { needsAllFilesAccess = false },
            title = { Text("All files access needed") },
            text = {
                Text(
                    "Serving a shared folder requires All Files Access, " +
                        "which Android only grants from Settings. After granting it, " +
                        "pick the folder again to serve it.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    needsAllFilesAccess = false
                    context.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                }) {
                    Text("Open settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { needsAllFilesAccess = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Directory chooser: the single place to pick what to serve. The
    // chips are real, OS-provided paths (the shared-storage root, the
    // public folders that exist on this device, any SD volumes) — not
    // guesses — and serving always goes by path under All Files
    // Access. Browse opens the system picker for everything else;
    // its result lands in the field, since some folders the picker
    // can't grant but the path route can serve.
    if (showDirChooser) {
        val suggestions = remember { suggestedShareDirs(context) }
        AlertDialog(
            onDismissRequest = { showDirChooser = false },
            title = { Text(if (sharedDirLabel != null) "Change directory" else "Share directory") },
            text = {
                Column {
                    suggestions.chunked(3).forEach { rowDirs ->
                        Row {
                            rowDirs.forEach { (label, path) ->
                                FilterChip(
                                    selected = pathInput == path,
                                    onClick = { pathInput = path },
                                    label = { Text(label) },
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pathInput,
                        onValueChange = { pathInput = it },
                        label = { Text("Path") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { dirPicker.launch(null) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Browse…")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val path = pathInput.trim()
                    if (path.isEmpty()) {
                        Toast.makeText(context, "Pick or type a directory to serve.", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    if (adoptShareDir(path)) {
                        showDirChooser = false
                        restartIfListening(path, state.serveMode, state.allowedOnly)
                    } else if (!File(path).isDirectory) {
                        Toast.makeText(context, "Not a directory: $path", Toast.LENGTH_SHORT).show()
                    }
                    // Otherwise the All Files Access dialog just opened.
                }) {
                    Text("Serve")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDirChooser = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Tapping the address opens this: the full address (copyable)
    // plus its actions, keeping the card itself free of buttons that
    // appear and expand it.
    if (showAddressActions) {
        val popupAddress = state.address.ifEmpty { defaultAddress }
        val remoteAlias = savedAddresses.addresses.firstOrNull {
            it.address == scannedAddress
        }?.alias
        val remoteInfo = if (scannedAddress.isNotEmpty() && scannedAddress != state.address) {
            permCache.get(scannedAddress)
        } else null
        val canPair = remoteInfo?.canWrite == true && popupAddress != null
        AlertDialog(
            onDismissRequest = { showAddressActions = false },
            title = { Text("Your address") },
            text = {
                Column {
                    SelectionContainer {
                        Text(
                            popupAddress ?: "No address yet.",
                            style = MaterialTheme.typography.bodySmall,
                            softWrap = true,
                        )
                    }
                    // Pairing: hand this address to the device the
                    // Connect tab is connected to, so it can also
                    // reach the phone. The toast reports the outcome.
                    if (canPair) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                if (sendingAddress) return@TextButton
                                sendingAddress = true
                                scope.launch {
                                    var fileName = "tailcat-address.txt"
                                    try {
                                        withContext(Dispatchers.IO) {
                                            val tmpFile = java.io.File(context.cacheDir, "tailcat-address.txt")
                                            tmpFile.writeText(popupAddress + "\n")

                                            val client = TailcatClient(scannedAddress, "")
                                            client.pingWithTimeout(15)
                                            val sftp = client.dialSFTP()
                                            val remotePath = sftp.uploadFileGetPath(tmpFile.absolutePath, "/tailcat-address.txt")
                                            sftp.close()
                                            client.close()
                                            tmpFile.delete()
                                            fileName = remotePath.substringAfterLast('/')
                                        }
                                        Toast.makeText(context, "Address sent as $fileName", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                    sendingAddress = false
                                }
                            },
                            enabled = !sendingAddress,
                        ) {
                            if (sendingAddress) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                if (sendingAddress) "Sending..."
                                else if (remoteAlias != null) "Send to $remoteAlias"
                                else "Send address"
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, popupAddress)
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "Share address"))
                    },
                    enabled = popupAddress != null,
                ) {
                    Text("Share")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddressActions = false
                    showRotateDialog = true
                }) {
                    Text("Rotate")
                }
            },
        )
    }

    // Rotate confirmation: replacing the identity changes the address,
    // so an accidental tap must not go through. The switch controls
    // whether the new key embeds a pre-shared key.
    if (showRotateDialog) {
        AlertDialog(
            onDismissRequest = { showRotateDialog = false },
            title = { Text("Rotate address?") },
            text = {
                Column {
                    Text(
                        "This replaces your identity with a new address. The old one stops " +
                            "working for everyone once the server restarts with it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = rotateWithPSK,
                            onCheckedChange = { rotateWithPSK = it },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Include pre-shared key", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Recommended: the address itself stays a secret credential. " +
                                    "Without it, the address is public information — pair it with " +
                                    "'Only saved addresses may connect'.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showRotateDialog = false
                    val withPSK = rotateWithPSK
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            TailcatKey.rotate(withPSK)
                        }
                        Toast.makeText(
                            context,
                            "Address rotated. Restart listening for the new address.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }) {
                    Text("Rotate")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRotateDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * Read all data from a TailcatConn and save it to the Downloads directory.
 * Returns a ReceivedFile with the generated name and total size.
 */
private suspend fun saveConnectionToFile(
    context: Context,
    conn: TailcatConn,
    onReceiving: (Boolean) -> Unit,
): ReceivedFile = withContext(Dispatchers.IO) {
    onReceiving(true)
    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val fileName = "tailcat-$timestamp.bin"

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { out ->
                var total = 0L
                while (true) {
                    val data = conn.read() ?: break
                    out.write(data)
                    total += data.size
                }
                conn.close()
                return@withContext ReceivedFile(fileName, total)
            }
        }
    }

    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val file = File(downloads, fileName)
    FileOutputStream(file).use { out ->
        var total = 0L
        while (true) {
            val data = conn.read() ?: break
            out.write(data)
            total += data.size
        }
        conn.close()
        return@withContext ReceivedFile(fileName, total)
    }
    onReceiving(false)
    ReceivedFile(fileName, 0)
}

/**
 * Converts a Storage Access Framework tree URI to a filesystem path.
 */
private fun treeUriToPath(uri: Uri): String? {
    val tree = uri.path ?: return null
    val segments = tree.split("/tree/", limit = 2)
    if (segments.size < 2) return null
    val docId = Uri.decode(segments[1])
    val colonIdx = docId.indexOf(':')
    if (colonIdx < 0) return null
    val volume = docId.substring(0, colonIdx)
    val subPath = docId.substring(colonIdx + 1)
    val basePath = when (volume) {
        "primary" -> "/storage/emulated/0"
        else -> "/storage/$volume"
    }
    return if (subPath.isEmpty()) basePath else "$basePath/$subPath"
}

/**
 * Serve mode chips and the saved-devices allowlist switch, shown both
 * before starting and while listening. Mode and allowlist changes on a
 * listening server restart it in place (same address); adding a newly
 * saved device is live, see ReceiveScreen's allow effect.
 */
@Composable
private fun ServeSettings(
    mode: String,
    allowedOnly: Boolean,
    enabled: Boolean,
    onModeChange: (String) -> Unit,
    onAllowedOnlyChange: (Boolean) -> Unit,
) {
    Text("Serve mode", style = MaterialTheme.typography.labelMedium)
    Row {
        listOf(
            "rw" to "Read & write",
            "ro" to "Read-only",
            "wo" to "Drop box",
        ).forEach { (m, label) ->
            FilterChip(
                selected = mode == m,
                onClick = { if (m != mode) onModeChange(m) },
                enabled = enabled,
                label = { Text(label) },
            )
            Spacer(Modifier.width(4.dp))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = allowedOnly,
            onCheckedChange = onAllowedOnlyChange,
            enabled = enabled,
        )
        Spacer(Modifier.width(8.dp))
        Text("Only saved addresses may connect", style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Watches a served directory for files written or moved into it, so
 * SFTP uploads appear in received list. A file being created or
 * modified (but not yet closed) reports receiving = true, so the UI
 * can show its spinner during the transfer. The String constructor of
 * FileObserver is deprecated in favor of a File one that needs API 29;
 * the app's minSdk is 26, so the String form is the only option.
 */
@Suppress("DEPRECATION")
private fun sharedDirObserver(
    dir: String,
    onReceiving: (Boolean) -> Unit,
    onFile: (name: String, size: Long) -> Unit,
): FileObserver =
    object : FileObserver(
        dir,
        FileObserver.CREATE or FileObserver.MODIFY or
            FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO,
    ) {
        override fun onEvent(event: Int, path: String?) {
            if (path == null) return
            val f = File(dir, path)
            if (event and (FileObserver.CREATE or FileObserver.MODIFY) != 0) {
                if (!f.isFile) return
                onReceiving(true)
                return
            }
            if (event and (FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO) != 0) {
                onReceiving(false)
                if (!f.isFile) return
                onFile(path, f.length())
            }
        }
    }

/** Parses TailcatServer.peersJSON into PeerEntry list; empty on malformed input. */
private fun parsePeers(json: String): List<PeerEntry> {
    val out = mutableListOf<PeerEntry>()
    try {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                PeerEntry(
                    key = o.getString("key"),
                    curAddr = o.optString("curAddr"),
                    relay = o.optString("relay"),
                    active = o.optBoolean("active"),
                )
            )
        }
    } catch (_: Exception) {
        // Malformed snapshot — show nothing this tick.
    }
    return out
}

/**
 * Real, OS-provided candidate directories for the chooser: the
 * public folders that exist on this device and any secondary volumes
 * (SD cards). Nothing is guessed, and the storage root is
 * deliberately absent — serving it stays possible by typing its
 * path, but it is never one tap away. The deprecated Environment
 * accessors are the right tool here: only the paths are used — the
 * permission comes from All Files Access, not from SAF grants.
 */
@Suppress("DEPRECATION")
private fun suggestedShareDirs(context: Context): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    for ((label, envDir) in listOf(
        "Downloads" to Environment.DIRECTORY_DOWNLOADS,
        "DCIM" to Environment.DIRECTORY_DCIM,
        "Documents" to Environment.DIRECTORY_DOCUMENTS,
        "Pictures" to Environment.DIRECTORY_PICTURES,
        "Music" to Environment.DIRECTORY_MUSIC,
        "Movies" to Environment.DIRECTORY_MOVIES,
    )) {
        val dir = Environment.getExternalStoragePublicDirectory(envDir)
        if (dir.isDirectory) out.add(label to dir.absolutePath)
    }
    // Secondary volumes: /storage/XXXX-XXXX/Android/data/... — the
    // volume root is the prefix before /Android/.
    for (files in context.getExternalFilesDirs(null).drop(1)) {
        val volRoot = files?.absolutePath?.substringBefore("/Android/") ?: continue
        if (File(volRoot).isDirectory) out.add(volRoot.substringAfterLast('/') to volRoot)
    }
    return out
}

/** MediaStore lookup for a received file by display name under Download. */
private fun findDownloadsUri(context: Context, name: String): Uri? {
    val filesUri = MediaStore.Files.getContentUri("external")
    val projection = arrayOf(MediaStore.MediaColumns._ID)
    val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
    val selectionArgs = arrayOf(name, "%Download%")
    val sortOrder = "${MediaStore.MediaColumns._ID} DESC"
    context.contentResolver.query(filesUri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
        if (cursor.moveToFirst()) {
            return Uri.withAppendedPath(filesUri, cursor.getLong(0).toString())
        }
    }
    return null
}

/**
 * Deletes a received file: from the served directory by path first,
 * then via MediaStore (for files the app inserted there).
 */
private fun removeReceivedFile(context: Context, dir: String, name: String): Boolean {
    if (File(dir, name).delete()) return true
    val uri = findDownloadsUri(context, name) ?: return false
    return context.contentResolver.delete(uri, null, null) > 0
}
