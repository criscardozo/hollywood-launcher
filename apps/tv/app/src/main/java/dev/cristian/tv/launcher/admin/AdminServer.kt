package dev.cristian.tv.launcher.admin

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.cristian.tv.launcher.content.Source
import dev.cristian.tv.launcher.jellyfin.JellyfinRepository
import dev.cristian.tv.launcher.jellyfin.Player
import dev.cristian.tv.launcher.net.Http
import dev.cristian.tv.launcher.pins.Pin
import dev.cristian.tv.launcher.pins.PinsRepository
import dev.cristian.tv.launcher.pins.SeerrClient
import dev.cristian.tv.launcher.remote.RemoteKeys
import dev.cristian.tv.launcher.settings.LauncherSettings
import dev.cristian.tv.launcher.youtube.YouTubeRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

const val ADMIN_PORT = 8765
private const val TAG = "AdminServer"

@Serializable private data class GoogleBody(val clientId: String, val clientSecret: String)
@Serializable private data class SeerrBody(val url: String, val apiKey: String)
@Serializable private data class PlayerBody(val player: String)
@Serializable private data class YouTubeCheck(val videos: Int?, val sample: List<String>, val error: String?)
@Serializable private data class KeyBody(val keyCode: Int, val packageName: String)
@Serializable private data class AppInfo(val packageName: String, val label: String)
@Serializable
private data class Status(
    val youtubeConfigured: Boolean,
    val youtubeConnected: Boolean,
    val seerrConfigured: Boolean,
    val seerrUrl: String,
    val jellyfinConnected: Boolean,
    val player: String,
    val pins: Int,
)
@Serializable private data class Error(val error: String)

/**
 * The launcher's settings page and API, on the LAN only (port 8765). The page itself is public; every
 * /api call needs the six-digit code shown on the TV (Configuración del inicio), in the X-Token header.
 * The iPhone app will use the same API later.
 */
class AdminServer(
    private val context: Context,
    private val settings: LauncherSettings,
    private val pins: PinsRepository,
    private val seerr: SeerrClient,
    private val jellyfin: JellyfinRepository,
    private val remoteKeys: RemoteKeys,
    private val youtube: YouTubeRepository,
) : NanoHTTPD(ADMIN_PORT) {

    private val json: Json = Http.json

    fun startQuietly() {
        try {
            start(SOCKET_READ_TIMEOUT, true)
        } catch (e: Exception) {
            Log.w(TAG, "Settings server did not start", e)
        }
    }

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: Exception) {
        Log.w(TAG, "${session.method} ${session.uri} failed", e)
        reply(Response.Status.INTERNAL_ERROR, Error(e.message ?: e.javaClass.simpleName))
    }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri
        if (uri == "/" || uri == "/index.html") {
            val html = context.assets.open("admin/index.html").bufferedReader().use { it.readText() }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        }
        if (!uri.startsWith("/api/")) return reply(Response.Status.NOT_FOUND, Error("No existe"))

        val token = runBlocking { settings.adminToken() }
        if (session.headers["x-token"] != token) return reply(Response.Status.UNAUTHORIZED, Error("Código incorrecto"))

        val method = session.method
        return runBlocking {
            when {
                uri == "/api/status" && method == Method.GET -> reply(status())

                uri == "/api/google" && method == Method.POST -> {
                    val body = json.decodeFromString<GoogleBody>(body(session))
                    settings.setGoogleClient(body.clientId, body.clientSecret)
                    reply(status())
                }

                uri == "/api/seerr" && method == Method.POST -> {
                    val body = json.decodeFromString<SeerrBody>(body(session))
                    settings.setSeerr(body.url, body.apiKey)
                    seerr.search("test") // fails loudly when the URL or key is wrong
                    reply(status())
                }

                uri == "/api/player" && method == Method.POST -> {
                    val body = json.decodeFromString<PlayerBody>(body(session))
                    settings.setPlayer(Player.valueOf(body.player))
                    reply(status())
                }

                uri == "/api/search" && method == Method.GET -> {
                    val query = session.parms["q"].orEmpty()
                    reply(if (query.isBlank()) emptyList() else seerr.search(query))
                }

                uri == "/api/services" && method == Method.GET -> {
                    val id = session.parms["tmdbId"]?.toIntOrNull() ?: return@runBlocking reply(Response.Status.BAD_REQUEST, Error("Falta tmdbId"))
                    val type = session.parms["mediaType"] ?: "movie"
                    reply(seerr.services(id, type).map { it.name })
                }

                uri == "/api/pins" && method == Method.GET -> reply(pins.list())

                uri == "/api/pins" && method == Method.POST -> {
                    val pin = json.decodeFromString<Pin>(body(session))
                    require(pin.source in Source.pinnable) { "Solo Prime Video, Disney+ o Max" }
                    reply(pins.add(pin))
                }

                uri.startsWith("/api/pins/") && method == Method.DELETE -> {
                    pins.remove(uri.removePrefix("/api/pins/"))
                    reply(pins.list())
                }

                uri == "/api/youtube" && method == Method.GET -> {
                    // What the YouTube row would show right now, or why it is empty.
                    val result = runCatching { youtube.latest(force = session.parms["force"] == "1") }
                    reply(YouTubeCheck(
                        videos = result.getOrNull()?.size,
                        sample = result.getOrNull()?.take(3)?.map { it.title }.orEmpty(),
                        error = result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" },
                    ))
                }

                uri == "/api/apps" && method == Method.GET -> reply(apps())

                uri == "/api/keys/recent" && method == Method.GET -> reply(RemoteKeys.recent)

                uri == "/api/keys" && method == Method.GET -> reply(remoteKeys.list())

                uri == "/api/keys" && method == Method.POST -> {
                    val body = json.decodeFromString<KeyBody>(body(session))
                    remoteKeys.set(body.keyCode, body.packageName)
                    reply(remoteKeys.list())
                }

                uri.startsWith("/api/keys/") && method == Method.DELETE -> {
                    uri.removePrefix("/api/keys/").toIntOrNull()?.let { remoteKeys.remove(it) }
                    reply(remoteKeys.list())
                }

                else -> reply(Response.Status.NOT_FOUND, Error("No existe"))
            }
        }
    }

    /** Every app a TV home screen can open, by label. */
    private fun apps(): List<AppInfo> {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private suspend fun status(): Status {
        val s = settings.current()
        return Status(
            youtubeConfigured = s.youtubeConfigured,
            youtubeConnected = s.youtubeConnected,
            seerrConfigured = s.seerrConfigured,
            seerrUrl = s.seerrUrl,
            jellyfinConnected = jellyfin.session.first() != null,
            player = s.player.name,
            pins = pins.list().size,
        )
    }

    private fun body(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"].orEmpty()
    }

    private inline fun <reified T> reply(value: T, status: Response.Status = Response.Status.OK): Response =
        reply(status, json.encodeToJsonElement(value))

    private inline fun <reified T> reply(status: Response.Status, value: T): Response {
        val element: JsonElement = json.encodeToJsonElement(value)
        return newFixedLengthResponse(status, "application/json; charset=utf-8", element.toString())
    }
}
