package dev.cardozo.hollywood.launcher.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.cardozo.hollywood.launcher.jellyfin.Player
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom

/**
 * Everything the launcher is told from outside: credentials typed into the settings page on the LAN,
 * the YouTube sign-in, the pinned items. None of it lives in the code or in git.
 */
data class Settings(
    val googleClientId: String = "",
    val googleClientSecret: String = "",
    val youtubeRefreshToken: String = "",
    /** Channel IDs from the last subscription sync: the row keeps working (via RSS) if the sign-in expires. */
    val youtubeChannels: List<String> = emptyList(),
    val seerrUrl: String = "",
    val seerrApiKey: String = "",
    val adminToken: String = "",
    val pinsJson: String = "[]",
    val player: Player = Player.WHOLPHIN,
) {
    val youtubeConfigured get() = googleClientId.isNotBlank() && googleClientSecret.isNotBlank()
    val youtubeConnected get() = youtubeConfigured && youtubeRefreshToken.isNotBlank()
    val seerrConfigured get() = seerrUrl.isNotBlank() && seerrApiKey.isNotBlank()
}

private val Context.dataStore by preferencesDataStore(name = "launcher")

class LauncherSettings(private val context: Context) {

    private object Keys {
        val googleClientId = stringPreferencesKey("google_client_id")
        val googleClientSecret = stringPreferencesKey("google_client_secret")
        val youtubeRefreshToken = stringPreferencesKey("youtube_refresh_token")
        val youtubeChannels = stringPreferencesKey("youtube_channels")
        val seerrUrl = stringPreferencesKey("seerr_url")
        val seerrApiKey = stringPreferencesKey("seerr_api_key")
        val adminToken = stringPreferencesKey("admin_token")
        val pins = stringPreferencesKey("pins")
        val player = stringPreferencesKey("player")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): Settings = settings.first()

    /** The code the settings page asks for, shown on the TV. Created once. */
    suspend fun adminToken(): String {
        current().adminToken.takeIf { it.isNotBlank() }?.let { return it }
        // A CSPRNG, not kotlin.random: the code guards the settings API on the LAN (the server rate-limits guesses).
        val token = (SecureRandom().nextInt(900_000) + 100_000).toString()
        context.dataStore.edit { it[Keys.adminToken] = token }
        return token
    }

    suspend fun setGoogleClient(id: String, secret: String) = context.dataStore.edit {
        it[Keys.googleClientId] = id.trim()
        it[Keys.googleClientSecret] = secret.trim()
    }

    suspend fun setYoutubeRefreshToken(token: String) = context.dataStore.edit {
        if (token.isBlank()) it.remove(Keys.youtubeRefreshToken) else it[Keys.youtubeRefreshToken] = token
    }

    suspend fun setYoutubeChannels(ids: List<String>) = context.dataStore.edit {
        it[Keys.youtubeChannels] = ids.joinToString(",")
    }

    suspend fun setSeerr(url: String, apiKey: String) = context.dataStore.edit {
        it[Keys.seerrUrl] = url.trim().trimEnd('/')
        it[Keys.seerrApiKey] = apiKey.trim()
    }

    suspend fun setPinsJson(json: String) = context.dataStore.edit { it[Keys.pins] = json }

    suspend fun setPlayer(player: Player) = context.dataStore.edit { it[Keys.player] = player.name }

    private fun Preferences.toSettings() = Settings(
        googleClientId = this[Keys.googleClientId].orEmpty(),
        googleClientSecret = this[Keys.googleClientSecret].orEmpty(),
        youtubeRefreshToken = this[Keys.youtubeRefreshToken].orEmpty(),
        youtubeChannels = this[Keys.youtubeChannels].orEmpty().split(',').filter { it.isNotBlank() },
        seerrUrl = this[Keys.seerrUrl].orEmpty(),
        seerrApiKey = this[Keys.seerrApiKey].orEmpty(),
        adminToken = this[Keys.adminToken].orEmpty(),
        pinsJson = this[Keys.pins] ?: "[]",
        player = this[Keys.player]?.let { name -> Player.entries.firstOrNull { it.name == name } } ?: Player.WHOLPHIN,
    )
}
