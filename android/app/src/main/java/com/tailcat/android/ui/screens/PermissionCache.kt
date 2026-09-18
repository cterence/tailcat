package com.tailcat.android.ui.screens

import androidx.compose.runtime.mutableStateMapOf
import com.tailcat.android.TailcatClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cached probe results keyed by address string. Hoisted to the app level
 * so it survives tab switches — prevents re-probing the same server
 * (ping + SFTP handshake + read/write test) every time the user
 * switches between Connect, Browse, and Send tabs.
 */
class PermissionCache {
    private val cache = mutableStateMapOf<String, ProbeInfo>()

    data class ProbeInfo(
        val canRead: Boolean,
        val canWrite: Boolean,
        val direct: Boolean,
        val via: String,
        val latencyMs: Long,
    )

    fun get(address: String): ProbeInfo? = cache[address]

    fun put(address: String, info: ProbeInfo) {
        cache[address] = info
    }

    fun clear(address: String) {
        cache.remove(address)
    }

    fun clearAll() {
        cache.clear()
    }
}

/**
 * Probes a tailcat server (ping, connection type, permissions) and caches
 * the complete result. Returns the cached entry when present so callers
 * never re-probe an address that has already been checked. The cache entry
 * is always fully populated — connection type included — so whichever tab
 * probes first, the others see accurate data.
 */
suspend fun probeAddress(
    address: String,
    cache: PermissionCache,
    onStatus: (String) -> Unit = {},
): PermissionCache.ProbeInfo {
    cache.get(address)?.let { return it }
    return withContext(Dispatchers.IO) {
        val client = TailcatClient(address, "")
        onStatus("Pinging...")
        client.pingWithTimeout(15)
        onStatus("Checking connection type...")
        val disco = client.discoPingUntilDirect(5)
        onStatus(if (disco.direct) "Direct!" else "Via ${disco.via}, trying...")
        val perms = client.checkPermissions()
        client.close()
        val info = PermissionCache.ProbeInfo(
            canRead = perms.canRead,
            canWrite = perms.canWrite,
            direct = disco.direct,
            via = disco.via,
            latencyMs = disco.latency,
        )
        cache.put(address, info)
        info
    }
}
