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
import com.tailcat.android.ui.screens.BrowseState
import com.tailcat.android.ui.screens.LivePing
import com.tailcat.android.ui.screens.PermissionCache
import com.tailcat.android.ui.screens.ReceiveScreen
import com.tailcat.android.ui.screens.ReceiveState
import com.tailcat.android.ui.screens.ScanScreen
import com.tailcat.android.ui.screens.SelectedFile
import com.tailcat.android.ui.screens.SendScreen
import com.tailcat.android.ui.screens.SendTransfer
import com.tailcat.android.ui.theme.TailcatTheme
import com.tailcat.android.SavedAddresses
import com.tailcat.android.TailcatClient
import bridge.Bridge
import kotlinx.coroutines.CancellationException
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

    // The Send tab's file selection, hoisted with the transfer so
    // switching tabs doesn't clear the chosen files.
    var sendSelection by remember { mutableStateOf<List<SelectedFile>>(emptyList()) }

    // Cached permission probe results, keyed by address. Survives tab switches
    // so we don't re-probe the same server every time the user switches tabs.
    val permCache = remember { PermissionCache() }

    // Browse tab state, hoisted with the connection: the listing, the
    // current directory, and the SFTP session survive tab switches.
    // The connection closes when the address changes or the app
    // exits, never on a tab switch.
    val browseState = remember { BrowseState() }

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

    // A watchdog-detected connection loss keeps the address on the
    // Connect card (shown as "connection lost" with Retry available)
    // instead of clearing it, so the user can retry without
    // re-scanning. watchdogAttempt restarts the watchdog after a loss.
    var connectionLost by remember { mutableStateOf(false) }
    var watchdogAttempt by remember { mutableStateOf(0) }

    // The latest watchdog disco ping: latency and connection type,
    // refreshed with every 3s alive ping and shown live on the
    // Connect card.
    var livePing by remember { mutableStateOf<LivePing?>(null) }

    // Disconnect keeps the address on the card in a greyed
    // "disconnected" state; Reconnect brings it back without
    // re-scanning.
    var userDisconnected by remember { mutableStateOf(false) }

    // Tabs other than Connect see no device while the connection is
    // down: Disconnect keeps the address on the Connect card for
    // reconnecting, but nothing else may talk to the device.
    val activeAddress = if (connectionLost || userDisconnected) "" else scannedAddress

    // Bumped when the watchdog's periodic permission recheck notices a
    // change (the remote serve mode switched): ScanScreen re-reads the
    // fresh cache so the card updates without a new network probe.
    var probeRefreshTick by remember { mutableStateOf(0) }

    // Background ping watchdog: pings the connected server every 3s.
    // A brief outage (the remote serve restarting) is a blip, not a
    // loss: the client is rebuilt on every failure so the connection
    // resumes on its own. A sustained run of failures (~20s, with
    // fast retries while failing) flags the card as lost and stops
    // the watchdog — reconnecting is then intentional, via the card's
    // Reconnect. Permissions are rechecked every ~6s so a serve mode
    // change shows on the card without any lost/recovered dance.
    LaunchedEffect(scannedAddress, watchdogAttempt) {
        if (scannedAddress.isEmpty() || !scannedAddress.startsWith("tc")) {
            watchdogAddress = ""
            return@LaunchedEffect
        }
        watchdogAddress = scannedAddress
        livePing = null
        var failures = 0
        var pingCount = 0
        try {
            while (watchdogAddress == scannedAddress && scannedAddress.isNotEmpty()) {
                // Fast retry while failing: on the normal 3s-delay +
                // 5s-timeout cycle, five failures take ~40s to flag a
                // dead server. While failing, retry every second with a
                // 2s budget — a dead server then flags ~20s after it
                // dies, while a serve restart (back within ~10s)
                // clears the failure count before it ever flags.
                delay(if (failures == 0) 3_000 else 1_000)
                if (watchdogAddress != scannedAddress) break
                try {
                    // Toasts must fire on the main thread — showing one
                    // from Dispatchers.IO throws, which the catch below
                    // would swallow as a ping failure and make the
                    // watchdog flap. Set flags here, toast after.
                    var permsChangedMsg: String? = null
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
                        val disco = client.discoPingWithTimeout(if (failures == 0) 5 else 2)
                        pingCount++
                        if (connectionLost) {
                            // Auto-recovery, silent: the server came
                            // back and the card just resumes its live
                            // values.
                            connectionLost = false
                        }
                        livePing = LivePing(
                            seq = pingCount.toLong(),
                            latencyMs = disco.latency,
                            direct = disco.direct,
                            via = disco.via,
                        )
                        // Recheck permissions roughly every 6s: a serve
                        // mode change on the remote (ro -> rw) must show
                        // on the card quickly, not minutes later.
                        if (pingCount % 2 == 0) {
                            try {
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
                                    permsChangedMsg = "Permissions changed: ${oldPerms.joinToString("+")} -> ${newPerms.joinToString("+")}"
                                }
                            } catch (_: Exception) {
                                // A failed recheck is not a connection
                                // loss — the disco ping above succeeded.
                                // Swallow it so it never counts toward
                                // the lost threshold.
                            }
                        }
                        // The client stays open between pings; its engine
                        // keeps the DERP relay connection alive on its own.
                    }
                    failures = 0
                    permsChangedMsg?.let {
                        Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                        // Tell the card to re-read the fresh cache.
                        probeRefreshTick++
                    }
                } catch (e: CancellationException) {
                    // Effect cancelled (address changed, disconnect):
                    // not a ping failure.
                    throw e
                } catch (e: Exception) {
                    failures++
                    // Rebuild the client on every failure, independently
                    // of the lost flag: a failed ping usually means the
                    // server restarted and dropped this client's peer
                    // state, and the meow handshake runs only once per
                    // client — the old client can never recover. A fresh
                    // client re-meows on its next ping, so latency
                    // resumes as soon as the server is back, without the
                    // card ever flagging a brief restart as lost.
                    withContext(Dispatchers.IO) { watchdogClient.value?.close() }
                    watchdogClient.value = null
                    if (failures >= 5 && !connectionLost) {
                        // Sustained outage (~20s), not a blip: keep the
                        // address on the card in a "connection lost"
                        // state and stop trying — reconnecting is an
                        // intentional act via the card's Reconnect.
                        permCache.clear(scannedAddress)
                        connectionLost = true
                        livePing = null
                        Toast.makeText(context, "Connection to server lost: $scannedAddress", Toast.LENGTH_LONG).show()
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
            browseState.closeConnection()
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
                        // Brand and tab name share the same size; the
                        // tab name is dimmed to keep the hierarchy. The
                        // dash is its own element with equal spacing on
                        // both sides.
                        Text("Tailcat", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "-",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            listOf("Connect", "Browse", "Send", "Receive")[selectedTab],
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                    if (addr != scannedAddress) {
                        permCache.clear(addr)
                    } else if (connectionLost || userDisconnected) {
                        // Re-selecting the same address doesn't change
                        // the watchdog's key: bump it to restart.
                        watchdogAttempt++
                    }
                    scannedAddress = addr
                    connectionLost = false
                    userDisconnected = false
                },
                onUseAddress = { selectedTab = 1 },
                onDisconnect = {
                    userDisconnected = true
                    connectionLost = false
                    livePing = null
                    // Drop the probe cache too, so reconnect shows fresh
                    // measurements instead of the pre-disconnect ones.
                    permCache.clear(scannedAddress)
                    // Stops the watchdog loop (its guard no longer
                    // matches) without clearing the address.
                    watchdogAddress = ""
                },
                onClearAddress = {
                    permCache.clear(scannedAddress)
                    scannedAddress = ""
                    connectionLost = false
                    userDisconnected = false
                    livePing = null
                },
                connectionLost = connectionLost,
                userDisconnected = userDisconnected,
                onReconnect = {
                    // connectionLost stays set until the watchdog's first
                    // successful ping clears it: Reconnect must stay
                    // tappable while the retry is in flight.
                    userDisconnected = false
                    watchdogAttempt++
                },
                livePing = livePing,
                probeRefreshTick = probeRefreshTick,
                permCache = permCache,
                savedAddresses = savedAddresses,
                modifier = Modifier.padding(innerPadding),
            )
            1 -> BrowseScreen(
                address = activeAddress,
                state = browseState,
                scope = appScope,
                permCache = permCache,
                modifier = Modifier.padding(innerPadding),
            )
            2 -> SendScreen(
                address = activeAddress,
                selectedFiles = sendSelection,
                onSelectedFilesChange = { sendSelection = it },
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
