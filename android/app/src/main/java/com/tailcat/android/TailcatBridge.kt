package com.tailcat.android

import bridge.Bridge
import bridge.Client
import bridge.Conn
import bridge.ConnectionListener
import bridge.FileLister
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
 * Call start() to begin listening; addr() returns the "tc..." token to share.
 */
class TailcatServer(
    private val derpMapURL: String,
    private val listener: TailcatConnectionListener,
) {
    private var server: Server? = null

    fun start(): String {
        val srv = Bridge.newServer(derpMapURL, object : ConnectionListener {
            override fun onConnection(c: Conn) {
                listener.onConnection(TailcatConn(c))
            }
        })
        server = srv
        return srv.addr()
    }

    fun startSFTP(filesDir: String): String {
        val srv = Bridge.newSFTPServer(derpMapURL, filesDir, object : ConnectionListener {
            override fun onConnection(c: Conn) {
                listener.onConnection(TailcatConn(c))
            }
        })
        server = srv
        return srv.addr()
    }

    fun addr(): String = server?.addr() ?: ""

    fun stop() {
        server?.close()
        server = null
    }
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
        return files
    }

    fun downloadFile(remotePath: String, localPath: String): Long {
        return sftp.downloadFile(remotePath, localPath)
    }

    fun uploadFile(localPath: String, remotePath: String): Long {
        return sftp.uploadFile(localPath, remotePath)
    }

    fun close() {
        sftp.close()
    }
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
