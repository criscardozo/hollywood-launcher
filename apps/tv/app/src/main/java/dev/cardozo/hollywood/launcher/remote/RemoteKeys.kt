package dev.cristian.tv.launcher.remote

import android.content.Context
import android.view.KeyEvent
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.cristian.tv.launcher.net.Http
import dev.cristian.tv.launcher.system.start
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable
data class PressedKey(val keyCode: Int, val name: String, val atMs: Long)

@Serializable
data class KeyMapping(val keyCode: Int, val name: String, val packageName: String)

private val Context.keysStore by preferencesDataStore(name = "remote_keys")
private val MAPPING = stringPreferencesKey("mapping")

/** The keycode → app mapping, and the last keys pressed (to learn what a button sends). */
class RemoteKeys(private val context: Context) {

    val mapping: Flow<Map<Int, String>> =
        context.keysStore.data.map { prefs -> decode(prefs[MAPPING]).associate { it.keyCode to it.packageName } }

    suspend fun list(): List<KeyMapping> = decode(context.keysStore.data.first()[MAPPING])

    suspend fun set(keyCode: Int, packageName: String) {
        val updated = list().filterNot { it.keyCode == keyCode } + KeyMapping(keyCode, keyName(keyCode), packageName)
        context.keysStore.edit { it[MAPPING] = Http.json.encodeToString(updated) }
    }

    suspend fun remove(keyCode: Int) {
        context.keysStore.edit { it[MAPPING] = Http.json.encodeToString(list().filterNot { k -> k.keyCode == keyCode }) }
    }

    fun open(packageName: String) {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName) ?: return
        context.start(intent)
    }

    private fun decode(json: String?): List<KeyMapping> =
        json?.let { runCatching { Http.json.decodeFromString<List<KeyMapping>>(it) }.getOrNull() }.orEmpty()

    companion object {
        private val recentKeys = ArrayDeque<PressedKey>()

        /** Only while the accessibility service is enabled; newest first. */
        val recent: List<PressedKey> get() = synchronized(recentKeys) { recentKeys.toList() }

        fun record(event: KeyEvent) = synchronized(recentKeys) {
            recentKeys.addFirst(PressedKey(event.keyCode, keyName(event.keyCode), System.currentTimeMillis()))
            while (recentKeys.size > 8) recentKeys.removeLast()
        }

        fun keyName(keyCode: Int): String = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
    }
}
