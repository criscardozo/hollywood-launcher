package dev.cardozo.hollywood.launcher.admin

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.cardozo.hollywood.launcher.content.Source
import dev.cardozo.hollywood.launcher.jellyfin.JellyfinRepository
import dev.cardozo.hollywood.launcher.jellyfin.Player
import dev.cardozo.hollywood.launcher.net.Http
import dev.cardozo.hollywood.launcher.pins.Pin
import dev.cardozo.hollywood.launcher.pins.PinsRepository
import dev.cardozo.hollywood.launcher.pins.SeerrClient
import dev.cardozo.hollywood.launcher.remote.RemoteKeys
import dev.cardozo.hollywood.launcher.settings.LauncherSettings
import dev.cardozo.hollywood.launcher.weather.WeatherRepository
import dev.cardozo.hollywood.launcher.youtube.YouTubeAuth
import dev.cardozo.hollywood.launcher.youtube.YouTubeRepository
import dev.cardozo.hollywood.launcher.youtube.YouTubeSignIn
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.security.MessageDigest

const val ADMIN_PORT = 8765
private const val TAG = "AdminServer"
private const val MAX_FAILURES = 10
private const val LOCKOUT_MS = 5 * 60_000L
private const val MAX_BODY_BYTES = 64 * 1024
/** TMDB's kinds, the only values that may go into a Seerr path. */
private val MEDIA_TYPES = setOf("movie", "tv")

@Serializable private data class GoogleBody(val clientId: String, val clientSecret: String)
@Serializable private data class SeerrBody(val url: String, val apiKey: String)
@Serializable private data class WeatherBody(val name: String, val latitude: Double, val longitude: Double)
@Serializable private data class JellyfinBody(val url: String, val username: String, val password: String)
/** Google's sign-in as the page sees it: [state] is idle, waiting, connected or failed. */
@Serializable private data class YouTubeConnect(val state: String, val url: String? = null, val code: String? = null, val error: String? = null)
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
    val jellyfinUrl: String,
    val player: String,
    val pins: Int,
    val weatherPlace: String,
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
    private val youtubeAuth: YouTubeAuth,
    private val weather: WeatherRepository,
) : NanoHTTPD(ADMIN_PORT) {

    private val json: Json = Http.json

    // Google's device flow outlives the request that starts it: it polls until the code is approved.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w(TAG, "Settings task failed", e) },
    )
    private var youtubeJob: Job? = null
    @Volatile private var youtubeConnect = YouTubeConnect("idle")

    // Six digits are guessable only without a limit: 10 wrong codes within 5 minutes lock the API for 5
    // minutes. A right code does not reset the count (the page polls, which would let guesses through), and
    // only requests that carry a code count: a browser cannot send that header cross-origin, so a web page
    // (an <img> pointing here) cannot lock the user out.
    private val failures = ArrayDeque<Long>()
    @Volatile private var lockedUntilMs = 0L

    fun startQuietly() {
        try {
            start(SOCKET_READ_TIMEOUT, true)
        } catch (e: Exception) {
            Log.w(TAG, "Settings server did not start", e)
        }
    }

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: IllegalArgumentException) {
        // require(...) on what the request sent: a bad request, not a server error.
        reply(Response.Status.BAD_REQUEST, Error(e.message ?: "Pedido inválido"))
    } catch (e: Exception) {
        Log.w(TAG, "${session.method} ${session.uri} failed", e)
        reply(Response.Status.INTERNAL_ERROR, Error(e.message ?: e.javaClass.simpleName))
    }

    /** Counts a wrong code, and locks the API after too many in the window. */
    @Synchronized
    private fun wrongCode() {
        val now = System.currentTimeMillis()
        failures.addLast(now)
        while (failures.isNotEmpty() && now - failures.first() > LOCKOUT_MS) failures.removeFirst()
        if (failures.size >= MAX_FAILURES) {
            failures.clear()
            lockedUntilMs = now + LOCKOUT_MS
            Log.w(TAG, "Settings API locked after $MAX_FAILURES wrong codes")
        }
    }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri
        if (uri == "/" || uri == "/index.html") {
            val html = context.assets.open("admin/index.html").bufferedReader().use { it.readText() }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html).apply {
                // Only this page's own requests and TMDB's posters; never inside someone else's frame.
                addHeader(
                    "Content-Security-Policy",
                    "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'unsafe-inline'; " +
                        "img-src 'self' https://image.tmdb.org data:; connect-src 'self'; frame-ancestors 'none'",
                )
                addHeader("X-Frame-Options", "DENY")
                addHeader("Referrer-Policy", "no-referrer")
            }
        }
        if (!uri.startsWith("/api/")) return reply(Response.Status.NOT_FOUND, Error("No existe"))

        if (System.currentTimeMillis() < lockedUntilMs) {
            return reply(Response.Status.FORBIDDEN, Error("Demasiados intentos: probá de nuevo en unos minutos"))
        }
        val token = runBlocking { settings.adminToken() }
        val given = session.headers["x-token"].orEmpty()
        if (given.isEmpty()) return reply(Response.Status.UNAUTHORIZED, Error("Falta el código"))
        if (!MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) {
            wrongCode()
            return reply(Response.Status.UNAUTHORIZED, Error("Código incorrecto"))
        }

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
                    // Tried first, saved only if it works: a wrong key must not replace a working one.
                    seerr.test(body.url, body.apiKey)
                    settings.setSeerr(body.url, body.apiKey)
                    reply(status())
                }

                uri == "/api/jellyfin/servers" && method == Method.GET -> reply(jellyfin.discoverOnce())

                uri == "/api/jellyfin" && method == Method.POST -> {
                    val body = json.decodeFromString<JellyfinBody>(body(session))
                    val error = jellyfin.signIn(body.url, body.username, body.password)
                    if (error != null) reply(Response.Status.BAD_REQUEST, Error(error)) else reply(status())
                }

                uri == "/api/youtube/connect" && method == Method.POST -> reply(startYouTubeSignIn())

                uri == "/api/youtube/connect" && method == Method.GET -> reply(youtubeConnect)

                uri == "/api/weather/search" && method == Method.GET -> {
                    val query = session.parms["q"].orEmpty()
                    reply(if (query.isBlank()) emptyList() else weather.search(query))
                }

                uri == "/api/weather" && method == Method.POST -> {
                    val body = json.decodeFromString<WeatherBody>(body(session))
                    settings.setWeatherPlace(body.name, body.latitude, body.longitude)
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
                    require(type in MEDIA_TYPES) { "mediaType: movie o tv" }
                    reply(seerr.services(id, type).map { it.name })
                }

                uri == "/api/pins" && method == Method.GET -> reply(pins.list())

                uri == "/api/pins" && method == Method.POST -> {
                    val pin = json.decodeFromString<Pin>(body(session))
                    require(pin.source in Source.pinnable) { "Solo Prime Video, Disney+ o Max" }
                    require(pin.mediaType in MEDIA_TYPES) { "mediaType: movie o tv" }
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

    /** Starts Google's sign-in (dropping one already waiting) and returns once the code is known. */
    @Synchronized
    private fun startYouTubeSignIn(): YouTubeConnect {
        youtubeJob?.cancel()
        youtubeConnect = YouTubeConnect("idle")
        val first = CompletableDeferred<Unit>()
        youtubeJob = scope.launch {
            youtubeAuth.signIn()
                // Anything unexpected ends this sign-in, never the launcher's process.
                .catch { e ->
                    Log.w(TAG, "YouTube sign-in from the page failed", e)
                    emit(YouTubeSignIn.Failed("No se pudo hablar con Google"))
                }
                .collect { step ->
                youtubeConnect = when (step) {
                    is YouTubeSignIn.WaitingForCode -> YouTubeConnect("waiting", url = step.url, code = step.code)
                    YouTubeSignIn.Connected -> YouTubeConnect("connected")
                    is YouTubeSignIn.Failed -> YouTubeConnect("failed", error = step.reason)
                }
                first.complete(Unit)
            }
        }
        runBlocking { withTimeoutOrNull(15_000) { first.await() } }
        return youtubeConnect.takeIf { first.isCompleted } ?: YouTubeConnect("failed", error = "Google no respondió")
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
            jellyfinUrl = jellyfin.session.first()?.baseUrl.orEmpty(),
            player = s.player.name,
            pins = pins.list().size,
            weatherPlace = s.weatherPlace,
        )
    }

    /**
     * The request body as UTF-8. NanoHTTPD's parseBody decodes it as ISO-8859-1 unless the Content-Type
     * names a charset, which fetch() and curl do not, so «Sídney» arrived as «SÃ­dney».
     */
    private fun body(session: IHTTPSession): String {
        val length = session.headers["content-length"]?.toIntOrNull() ?: return ""
        // Settings are a few hundred bytes; a huge Content-Length would otherwise allocate it all.
        require(length in 0..MAX_BODY_BYTES) { "Pedido demasiado grande" }
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = session.inputStream.read(bytes, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(bytes, 0, read, Charsets.UTF_8)
    }

    private inline fun <reified T> reply(value: T, status: Response.Status = Response.Status.OK): Response =
        reply(status, json.encodeToJsonElement(value))

    private inline fun <reified T> reply(status: Response.Status, value: T): Response {
        val element: JsonElement = json.encodeToJsonElement(value)
        return newFixedLengthResponse(status, "application/json; charset=utf-8", element.toString())
    }
}
