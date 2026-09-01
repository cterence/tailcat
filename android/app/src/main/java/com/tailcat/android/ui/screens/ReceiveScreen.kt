package com.tailcat.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tailcat.android.TailcatConn
import com.tailcat.android.TailcatConnectionListener
import com.tailcat.android.TailcatServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val token: String = "",
    val listening: Boolean = false,
    val error: String? = null,
    val receivedFiles: List<ReceivedFile> = emptyList(),
    val receiving: Boolean = false,
    val sftpMode: Boolean = true,
)

@Composable
fun ReceiveScreen(
    state: ReceiveState,
    onStateChange: (ReceiveState) -> Unit,
    server: TailcatServer?,
    onServerChange: (TailcatServer?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Receive", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        if (!state.listening) {
            Button(
                onClick = {
                    onStateChange(state.copy(error = null))
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val srv = TailcatServer("", object : TailcatConnectionListener {
                                    override fun onConnection(conn: TailcatConn) {
                                        scope.launch {
                                            val file = saveConnectionToFile(context, conn) { receiving ->
                                                onStateChange(state.copy(receiving = receiving))
                                            }
                                            onStateChange(state.copy(
                                                receivedFiles = state.receivedFiles + file,
                                                receiving = false,
                                            ))
                                        }
                                    }
                                })
                                val addr = if (state.sftpMode) {
                                    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                                    srv.startSFTP(downloads)
                                } else {
                                    srv.start()
                                }
                                onServerChange(srv)
                                onStateChange(state.copy(token = addr, listening = true))
                            }
                        } catch (e: Exception) {
                            onStateChange(state.copy(error = e.message))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Start listening")
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Your token:", style = MaterialTheme.typography.labelMedium)
                    Text(
                        state.token,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 3,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "On the other device: cat file | tailcat <token>",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("tailcat token", state.token))
                                Toast.makeText(context, "Token copied", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Copy")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, state.token)
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Share token"))
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Share")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    server?.stop()
                    onServerChange(null)
                    onStateChange(state.copy(listening = false, token = ""))
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
            ) {
                Text("Stop listening")
            }
        }

        if (state.receiving) {
            Spacer(Modifier.height(8.dp))
            Text("Receiving file...", color = MaterialTheme.colorScheme.primary)
        }

        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text("Error: $it", color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(16.dp))

        if (state.receivedFiles.isNotEmpty()) {
            Text("Received files:", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(state.receivedFiles) { file ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(file.name, style = MaterialTheme.typography.bodyLarge)
                        Text("${file.size} bytes", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
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
        // API 29+: use MediaStore.Downloads
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

    // API < 29: write directly to the public Downloads directory.
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
