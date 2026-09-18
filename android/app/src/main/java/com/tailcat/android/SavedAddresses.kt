package com.tailcat.android

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists saved tailcat addresses with user-defined aliases.
 * Stored in SharedPreferences as a JSON array of {alias, address} objects.
 * Observable via [addresses] for Compose recomposition.
 */
class SavedAddresses(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("tailcat_saved_addresses", Context.MODE_PRIVATE)

    val addresses = mutableStateListOf<SavedAddress>()

    init {
        load()
    }

    data class SavedAddress(
        val alias: String,
        val address: String,
    )

    private fun load() {
        val json = prefs.getString(KEY_ADDRESSES, null) ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                addresses.add(
                    SavedAddress(
                        alias = obj.getString("alias"),
                        address = obj.getString("address"),
                    )
                )
            }
        } catch (_: Exception) {
            // Corrupt data — start fresh
        }
    }

    private fun save() {
        val arr = JSONArray()
        for (a in addresses) {
            arr.put(JSONObject().apply {
                put("alias", a.alias)
                put("address", a.address)
            })
        }
        prefs.edit().putString(KEY_ADDRESSES, arr.toString()).apply()
    }

    fun add(alias: String, address: String) {
        // Replace if the same address already exists
        val existing = addresses.indexOfFirst { it.address == address }
        if (existing >= 0) {
            addresses[existing] = SavedAddress(alias.ifEmpty { address.take(16) }, address)
        } else {
            addresses.add(SavedAddress(alias.ifEmpty { address.take(16) }, address))
        }
        save()
    }

    fun rename(address: String, newAlias: String) {
        val idx = addresses.indexOfFirst { it.address == address }
        if (idx >= 0) {
            addresses[idx] = SavedAddress(newAlias.ifEmpty { address.take(16) }, address)
            save()
        }
    }

    fun remove(address: String) {
        addresses.removeAll { it.address == address }
        save()
    }

    fun contains(address: String): Boolean = addresses.any { it.address == address }

    companion object {
        private const val KEY_ADDRESSES = "addresses"
    }
}
