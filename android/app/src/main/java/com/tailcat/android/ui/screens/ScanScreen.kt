package com.tailcat.android.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.tailcat.android.SavedAddresses
import com.tailcat.android.addressKey
import com.tailcat.android.ocr.TokenScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * One watchdog disco ping: live connection state, refreshed every 3s.
 * seq makes every ping a distinct value, so state keyed on it (the
 * latency dots timer) restarts even when the measurements are equal.
 */
data class LivePing(val seq: Long, val latencyMs: Long, val direct: Boolean, val via: String)

/**
 * Fully static layout: every section always exists in the same place
 * and at the same height. State changes only swap text and the
 * enabled/greyed state of controls; nothing ever appears, disappears,
 * or moves. The camera preview is the one exception to the "always
 * visible" rule and lives in a full-screen overlay so opening it
 * never reflows the tab.
 */
@Composable
fun ScanScreen(
    scannedAddress: String,
    onAddressScanned: (String) -> Unit,
    onUseAddress: () -> Unit,
    // Disconnect stops the connection but keeps the address on the
    // card; Reconnect brings it back without re-scanning.
    onDisconnect: () -> Unit,
    // Clear resets the card completely: only offered once the
    // connection is already disconnected.
    onClearAddress: () -> Unit,
    // True when the watchdog lost the connection unexpectedly: the
    // card shows "connection lost".
    connectionLost: Boolean,
    // True when the user disconnected deliberately: the card shows
    // "disconnected" instead of clearing.
    userDisconnected: Boolean,
    onReconnect: () -> Unit,
    // The latest watchdog ping: latency, connection type, measured
    // continuously while the connection is alive.
    livePing: LivePing?,
    // Bumped when the watchdog's periodic recheck noticed a permission
    // change: re-read the fresh cache into the card.
    probeRefreshTick: Int,
    permCache: PermissionCache,
    savedAddresses: SavedAddresses,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    // Camera lives in a full-screen overlay while active; the tab
    // below keeps its layout at all times.
    var cameraActive by remember { mutableStateOf(false) }

    var manualAddress by remember { mutableStateOf("") }
    // The manual paste form is collapsed by default: scanning or a
    // saved device is the common path, and keeping the form folded
    // leaves the rest of the tab uncluttered.
    var showManualInput by remember { mutableStateOf(false) }

    // Save dialog state
    var showSaveDialog by remember { mutableStateOf(false) }
    var aliasInput by remember { mutableStateOf("") }

    // Rename dialog state: the saved address being renamed, and the
    // alias being typed. Tapping the left region of a saved-address
    // card opens the dialog.
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var renameInput by remember { mutableStateOf("") }

    // Remove confirmation state: the saved address pending removal.
    var removeTarget by remember { mutableStateOf<String?>(null) }

    // Tapping the address opens it in full, copyable.
    var showAddressDialog by remember { mutableStateOf(false) }

    // Probe results: connection type and permissions.
    // Initialize from cache so results don't flash on tab switch.
    val cachedInfo = if (scannedAddress.isNotEmpty() && scannedAddress.startsWith("tc")) permCache.get(scannedAddress) else null
    var probing by remember { mutableStateOf(cachedInfo == null && scannedAddress.startsWith("tc")) }
    var probeResult by remember {
        mutableStateOf(cachedInfo?.let {
            ProbeResult(it.direct, it.via, it.latencyMs, it.canRead, it.canWrite)
        })
    }
    var probeError by remember { mutableStateOf<String?>(null) }
    // Incremented by the Retry control to re-run the probe.
    var probeAttempt by remember { mutableStateOf(0) }
    // True between a Reconnect tap and the end of the probe it
    // triggers: the swap slot stays greyed for exactly that window.
    var reconnecting by remember { mutableStateOf(false) }

    // Auto-probe when a new address is scanned — but skip if already cached
    LaunchedEffect(scannedAddress, probeAttempt) {
        if (scannedAddress.isEmpty() || !scannedAddress.startsWith("tc")) {
            probeResult = null
            probeError = null
            probing = false
            reconnecting = false
            return@LaunchedEffect
        }
        val cached = permCache.get(scannedAddress)
        if (cached != null) {
            probeResult = cached.toProbeResult()
            probing = false
            probeError = null
            reconnecting = false
            return@LaunchedEffect
        }
        probing = true
        probeError = null
        probeResult = null
        try {
            val info = probeAddress(scannedAddress, permCache)
            probeResult = info.toProbeResult()
        } catch (e: CancellationException) {
            // Cancelled (a new attempt superseded this one): not an
            // error, and never displayed as one.
            throw e
        } catch (e: Exception) {
            probeError = e.message
        }
        probing = false
        reconnecting = false
    }

    // The watchdog's periodic recheck updated the cache (the remote
    // serve mode changed); re-read it into the card.
    LaunchedEffect(probeRefreshTick) {
        if (probeRefreshTick > 0) probeAttempt++
    }

    // The watchdog keeps pinging through a lost connection and flips
    // connectionLost back off when the server returns; that recovery
    // re-probes, so the fresh serve mode's permissions show instead of
    // the stale pre-loss values.
    var wasLost by remember { mutableStateOf(false) }
    LaunchedEffect(connectionLost) {
        if (connectionLost) {
            wasLost = true
        } else if (wasLost) {
            wasLost = false
            probeResult = null
            probeError = null
            probeAttempt++
        }
    }

    val connected = scannedAddress.isNotEmpty()

    // Recognize the connected device among the saved ones: a saved
    // entry may hold the short-form address while a scan delivers the
    // full one, so match on the node key rather than the address text.
    val scannedKey = if (connected) addressKey(scannedAddress) else null
    val matchedAlias = if (scannedKey == null) null
        else savedAddresses.addresses.firstOrNull { addressKey(it.address) == scannedKey }?.alias

    // Fixed sections on top; the saved-address list at the bottom is
    // the only scrollable element, bounded by the remaining height.
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Connection card, always present and always the same height:
        // disconnected shows a hint in the address slot, the three
        // status rows show "–", and every control is greyed. Connecting
        // swaps text and enables controls — nothing moves.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Address:", style = MaterialTheme.typography.labelMedium)
                    if (matchedAlias != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            matchedAlias,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Both states share the same style and the same
                // two-line slot: connecting swaps text only, never
                // size or height.
                // Tap the address (when connected) to see it in full.
                SelectionContainer {
                    Text(
                        if (connected) scannedAddress else "Not connected",
                        style = MaterialTheme.typography.bodyMedium,
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (connected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (connected) Modifier.clickable { showAddressDialog = true }
                                else Modifier
                            ),
                    )
                }
                Spacer(Modifier.height(8.dp))

                val connText = when {
                    !connected -> "–"
                    probing -> "checking…"
                    connectionLost -> "connection lost"
                    userDisconnected -> "disconnected"
                    probeError != null -> "unreachable: $probeError"
                    livePing != null ->
                        if (livePing!!.direct) "direct (P2P)" else "relayed via ${livePing!!.via}"
                    probeResult != null ->
                        if (probeResult!!.direct) "direct (P2P)" else "relayed via ${probeResult!!.via}"
                    else -> "–"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Connection: $connText",
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            connectionLost || probeError != null -> MaterialTheme.colorScheme.error
                            userDisconnected -> MaterialTheme.colorScheme.onSurfaceVariant
                            livePing?.direct == true || probeResult?.direct == true -> MaterialTheme.colorScheme.primary
                            livePing != null || probeResult != null -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // The card's one spinner, covering both a first
                    // connection and a reconnect, right next to the
                    // "checking…" text — the text itself never moves.
                    if (probing) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
                val latencyText = when {
                    connectionLost || userDisconnected || probing || probeError != null -> "–"
                    livePing != null -> "${livePing!!.latencyMs} ms"
                    else -> "${probeResult?.latencyMs ?: "–"} ms"
                }
                // Dots mark the value as continuously re-measured:
                // one dot per second since the last watchdog ping. They
                // run as soon as the watchdog is active — right after
                // the connect probe, before its first ping even lands —
                // and reset whenever a fresh ping arrives.
                val dotsActive = connected && !connectionLost && !userDisconnected && !probing
                var dots by remember { mutableStateOf(0) }
                LaunchedEffect(dotsActive, livePing) {
                    if (!dotsActive) {
                        dots = 0
                        return@LaunchedEffect
                    }
                    dots = 0
                    repeat(3) {
                        delay(1000)
                        dots++
                    }
                }
                Text(
                    "Latency: $latencyText" + if (dotsActive) ".".repeat(dots) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val permParts = mutableListOf<String>()
                if (!connectionLost && !userDisconnected) {
                    probeResult?.let { r ->
                        if (r.canRead) permParts.add("Read")
                        if (r.canWrite) permParts.add("Write")
                    }
                }
                if (permParts.isEmpty()) permParts.add("–")
                Text(
                    "Permissions: ${permParts.joinToString(" + ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (probeResult == null) MaterialTheme.colorScheme.onSurfaceVariant
                    else if (probeResult!!.canRead || probeResult!!.canWrite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )

                // Actions: Save on the left; Clear and the single
                // connect slot clustered on the right. Reconnect and
                // Disconnect are never shown together — one slot
                // swaps between them: Disconnect while the connection
                // is live, Reconnect once it is down (lost, a
                // deliberate disconnect, or a failed probe). Clear
                // sits left of the slot and wipes the address; it
                // only applies with no live connection.
                Spacer(Modifier.height(4.dp))
                val saved = connected && savedAddresses.contains(scannedAddress)
                val connectionUp = connected && !connectionLost && !userDisconnected
                val connectionDown = connected && (!connectionUp || probeError != null)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            aliasInput = ""
                            showSaveDialog = true
                        },
                        enabled = connected && !saved,
                    ) {
                        Text(if (saved) "Saved" else "Save")
                    }
                    Row {
                        TextButton(
                            onClick = {
                                onClearAddress()
                                probeResult = null
                                probeError = null
                            },
                            enabled = connectionDown,
                        ) {
                            Text("Clear")
                        }
                        TextButton(
                            onClick = {
                                if (connectionDown) {
                                    reconnecting = true
                                    onReconnect()
                                    // Clear immediately so the old values
                                    // don't flash back while reconnecting.
                                    probeAttempt++
                                } else {
                                    onDisconnect()
                                    // Nothing of the old connection
                                    // lingers into the next one.
                                }
                                probeResult = null
                                probeError = null
                            },
                            // Greyed with its spinner while a probe or
                            // reconnect is in flight: the slot shows
                            // the swap target, not a live control.
                            enabled = connected && !probing && !reconnecting,
                        ) {
                            Text(if (connectionDown) "Reconnect" else "Disconnect")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Scanning is a static button; the preview opens in a
        // full-screen overlay and never reflows this tab.
        Button(
            onClick = {
                if (hasCameraPermission) cameraActive = true
                else permissionLauncher.launch(Manifest.permission.CAMERA)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (hasCameraPermission) "Open camera to scan address (QR code)" else "Grant camera permission to scan")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Tip: run 'qrencode -t ANSIUTF8 tc...' on the remote device to display its QR code in the terminal.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        // Manual paste is the least common path: collapsed behind a
        // toggle. When expanded, the hide control sits below the input.
        // Plain clickable text, not TextButton: buttons' 48dp touch
        // target adds a lot of padding around one small line.
        if (!showManualInput) {
            Text(
                "Or paste an address manually",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { showManualInput = true },
            )
        }
        if (showManualInput) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = manualAddress,
                    onValueChange = { manualAddress = it },
                    // Placeholder, not label: a floating label offsets
                    // the input text downward, out of line with the
                    // button's centered text.
                    placeholder = { Text("tc...") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                // Same height as the text field: a default-height
                // button centers against the field's 56dp box and
                // reads as floating next to the input line.
                Button(
                    onClick = {
                        if (manualAddress.startsWith("tc") && manualAddress.length > 2) {
                            onAddressScanned(manualAddress)
                            onUseAddress()
                        }
                    },
                    modifier = Modifier.height(56.dp),
                    enabled = manualAddress.startsWith("tc") && manualAddress.length > 2,
                ) {
                    Text("Use")
                }
            }
            Text(
                "Hide manual address input",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { showManualInput = false },
            )
        }

        Spacer(Modifier.height(16.dp))

        // Saved addresses: the only scrollable element, taking the
        // remaining height. Tapping a card opens the rename dialog,
        // which also carries the Remove action.
        Text("Saved addresses", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (savedAddresses.addresses.isEmpty()) {
                item {
                    Text(
                        "No saved addresses yet — connect to one above and tap Save to keep it here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(savedAddresses.addresses, key = { it.address }) { saved ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        renameTarget = saved.address
                                        renameInput = saved.alias
                                        showRenameDialog = true
                                    },
                            ) {
                                Text(saved.alias, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${saved.address.take(24)}...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = { onAddressScanned(saved.address) },
                                enabled = scannedKey == null || addressKey(saved.address) != scannedKey,
                            ) {
                                Text("Connect")
                            }
                        }
                    }
                }
            }
        }
    }

    // Full-screen camera overlay: the tab keeps its layout while the
    // scanner runs on top of it. Auto-closes on a valid address.
    if (cameraActive) {
        Dialog(
            onDismissRequest = { cameraActive = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                TokenScanner(
                    modifier = Modifier.fillMaxSize(),
                    onToken = { token ->
                        // Only accept tokens that look like tailcat addresses
                        if (token.startsWith("tc") && token.length > 10 && token != scannedAddress) {
                            cameraActive = false
                            onAddressScanned(token)
                        }
                    },
                )
                // Close sits in the top corner, kept clear of the
                // system bars: at the bottom it ended up under the
                // navigation/action buttons on some devices.
                IconButton(
                    onClick = { cameraActive = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .systemBarsPadding()
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close camera",
                        tint = Color.White,
                    )
                }
            }
        }
    }

    // Tapping the address opens this: the full address, selectable for
    // copying, plus a copy-to-clipboard action.
    if (showAddressDialog && connected) {
        AlertDialog(
            onDismissRequest = { showAddressDialog = false },
            title = { Text("Address", style = MaterialTheme.typography.titleMedium) },
            text = {
                SelectionContainer {
                    Text(
                        scannedAddress,
                        style = MaterialTheme.typography.bodyMedium,
                        softWrap = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("tailcat address", scannedAddress))
                    Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
                    showAddressDialog = false
                }) {
                    Text("Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddressDialog = false }) {
                    Text("Close")
                }
            },
        )
    }

    // Save dialog
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save address") },
            text = {
                Column {
                    Text("Give this address a friendly name:", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = aliasInput,
                        onValueChange = { aliasInput = it },
                        label = { Text("Alias (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    savedAddresses.add(aliasInput, scannedAddress)
                    showSaveDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Rename dialog: change the alias of a saved address.
    if (showRenameDialog && renameTarget != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename") },
            text = {
                Column {
                    Text("Change the alias for this address:", style = MaterialTheme.typography.bodySmall)
                    // The card shows a truncated address; the dialog
                    // shows the full one, selectable for copying.
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Text(
                            renameTarget!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = renameInput,
                        onValueChange = { renameInput = it },
                        label = { Text("Alias") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    savedAddresses.rename(renameTarget!!, renameInput)
                    showRenameDialog = false
                }) {
                    Text("Rename")
                }
            },
            dismissButton = {
                // Remove lives here, not on the card: it hands off to
                // the confirmation dialog.
                Row {
                    TextButton(onClick = {
                        removeTarget = renameTarget
                        showRenameDialog = false
                    }) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { showRenameDialog = false }) {
                        Text("Cancel")
                    }
                }
            },
        )
    }

    // Remove confirmation: deleting a saved address also drops it from
    // the receive tab's "only saved addresses" allowlist after the next
    // restart, so it must be deliberate.
    if (removeTarget != null) {
        val alias = savedAddresses.addresses.firstOrNull { it.address == removeTarget }?.alias
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove saved device?") },
            text = {
                Text(
                    "Remove ${alias ?: "this device"} from the saved list? " +
                        "Nothing on the device is affected, but it will no longer be allowed to " +
                        "connect when 'Only saved addresses may connect' is on.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    savedAddresses.remove(removeTarget!!)
                    removeTarget = null
                }) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private data class ProbeResult(
    val direct: Boolean,
    val via: String,
    val latencyMs: Long,
    val canRead: Boolean,
    val canWrite: Boolean,
)

private fun PermissionCache.ProbeInfo.toProbeResult() = ProbeResult(
    direct = direct,
    via = via,
    latencyMs = latencyMs,
    canRead = canRead,
    canWrite = canWrite,
)
