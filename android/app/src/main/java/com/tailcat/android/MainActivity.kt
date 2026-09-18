package com.tailcat.android

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.tailcat.android.ui.screens.BrowseScreen
import com.tailcat.android.ui.screens.PermissionCache
import com.tailcat.android.ui.screens.ReceiveScreen
import com.tailcat.android.ui.screens.ReceiveState
import com.tailcat.android.ui.screens.ScanScreen
import com.tailcat.android.ui.screens.SendScreen
import com.tailcat.android.ui.screens.SendTransfer
import com.tailcat.android.ui.theme.TailcatTheme
import com.tailcat.android.SavedAddresses
import com.tailcat.android.TailcatClient
import bridge.Bridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pass the app's files directory to the Go bridge for SSH key storage.
        bridge.Bridge.setAppDataDir(filesDir.absolutePath)
        // Debug-only profiling endpoint on 127.0.0.1:6060, reachable
        // from the dev machine via `adb forward tcp:6060 tcp:6060`.
        // Inert unless something dials it.
        bridge.Bridge.startPprof(6060)
        setContent {
            TailcatTheme {
                TailcatApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TailcatApp() {
    var scannedAddress by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) }

    // App-level scope: outlives tab switches, so the receive server's
    // file-saving callbacks keep working while the Receive tab is out
    // of composition (a screen-local scope would be cancelled there).
    val appScope = rememberCoroutineScope()

    // Server state lives at the app level so it survives tab switches.
    // Only stopped when the user taps "Stop listening" or the app exits.
    var server by remember { mutableStateOf<TailcatServer?>(null) }
    var receiveState by remember { mutableStateOf(ReceiveState()) }

    // An in-flight or finished send, hoisted above the tabs: the
    // transfer runs on the app-level scope and survives tab switches.
    var sendTransfer by remember { mutableStateOf<SendTransfer?>(null) }
    // The running send's job, so the cancel button can stop it.
    var sendJob by remember { mutableStateOf<Job?>(null) }

    // Cached permission probe results, keyed by address. Survives tab switches
    // so we don't re-probe the same server every time the user switches tabs.
    val permCache = remember { PermissionCache() }

    // Saved remote addresses with user-defined aliases.
    val context = LocalContext.current
    val savedAddresses = remember { SavedAddresses(context) }

    // Track the last known address for the watchdog so it doesn't capture stale state.
    var watchdogAddress by remember { mutableStateOf("") }

    // One persistent watchdog client per connection: every 3s ping
    // reuses the same engine instead of building a fresh one (new
    // keys, netcheck, DERP TLS handshake). The per-ping engines cost
    // real CPU during transfers and registered a new peer identity on
    // the server on every ping.
    val watchdogClient = remember { mutableStateOf<TailcatClient?>(null) }

    // Background ping watchdog: pings the connected server every 3s.
    // After 2 consecutive failures, disconnects and shows a toast.
    // Every 30s, also rechecks permissions and toasts if they changed.
    LaunchedEffect(scannedAddress) {
        if (scannedAddress.isEmpty() || !scannedAddress.startsWith("tc")) {
            watchdogAddress = ""
            return@LaunchedEffect
        }
        watchdogAddress = scannedAddress
        var failures = 0
        var pingCount = 0
        try {
            while (watchdogAddress == scannedAddress && scannedAddress.isNotEmpty()) {
                delay(3_000)
                if (watchdogAddress != scannedAddress) break
                try {
                    withContext(Dispatchers.IO) {
                        if (watchdogClient.value == null) {
                            watchdogClient.value = TailcatClient(scannedAddress, "")
                        }
                        val client = watchdogClient.value!!
                        // Disco ping, not plain ping: the meow handshake
                        // behind Ping happens only once per client, so
                        // later Ping calls succeed without contacting
                        // the server. A disco ping is a round trip
                        // every time, which is what a liveness check
                        // needs — and it keeps the direct path fresh.
                        client.discoPingWithTimeout(5)
                        pingCount++
                        if (pingCount % 20 == 0) {
                            val perms = client.checkPermissions()
                            val cached = permCache.get(scannedAddress)
                            if (cached != null && (cached.canRead != perms.canRead || cached.canWrite != perms.canWrite)) {
                                val oldPerms = listOfNotNull(
                                    if (cached.canRead) "Read" else null,
                                    if (cached.canWrite) "Write" else null,
                                ).ifEmpty { listOf("No access") }
                                val newPerms = listOfNotNull(
                                    if (perms.canRead) "Read" else null,
                                    if (perms.canWrite) "Write" else null,
                                ).ifEmpty { listOf("No access") }
                                permCache.put(scannedAddress, PermissionCache.ProbeInfo(
                                    canRead = perms.canRead,
                                    canWrite = perms.canWrite,
                                    direct = cached.direct,
                                    via = cached.via,
                                    latencyMs = cached.latencyMs,
                                ))
                                Toast.makeText(context, "Permissions changed: ${oldPerms.joinToString("+")} -> ${newPerms.joinToString("+")}", Toast.LENGTH_LONG).show()
                            }
                        }
                        // The client stays open between pings; its engine
                        // keeps the DERP relay connection alive on its own.
                    }
                    failures = 0
                } catch (e: Exception) {
                    failures++
                    if (failures >= 2) {
                        val lostAddr = scannedAddress
                        permCache.clear(lostAddr)
                        scannedAddress = ""
                        Toast.makeText(context, "Connection to server lost: $lostAddr", Toast.LENGTH_LONG).show()
                        break
                    }
                }
            }
        } finally {
            // Close the persistent client whether the connection was
            // lost or the effect was cancelled by an address change.
            val client = watchdogClient.value
            watchdogClient.value = null
            if (client != null) {
                withContext(NonCancellable + Dispatchers.IO) { client.close() }
            }
        }
    }

    // Stop server when the app exits entirely.
    DisposableEffect(Unit) {
        onDispose {
            server?.stop()
            server = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.ic_logo),
                            contentDescription = null, // the adjacent text names the app
                            modifier = Modifier.height(40.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Tailcat", style = MaterialTheme.typography.titleLarge)
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Link, contentDescription = "Connect") },
                    label = { Text("Connect") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Folder, contentDescription = "Browse") },
                    label = { Text("Browse") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                )
                NavigationBarItem(
                    icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") },
                    label = { Text("Send") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Download, contentDescription = "Receive") },
                    label = { Text("Receive") },
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                )
            }
        },
    ) { innerPadding ->
        when (selectedTab) {
            0 -> ScanScreen(
                scannedAddress = scannedAddress,
                onAddressScanned = { addr ->
                    if (addr != scannedAddress) permCache.clear(addr)
                    scannedAddress = addr
                },
                onUseAddress = { selectedTab = 1 },
                onClearAddress = { scannedAddress = "" },
                permCache = permCache,
                savedAddresses = savedAddresses,
                modifier = Modifier.padding(innerPadding),
            )
            1 -> BrowseScreen(
                address = scannedAddress,
                modifier = Modifier.padding(innerPadding),
            )
            2 -> SendScreen(
                address = scannedAddress,
                transfer = sendTransfer,
                onTransferChange = { sendTransfer = it(sendTransfer) },
                onSendJobChange = { sendJob = it },
                cancelSend = { sendJob?.cancel() },
                scope = appScope,
                permCache = permCache,
                modifier = Modifier.padding(innerPadding),
            )
            3 -> ReceiveScreen(
                state = receiveState,
                onStateChange = { receiveState = it(receiveState) },
                server = server,
                onServerChange = { server = it },
                scannedAddress = scannedAddress,
                permCache = permCache,
                savedAddresses = savedAddresses,
                scope = appScope,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}
