package com.tailcat.android

import bridge.Bridge
import bridge.Client
import bridge.Conn
import bridge.ConnectionListener
import bridge.DiscoPingResult
import bridge.CheckPermissionsResult
import bridge.FileLister
import bridge.ProgressListener
import bridge.SFTPClient
import bridge.Server
import java.io.InputStream

/**
 * ConnectionListener implemented in Kotlin to receive incoming
 * connections when the phone is a server.
 */
interface TailcatConnectionListener {
    fun onConnection(conn: TailcatConn)
}

/**
 * Wraps the gomobile-generated bridge.Conn behind a friendlier Kotlin API.
 * Read returns a ByteArray (data) or null (EOF), matching the Go bridge's
 * nil-on-EOF convention.
 */
class TailcatConn(private val conn: Conn) {

    /** Read the next chunk (up to 64 KB), or null on EOF. */
    fun read(): ByteArray? {
        val data = conn.read() ?: return null
        return data
    }

    /** Write data through the tunnel. */
    fun write(data: ByteArray) {
        conn.write(data)
    }

    /** Half-close the write side (send FIN, netcat style). */
    fun closeWrite() {
        conn.closeWrite()
    }

    /** Close the connection. */
    fun close() {
        conn.close()
    }
}

/**
 * Wraps the gomobile-generated bridge.Server behind a friendlier Kotlin API.
 * Call start() to begin listening; addr() returns the "tc..." address to share.
 */
class TailcatServer(
    private val derpMapURL: String,
    private val listener: TailcatConnectionListener,
) {
    private var server: Server? = null

    fun start(keyJSON: String, allowedKeysJSON: String = ""): String {
        val srv = Bridge.newServer(derpMapURL, keyJSON, allowedKeysJSON, object : ConnectionListener {
            override fun onConnection(c: Conn) {
                listener.onConnection(TailcatConn(c))
            }
        })
        server = srv
        return srv.addr()
    }

    fun startSFTP(filesDir: String, mode: String, keyJSON: String, allowedKeysJSON: String = ""): String {
        val srv = Bridge.newSFTPServer(derpMapURL, filesDir, mode, keyJSON, allowedKeysJSON, object : ConnectionListener {
            override fun onConnection(c: Conn) {
                listener.onConnection(TailcatConn(c))
            }
        })
        server = srv
        return srv.addr()
    }

    fun addr(): String = server?.addr() ?: ""

    /**
     * Admits one more client node key (base64, as returned by
     * [addressKey]) on the running engine: a listening server accepts
     * the new client immediately, without a restart or address change.
     * Returns false if the server is stopped or the key is invalid.
     */
    fun allowClient(keyB64: String): Boolean = try {
        server?.addAllowedClient(keyB64) ?: error("server is not running")
        true
    } catch (e: Exception) {
        false
    }

    /**
     * JSON array with one entry per connected client: {"key": ...,
     * "curAddr": ..., "relay": ..., "rx": ..., "tx": ..., "active": ...}.
     * "[]" when no client is connected. Poll alongside [relayOnline].
     */
    fun peersJSON(): String = server?.peersJSON() ?: "[]"

    /**
     * Whether the server currently has a connection to its home DERP
     * relay. The shared address is unreachable while this is false,
     * even though the server is listening; the bridge's watchdog
     * rebuilds the engine to restore it.
     */
    fun relayOnline(): Boolean = server?.relayOnline() ?: false

    /** Consecutive watchdog checks with no relay connection; resets to zero once re-established. */
    fun relayMisses(): Int = server?.relayMisses()?.toInt() ?: 0

    /** Rebuild the network engine (same address) to restore a lost relay connection. */
    fun restart(): Boolean = try {
        server?.restart()
        true
    } catch (e: Exception) {
        false
    }

    fun stop() {
        server?.close()
        server = null
    }
}

/**
 * Loads the stable identity key from disk, or creates and saves a new one.
 * The key is a JSON-serialized tailcat.PrivateKey. Using a stable key means
 * the server's "tc..." address stays the same across app restarts.
 */
object TailcatKey {
    fun loadOrCreate(): String = Bridge.loadOrCreateKey()

    /**
     * Replaces the persisted identity key, changing the address on the
     * next server start. With [withPSK] the new key embeds a pre-shared
     * key so the address stays a secret credential; without one, access
     * control must come from the allowed-clients list.
     */
    fun rotate(withPSK: Boolean): String = Bridge.rotateKey(withPSK)
}

/**
 * Wraps the gomobile-generated bridge.Client.
 * Call ping() to establish the tunnel, then dial() to open a TCP stream.
 */
class TailcatClient(addr: String, derpMapURL: String) {
    private var client: Client = Bridge.newClient(addr, derpMapURL)

    fun ping() {
        client.ping()
    }

    fun pingWithTimeout(timeoutSeconds: Long) {
        client.pingWithTimeout(timeoutSeconds)
    }

    /** Returns connection path info: direct (P2P) vs DERP relay, latency, endpoint. */
    fun discoPing(): DiscoPingResult = client.discoPing()

    /**
     * A real round trip on every call — unlike [ping], whose handshake
     * happens only once per client — so this is the probe for repeated
     * liveness checks on a long-lived client. Throws if no pong
     * arrives within the timeout (server down or unreachable).
     */
    fun discoPingWithTimeout(timeoutSeconds: Long): DiscoPingResult =
        client.discoPingWithTimeout(timeoutSeconds)

    /** Repeats disco pings up to maxAttempts until direct (P2P) is achieved. */
    fun discoPingUntilDirect(maxAttempts: Int): DiscoPingResult = client.discoPingUntilDirect(maxAttempts.toLong())

    /** Probes the remote SFTP server for read/write permissions. */
    fun checkPermissions(): CheckPermissionsResult = client.checkPermissions()

    fun dial(port: Long): TailcatConn {
        return TailcatConn(client.dial(port))
    }

    fun close() {
        client.close()
    }

    fun dialSFTP(): TailcatSFTPClient {
        return TailcatSFTPClient(client.dialSFTP())
    }
}

/**
 * Wraps the gomobile-generated bridge.SFTPClient for browsing and
 * downloading files from a remote SFTP server.
 */
class TailcatSFTPClient(private val sftp: SFTPClient) {

    data class RemoteFileEntry(val name: String, val size: Long, val isDir: Boolean)

    fun listDir(path: String): List<RemoteFileEntry> {
        val files = mutableListOf<RemoteFileEntry>()
        sftp.listDir(path, object : FileLister {
            override fun onFile(name: String, size: Long, isDir: Boolean) {
                files.add(RemoteFileEntry(name, size, isDir))
            }
        })
        // Sort: directories first, then files, each group alphabetical (case-insensitive)
        return files.sortedWith(
            compareBy<RemoteFileEntry> { !it.isDir }
                .thenBy { it.name.lowercase() }
        )
    }

    fun downloadFile(remotePath: String, localPath: String): Long {
        return sftp.downloadFile(remotePath, localPath)
    }

    /** Downloads with progress: onProgress(bytesWritten, bytesTotal), from a background thread. */
    fun downloadFile(remotePath: String, localPath: String, onProgress: (sent: Long, total: Long) -> Unit): Long {
        return sftp.downloadFileWithProgress(remotePath, localPath, object : ProgressListener {
            override fun onProgress(sent: Long, total: Long) {
                onProgress(sent, total)
            }
            override fun isCancelled(): Boolean = false
        })
    }

    /** Downloads with per-transfer progress and cancel, like the [uploadFile] overload. */
    fun downloadFile(
        remotePath: String,
        localPath: String,
        onProgress: (sent: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean,
    ): Long {
        return sftp.downloadFileWithProgress(remotePath, localPath, object : ProgressListener {
            override fun onProgress(sent: Long, total: Long) {
                onProgress(sent, total)
            }
            override fun isCancelled(): Boolean = isCancelled()
        })
    }

    fun uploadFile(localPath: String, remotePath: String): Long {
        return sftp.uploadFile(localPath, remotePath)
    }

    /** Uploads with per-file progress: onProgress(bytesSent, bytesTotal), from a background thread. */
    fun uploadFile(localPath: String, remotePath: String, onProgress: (sent: Long, total: Long) -> Unit): Long {
        return sftp.uploadFileWithProgress(localPath, remotePath, object : ProgressListener {
            override fun onProgress(sent: Long, total: Long) {
                onProgress(sent, total)
            }
            override fun isCancelled(): Boolean = false
        })
    }

    /**
     * Uploads with per-file progress and a per-transfer cancel: the
     * transfer polls [isCancelled] before every chunk and aborts if it
     * returns true. Per-transfer, so parallel transfers over one
     * session cancel independently.
     */
    fun uploadFile(
        localPath: String,
        remotePath: String,
        onProgress: (sent: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean,
    ): Long {
        return sftp.uploadFileWithProgress(localPath, remotePath, object : ProgressListener {
            override fun onProgress(sent: Long, total: Long) {
                onProgress(sent, total)
            }
            override fun isCancelled(): Boolean = isCancelled()
        })
    }

    fun uploadFileGetPath(localPath: String, remotePath: String): String {
        return sftp.uploadFileGetPath(localPath, remotePath)
    }

    fun uploadDir(localDir: String, remoteDir: String): Long {
        return sftp.uploadDir(localDir, remoteDir)
    }

    fun close() {
        sftp.close()
    }
}

/**
 * Returns the node public key of the device that shared the given
 * "tc..." address, base64-encoded, or null if the address cannot be
 * parsed. The key is the device's tunnel identity: it matches the
 * "key" field of that device's entry in TailcatServer.peersJSON, and
 * can be passed to TailcatServer.allowClient to admit the device.
 */
fun addressKey(addr: String): String? = try {
    Bridge.addrKey(addr)
} catch (e: Exception) {
    null
}

/**
 * Returns the tailcat address for an identity key JSON (as produced
 * by TailcatKey.loadOrCreate) without starting a server, or null on
 * failure. The Receive tab shows it before the first start.
 */
fun keyAddress(keyJSON: String): String? = try {
    Bridge.keyAddress(keyJSON)
} catch (e: Exception) {
    null
}

/**
 * Helper: stream an InputStream to the tunnel in 64 KB chunks, then
 * half-close and read until EOF for delivery confirmation. Returns
 * the total bytes sent.
 */
fun streamToConn(input: InputStream, conn: TailcatConn, onProgress: (Long) -> Unit): Long {
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n <= 0) break
        val chunk = if (n == buf.size) buf else buf.copyOf(n)
        conn.write(chunk)
        total += n
        onProgress(total)
    }
    conn.closeWrite()
    // Read until EOF to confirm the peer received everything.
    while (conn.read() != null) { }
    return total
}
