package com.tailcat.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.tailcat.android.ui.screens.ReceiveScreen
import com.tailcat.android.ui.screens.ReceiveState
import com.tailcat.android.ui.screens.ReceivedFile
import com.tailcat.android.ui.screens.ScanScreen
import com.tailcat.android.ui.screens.SendScreen
import com.tailcat.android.ui.theme.TailcatTheme
import bridge.Bridge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pass the app's files directory to the Go bridge for SSH key storage.
        bridge.Bridge.setAppDataDir(filesDir.absolutePath)
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
    var scannedToken by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) }

    // Server state lives at the app level so it survives tab switches.
    // Only stopped when the user taps "Stop listening" or the app exits.
    var server by remember { mutableStateOf<TailcatServer?>(null) }
    var receiveState by remember { mutableStateOf(ReceiveState()) }

    // Stop server when the app exits entirely.
    DisposableEffect(Unit) {
        onDispose {
            server?.stop()
            server = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Tailcat") })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan") },
                    label = { Text("Scan") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                )
                NavigationBarItem(
                    icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") },
                    label = { Text("Send") },
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        scannedToken = ""
                    },
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Download, contentDescription = "Receive") },
                    label = { Text("Receive") },
                    selected = selectedTab == 2,
                    onClick = {
                        selectedTab = 2
                        scannedToken = ""
                    },
                )
            }
        },
    ) { innerPadding ->
        when (selectedTab) {
            0 -> ScanScreen(
                scannedToken = scannedToken,
                onTokenScanned = { token -> scannedToken = token },
                onUseToken = { selectedTab = 1 },
                modifier = Modifier.padding(innerPadding),
            )
            1 -> SendScreen(initialToken = scannedToken, modifier = Modifier.padding(innerPadding), receiveToken = receiveState.token)
            2 -> ReceiveScreen(
                state = receiveState,
                onStateChange = { receiveState = it },
                server = server,
                onServerChange = { server = it },
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}
