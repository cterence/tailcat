package com.tailcat.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tailcat.android.ocr.TokenScanner

@Composable
fun ScanScreen(
    scannedToken: String,
    onTokenScanned: (String) -> Unit,
    onUseToken: () -> Unit,
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

    var manualToken by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Scan QR Code", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        if (!hasCameraPermission) {
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Grant camera permission")
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                TokenScanner(
                    modifier = Modifier.fillMaxSize(),
                    onToken = { token ->
                        if (token != scannedToken) {
                            onTokenScanned(token)
                        }
                    },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (scannedToken.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Scanned token:", style = MaterialTheme.typography.labelMedium)
                    Text(
                        scannedToken,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onUseToken) {
                        Text("Use this token")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Text("Or paste a token manually:", style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = manualToken,
                onValueChange = { manualToken = it },
                label = { Text("tc...") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (manualToken.startsWith("tc") && manualToken.length > 2) {
                        onTokenScanned(manualToken)
                        onUseToken()
                    }
                },
                enabled = manualToken.startsWith("tc") && manualToken.length > 2,
            ) {
                Text("Use")
            }
        }
    }
}
