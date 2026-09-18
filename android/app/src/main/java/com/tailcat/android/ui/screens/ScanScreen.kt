package com.tailcat.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import com.tailcat.android.SavedAddresses
import com.tailcat.android.ocr.TokenScanner

@Composable
fun ScanScreen(
    scannedAddress: String,
    onAddressScanned: (String) -> Unit,
    onUseAddress: () -> Unit,
    onClearAddress: () -> Unit,
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

    // Camera is off by default; user taps "Open camera" to start scanning.
    // Auto-closes when a valid tc... address is scanned.
    var cameraActive by remember { mutableStateOf(false) }

    var manualAddress by remember { mutableStateOf("") }

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
    var probeStatus by remember { mutableStateOf("") }
    // Incremented by the Retry button to re-run the probe.
    var probeAttempt by remember { mutableStateOf(0) }

    // Auto-probe when a new address is scanned — but skip if already cached
    LaunchedEffect(scannedAddress, probeAttempt) {
        if (scannedAddress.isEmpty() || !scannedAddress.startsWith("tc")) {
            probeResult = null
            probeError = null
            probeStatus = ""
            return@LaunchedEffect
        }
        val cached = permCache.get(scannedAddress)
        if (cached != null) {
            probeResult = cached.toProbeResult()
            probing = false
            probeError = null
            probeStatus = ""
            return@LaunchedEffect
        }
        probing = true
        probeError = null
        probeResult = null
        probeStatus = "Pinging..."
        try {
            val info = probeAddress(scannedAddress, permCache) { probeStatus = it }
            probeResult = info.toProbeResult()
        } catch (e: Exception) {
            probeError = e.message
        }
        probing = false
        probeStatus = ""
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Connect", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        if (!hasCameraPermission) {
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Grant camera permission")
            }
        } else if (cameraActive) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
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
                OutlinedButton(
                    onClick = { cameraActive = false },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                ) {
                    Text("Close camera")
                }
            }
        } else {
            Button(
                onClick = { cameraActive = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open camera to scan address (QR code)")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Tip: run 'qrencode -t ANSIUTF8 tc...' on the remote device to display its QR code in the terminal.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        // Saved addresses section
        if (savedAddresses.addresses.isNotEmpty()) {
            Text("Saved addresses", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
            ) {
                items(savedAddresses.addresses) { saved ->
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
                                enabled = saved.address != scannedAddress,
                            ) {
                                Text("Connect")
                            }
                            TextButton(onClick = {
                                removeTarget = saved.address
                            }) {
                                Text("Remove")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        if (scannedAddress.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Address:", style = MaterialTheme.typography.labelMedium)
                    Text(
                        scannedAddress,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                    )
                    Spacer(Modifier.height(8.dp))

                    if (probing) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(probeStatus, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    probeResult?.let { r ->
                        Spacer(Modifier.height(8.dp))
                        val connColor = if (r.direct) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                        Text(
                            if (r.direct) "Direct connection (P2P)" else "Relayed via ${r.via}",
                            style = MaterialTheme.typography.bodySmall,
                            color = connColor,
                        )
                        Text(
                            "Latency: ${r.latencyMs} ms",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        val permParts = mutableListOf<String>()
                        if (r.canRead) permParts.add("Read")
                        if (r.canWrite) permParts.add("Write")
                        if (permParts.isEmpty()) permParts.add("No access")
                        Text(
                            "Permissions: ${permParts.joinToString(" + ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (permParts.isNotEmpty() && permParts[0] != "No access")
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }

                    probeError?.let {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Probe error: $it",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                onClick = { probeAttempt++ },
                                enabled = !probing,
                            ) {
                                Text("Retry")
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        if (!savedAddresses.contains(scannedAddress)) {
                            OutlinedButton(onClick = {
                                aliasInput = ""
                                showSaveDialog = true
                            }) {
                                Text("Save")
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        OutlinedButton(onClick = {
                            onClearAddress()
                            probeResult = null
                            probeError = null
                        }) {
                            Text("Disconnect")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Text("Or paste an address manually:", style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = manualAddress,
                onValueChange = { manualAddress = it },
                label = { Text("tc...") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (manualAddress.startsWith("tc") && manualAddress.length > 2) {
                        onAddressScanned(manualAddress)
                        onUseAddress()
                    }
                },
                enabled = manualAddress.startsWith("tc") && manualAddress.length > 2,
            ) {
                Text("Use")
            }
        }
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
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Remove confirmation: deleting a saved address also drops it from
    // the receive tab's "only saved devices" allowlist after the next
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
                        "connect when 'Only saved devices may connect' is on.",
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
