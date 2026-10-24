package dev.cardozo.hollywood.launcher.youtube

import android.util.Log
import dev.cardozo.hollywood.launcher.net.Http
import dev.cardozo.hollywood.launcher.net.HttpException
import dev.cardozo.hollywood.launcher.settings.LauncherSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.Request
import java.io.IOException

sealed interface YouTubeSignIn {
    /** Shown on the TV: open [url] on the phone and type [code]. */
    data class WaitingForCode(val url: String, val code: String) : YouTubeSignIn
    data object Connected : YouTubeSignIn
    data class Failed(val reason: String) : YouTubeSignIn
}

private const val DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
private const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
private const val TAG = "YouTubeAuth"
private const val SCOPE = "https://www.googleapis.com/auth/youtube.readonly"

@Serializable
private data class DeviceCode(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_url") val verificationUrl: String,
    @SerialName("expires_in") val expiresIn: Int,
    val interval: Int = 5,
)

@Serializable
private data class Token(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("refresh_token") val refreshToken: String? = null,
)

@Serializable
private data class TokenError(val error: String)

/**
 * Google's OAuth flow for TVs: the TV shows a code, the phone approves it at google.com/device. The
 * client ID and secret come from a "TVs and Limited Input devices" client in Cristian's Google Cloud
 * project, typed into the settings page; only the refresh token is kept afterwards.
 */
class YouTubeAuth(private val settings: LauncherSettings) {

    // Home, the settings page and the row can all ask at once: one refresh at a time.
    private val refreshing = Mutex()
    @Volatile private var accessToken: String? = null
    @Volatile private var expiresAtMs = 0L

    fun signIn(): Flow<YouTubeSignIn> = flow {
        val s = settings.current()
        if (!s.youtubeConfigured) {
            emit(YouTubeSignIn.Failed("Falta el client ID de Google: cargalo en la página de configuración"))
            return@flow
        }
        val device = try {
            post(DEVICE_CODE_URL, "client_id" to s.googleClientId, "scope" to SCOPE).let {
                Http.json.decodeFromString<DeviceCode>(it)
            }
        } catch (e: HttpException) {
            emit(YouTubeSignIn.Failed("Google rechazó el client ID (${e.code})"))
            return@flow
        } catch (e: IOException) {
            emit(YouTubeSignIn.Failed("No se pudo hablar con Google"))
            return@flow
        }
        emit(YouTubeSignIn.WaitingForCode(device.verificationUrl, device.userCode))

        var waitSeconds = device.interval
        val deadline = System.currentTimeMillis() + device.expiresIn * 1_000L
        while (System.currentTimeMillis() < deadline) {
            delay(waitSeconds * 1_000L)
            val result = try {
                post(
                    TOKEN_URL,
                    "client_id" to s.googleClientId,
                    "client_secret" to s.googleClientSecret,
                    "device_code" to device.deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                )
            } catch (e: HttpException) {
                when (runCatching { Http.json.decodeFromString<TokenError>(e.body).error }.getOrNull()) {
                    "authorization_pending" -> continue
                    "slow_down" -> { waitSeconds += 5; continue }
                    "access_denied" -> { emit(YouTubeSignIn.Failed("Se rechazó el acceso")); return@flow }
                    else -> { emit(YouTubeSignIn.Failed("Error de Google (${e.code})")); return@flow }
                }
            } catch (e: IOException) {
                // A dropped connection or a timeout mid-wait: ask again on the next round.
                continue
            }
            val token = Http.json.decodeFromString<Token>(result)
            accessToken = token.accessToken
            expiresAtMs = System.currentTimeMillis() + token.expiresIn * 1_000L
            settings.setYoutubeRefreshToken(token.refreshToken.orEmpty())
            emit(YouTubeSignIn.Connected)
            return@flow
        }
        emit(YouTubeSignIn.Failed("El código venció"))
    }

    /**
     * Disconnecting on purpose: the token is revoked at Google (best effort) and the saved channels go too,
     * so the row does not carry on over RSS. Expiry, unlike this, keeps the channels.
     */
    suspend fun signOut() {
        val refreshToken = settings.current().youtubeRefreshToken
        accessToken = null
        settings.setYoutubeRefreshToken("")
        settings.setYoutubeChannels(emptyList())
        if (refreshToken.isNotBlank()) {
            runCatching { post(REVOKE_URL, "token" to refreshToken) }
                .onFailure { Log.i(TAG, "Revoking the YouTube token failed; it is forgotten here anyway", it) }
        }
    }

    /** A valid access token, refreshed when it is about to expire; null when not signed in. */
    suspend fun accessToken(): String? = refreshing.withLock {
        val s = settings.current()
        if (!s.youtubeConnected) return null
        accessToken?.let { if (System.currentTimeMillis() < expiresAtMs - 60_000) return it }
        val token = try {
            Http.json.decodeFromString<Token>(
                post(
                    TOKEN_URL,
                    "client_id" to s.googleClientId,
                    "client_secret" to s.googleClientSecret,
                    "refresh_token" to s.youtubeRefreshToken,
                    "grant_type" to "refresh_token",
                ),
            )
        } catch (e: HttpException) {
            // invalid_grant: the user revoked access, or the token expired. Sign in again.
            if (e.code == 400 || e.code == 401) settings.setYoutubeRefreshToken("")
            throw e
        }
        accessToken = token.accessToken
        expiresAtMs = System.currentTimeMillis() + token.expiresIn * 1_000L
        token.accessToken
    }

    private suspend fun post(url: String, vararg fields: Pair<String, String>): String {
        val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return Http.get(Request.Builder().url(url).post(body).build())
    }
}
