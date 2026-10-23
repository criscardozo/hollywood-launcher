package dev.cardozo.hollywood.launcher.jellyfin

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class JellyfinSession(val baseUrl: String, val accessToken: String, val userId: String)

private val Context.dataStore by preferencesDataStore(name = "jellyfin")

class JellyfinSessionStore(private val context: Context) {

    private val baseUrl = stringPreferencesKey("base_url")
    private val accessToken = stringPreferencesKey("access_token")
    private val userId = stringPreferencesKey("user_id")

    val session: Flow<JellyfinSession?> = context.dataStore.data.map { prefs ->
        val url = prefs[baseUrl] ?: return@map null
        val token = prefs[accessToken] ?: return@map null
        val user = prefs[userId] ?: return@map null
        JellyfinSession(url, token, user)
    }

    suspend fun save(session: JellyfinSession) {
        context.dataStore.edit {
            it[baseUrl] = session.baseUrl
            it[accessToken] = session.accessToken
            it[userId] = session.userId
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
