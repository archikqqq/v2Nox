package com.v2nox.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.serverPreferences by preferencesDataStore(name = "servers")

class ServerRepository(context: Context) {
    private val dataStore = context.applicationContext.serverPreferences

    val servers: Flow<List<ServerConfig>> = dataStore.data.map { preferences ->
        decodeServers(preferences[SERVER_LIST_KEY].orEmpty())
    }

    val activeServerId: Flow<String?> = dataStore.data.map { preferences ->
        preferences[ACTIVE_SERVER_ID_KEY]
    }

    suspend fun saveServer(config: ServerConfig) {
        dataStore.edit { preferences ->
            val current = decodeServers(preferences[SERVER_LIST_KEY].orEmpty())
            val updated = current.toMutableList()
            val index = updated.indexOfFirst { it.id == config.id }
            if (index >= 0) updated[index] = config else updated.add(config)
            preferences[SERVER_LIST_KEY] = encodeServers(updated)
        }
    }

    suspend fun removeServer(id: String) {
        dataStore.edit { preferences ->
            val updated = decodeServers(preferences[SERVER_LIST_KEY].orEmpty()).filterNot { it.id == id }
            preferences[SERVER_LIST_KEY] = encodeServers(updated)
        }
    }

    suspend fun setActiveServerId(id: String?) {
        dataStore.edit { preferences ->
            if (id == null) preferences.remove(ACTIVE_SERVER_ID_KEY)
            else preferences[ACTIVE_SERVER_ID_KEY] = id
        }
    }

    private fun decodeServers(value: String): List<ServerConfig> {
        if (value.isBlank()) return emptyList()

        return runCatching {
            val array = JSONArray(value)
            buildList {
                for (index in 0 until array.length()) {
                    runCatching {
                        val item = array.getJSONObject(index)
                        ServerConfig(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            address = item.getString("address"),
                            port = item.getInt("port"),
                            rawUrl = item.optionalString("rawUrl"),
                            security = item.optionalString("security"),
                        )
                    }.getOrNull()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeServers(servers: List<ServerConfig>): String = JSONArray().apply {
        servers.forEach { config ->
            put(JSONObject().apply {
                put("id", config.id)
                put("name", config.name)
                put("address", config.address)
                put("port", config.port)
                put("rawUrl", config.rawUrl ?: JSONObject.NULL)
                put("security", config.security ?: JSONObject.NULL)
            })
        }
    }.toString()

    private fun JSONObject.optionalString(name: String): String? =
        if (isNull(name) || !has(name)) null else optString(name)

    private companion object {
        val SERVER_LIST_KEY = stringPreferencesKey("server_list")
        val ACTIVE_SERVER_ID_KEY = stringPreferencesKey("active_server_id")
    }
}
