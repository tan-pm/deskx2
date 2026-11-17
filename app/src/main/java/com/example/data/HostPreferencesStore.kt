package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.DeckLayoutConfig
import com.example.model.PcHostProfile
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.deckDataStore by preferencesDataStore(name = "deskdeck_prefs")

class HostPreferencesStore(
    private val context: Context,
    private val moshi: Moshi,
) {
    private val hostsKey = stringPreferencesKey("saved_hosts_json")
    private val keepScreenOnKey = booleanPreferencesKey("keep_screen_on")
    private val cachedLayoutKey = stringPreferencesKey("cached_layout_json")

    private val hostListType = Types.newParameterizedType(List::class.java, PcHostProfile::class.java)
    private val hostListAdapter = moshi.adapter<List<PcHostProfile>>(hostListType)
    private val layoutAdapter = moshi.adapter(DeckLayoutConfig::class.java)

    val savedHosts: Flow<List<PcHostProfile>> = context.deckDataStore.data.map { prefs ->
        val raw = prefs[hostsKey] ?: return@map emptyList()
        runCatching { hostListAdapter.fromJson(raw) }.getOrNull().orEmpty()
    }

    val keepScreenOn: Flow<Boolean> = context.deckDataStore.data.map { prefs ->
        prefs[keepScreenOnKey] ?: true
    }

    val cachedLayout: Flow<DeckLayoutConfig?> = context.deckDataStore.data.map { prefs ->
        val raw = prefs[cachedLayoutKey] ?: return@map null
        runCatching { layoutAdapter.fromJson(raw) }.getOrNull()
    }

    suspend fun saveOrUpdateHost(profile: PcHostProfile) {
        context.deckDataStore.edit { prefs ->
            val current = prefs[hostsKey]?.let {
                runCatching { hostListAdapter.fromJson(it) }.getOrNull()
            }.orEmpty().toMutableList()
            current.removeAll { it.id == profile.id || (it.host == profile.host && it.port == profile.port) }
            current.add(0, profile.copy(lastConnectedAt = System.currentTimeMillis()))
            prefs[hostsKey] = hostListAdapter.toJson(current.take(8))
        }
    }

    suspend fun removeHost(hostId: String) {
        context.deckDataStore.edit { prefs ->
            val current = prefs[hostsKey]?.let {
                runCatching { hostListAdapter.fromJson(it) }.getOrNull()
            }.orEmpty().filterNot { it.id == hostId }
            prefs[hostsKey] = hostListAdapter.toJson(current)
        }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.deckDataStore.edit { prefs ->
            prefs[keepScreenOnKey] = enabled
        }
    }

    suspend fun saveLayout(layout: DeckLayoutConfig) {
        context.deckDataStore.edit { prefs ->
            prefs[cachedLayoutKey] = layoutAdapter.toJson(layout)
        }
    }
}
