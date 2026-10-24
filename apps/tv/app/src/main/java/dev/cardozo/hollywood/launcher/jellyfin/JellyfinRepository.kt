package dev.cardozo.hollywood.launcher.jellyfin

import android.content.Context
import android.util.Log
import dev.cardozo.hollywood.launcher.content.MediaItem
import dev.cardozo.hollywood.launcher.content.Source
import dev.cardozo.hollywood.launcher.details.Action
import dev.cardozo.hollywood.launcher.details.Details
import dev.cardozo.hollywood.launcher.details.Person
import dev.cardozo.hollywood.launcher.details.Season
import dev.cardozo.hollywood.launcher.details.trailerAction
import dev.cardozo.hollywood.launcher.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PersonKind
import org.jellyfin.sdk.model.api.QuickConnectDto
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

@Serializable
data class DiscoveredServer(val name: String, val address: String)

sealed interface QuickConnectState {
    data class WaitingForApproval(val code: String) : QuickConnectState
    data object Connected : QuickConnectState
    data class Failed(val reason: String) : QuickConnectState
}

/** One film of the screensaver: its backdrop full screen, the poster and the text over it. */
@Serializable
data class ScreensaverSlide(val title: String, val meta: String, val overview: String, val backdropUrl: String, val posterUrl: String)

/** What the home screen shows from Jellyfin. Kept on disk too, to show while the server does not answer. */
@Serializable
data class JellyfinHome(
    /** In progress first, then next up episodes. */
    val continueWatching: List<MediaItem>,
    val recentlyAdded: List<MediaItem>,
    /** Backdrops from the library, used as the wallpaper. */
    val wallpapers: List<String>,
    /** The server's own name («Ginebra»), for the notice when it does not answer. */
    val serverName: String? = null,
)

private const val TAG = "Jellyfin"
private const val TICKS_PER_MS = 10_000L
private val CARD_FIELDS = listOf(ItemFields.OVERVIEW, ItemFields.GENRES)

class JellyfinRepository(context: Context, private val store: JellyfinSessionStore) {

    // The last good answers, on disk: when the server is off (Ginebra shut down), Home and the screensaver
    // show these instead of emptying. The images themselves come from Coil's disk cache.
    private val homeCache = File(context.filesDir, "jellyfin-home.json")
    private val slidesCache = File(context.filesDir, "screensaver-slides.json")

    private val jellyfin = createJellyfin {
        clientInfo = ClientInfo(name = "Hollywood", version = "0.1.0")
        this.context = context.applicationContext
    }

    val session: Flow<JellyfinSession?> = store.session

    fun discover(): Flow<DiscoveredServer> = flow {
        jellyfin.discovery.discoverLocalServers().collect { info ->
            val address = info.address ?: return@collect
            emit(DiscoveredServer(info.name ?: address, address))
        }
    }

    /** Servers that answer the LAN broadcast within [timeoutMs], for the settings page to offer. */
    suspend fun discoverOnce(timeoutMs: Long = 3_000): List<DiscoveredServer> {
        val found = mutableListOf<DiscoveredServer>()
        withTimeoutOrNull(timeoutMs) { discover().collect { found += it } }
        return found.distinctBy { it.address }
    }

    /**
     * Sign in with user and password, from the settings page (typing them with the remote is what Quick
     * Connect avoids). Only the access token is kept. Returns null on success, else why it failed.
     */
    suspend fun signIn(baseUrl: String, username: String, password: String): String? = withContext(Dispatchers.IO) {
        val url = baseUrl.trim().trimEnd('/')
        val auth = try {
            jellyfin.createApi(baseUrl = url).authenticationApi
                .authenticateUserByName(AuthenticateUserByName(username = username.trim(), pw = password)).content
        } catch (e: InvalidStatusException) {
            return@withContext if (e.status == 401) "Usuario o contraseña incorrectos" else "El servidor respondió ${e.status}"
        } catch (e: Exception) {
            Log.w(TAG, "Sign-in on $url failed", e)
            return@withContext "No se pudo hablar con $url"
        }
        val token = auth.accessToken
        val userId = auth.user?.id ?: return@withContext "El servidor no devolvió un usuario"
        if (token == null) return@withContext "El servidor no devolvió un usuario"
        store.save(JellyfinSession(url, token, userId.toString()))
        null
    }

    /**
     * Quick Connect: show a code on the TV, approve it from the phone or the web, keep the token.
     * No password is ever typed on the TV.
     */
    fun quickConnect(baseUrl: String): Flow<QuickConnectState> = flow {
        val api = jellyfin.createApi(baseUrl = baseUrl)
        // A code expires on the server after a few minutes; when it does, start over with a new one.
        while (true) {
            val started = try {
                api.authenticationApi.initiateQuickConnect().content
            } catch (e: Exception) {
                Log.w(TAG, "Quick Connect could not start on $baseUrl", e)
                emit(QuickConnectState.Failed("No se pudo hablar con $baseUrl ($e)"))
                return@flow
            }
            emit(QuickConnectState.WaitingForApproval(started.code))
            val approved = try {
                var done = false
                while (!done) {
                    delay(2_000)
                    done = api.authenticationApi.getQuickConnectState(started.secret).content.authenticated
                }
                true
            } catch (e: Exception) {
                Log.i(TAG, "Quick Connect code expired, asking for a new one", e)
                false
            }
            if (!approved) continue

            val auth = try {
                api.authenticationApi.authenticateWithQuickConnect(QuickConnectDto(secret = started.secret)).content
            } catch (e: Exception) {
                Log.w(TAG, "Quick Connect approved but authentication failed", e)
                emit(QuickConnectState.Failed("Aprobado, pero el servidor no dio acceso ($e)"))
                return@flow
            }
            val token = auth.accessToken
            val userId = auth.user?.id
            if (token == null || userId == null) {
                emit(QuickConnectState.Failed("El servidor no devolvió un usuario"))
                return@flow
            }
            store.save(JellyfinSession(baseUrl, token, userId.toString()))
            emit(QuickConnectState.Connected)
            return@flow
        }
    }

    suspend fun disconnect() = store.clear()

    /** The details page of a film or a series. An episode opens its series, continuing from that episode. */
    suspend fun details(itemId: String): Details? = withContext(Dispatchers.IO) {
        val session = store.session.first() ?: return@withContext null
        val api = jellyfin.createApi(baseUrl = session.baseUrl, accessToken = session.accessToken)
        val userId = UUID.fromString(session.userId)
        val opened = api.libraryApi.getItem(UUID.fromString(itemId), userId).content
        val episode = opened.takeIf { it.type == BaseItemKind.EPISODE }
        val item = episode?.seriesId?.let { api.libraryApi.getItem(it, userId).content } ?: opened
        val server = serverName ?: runCatching { api.systemApi.getPublicSystemInfo().content.serverName }.getOrNull()
            .also { serverName = it }
        if (item.type == BaseItemKind.SERIES) api.seriesDetails(userId, item, episode, server) else api.movieDetails(item, server)
    }

    /** A season's episodes as cards: «E3 · Name», with the runtime underneath. */
    suspend fun episodes(seriesId: String, seasonId: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val session = store.session.first() ?: return@withContext emptyList()
        val api = jellyfin.createApi(baseUrl = session.baseUrl, accessToken = session.accessToken)
        api.showApi.getEpisodes(
            seriesId = UUID.fromString(seriesId),
            userId = UUID.fromString(session.userId),
            seasonId = UUID.fromString(seasonId),
            fields = CARD_FIELDS,
        ).content.items.map { episode ->
            val runtime = episode.runTimeTicks?.let { formatMinutes(it / TICKS_PER_MS / 60_000) }
            val watched = if (episode.userData?.played == true) "vista" else null
            api.toMediaItem(episode).copy(
                title = listOfNotNull(episode.indexNumber?.let { "E$it" }, episode.name).joinToString(" · "),
                subtitle = listOfNotNull(runtime, watched).joinToString(" · ").ifBlank { null },
            )
        }
    }

    @Volatile private var serverName: String? = null

    suspend fun loadHome(): JellyfinHome? = withContext(Dispatchers.IO) {
        val session = store.session.first() ?: return@withContext null
        val api = jellyfin.createApi(baseUrl = session.baseUrl, accessToken = session.accessToken)
        val userId = UUID.fromString(session.userId)

        val resume = api.libraryApi.getResumeItems(
            userId = userId,
            limit = 12,
            mediaTypes = listOf(MediaType.VIDEO),
            fields = CARD_FIELDS,
        ).content.items
        val nextUp = api.showApi.getNextUp(
            userId = userId,
            limit = 12,
            fields = CARD_FIELDS,
            enableResumable = false,
        ).content.items
        val latest = api.libraryApi.getLatestMedia(
            userId = userId,
            limit = 20,
            includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
            fields = CARD_FIELDS,
        ).content

        val seen = resume.map { it.seriesId ?: it.id }.toMutableSet()
        val upNext = nextUp.filter { seen.add(it.seriesId ?: it.id) }
        val server = serverName ?: runCatching { api.systemApi.getPublicSystemInfo().content.serverName }.getOrNull()
            .also { serverName = it }
        JellyfinHome(
            continueWatching = (resume + upNext).map { api.toMediaItem(it) },
            recentlyAdded = latest.map { api.toMediaItem(it) },
            wallpapers = api.randomBackdrops(userId, 20),
            serverName = server,
        ).also { save(homeCache, it) }
    }

    /** Home as it was last loaded, if it ever was. */
    suspend fun cachedHome(): JellyfinHome? = read(homeCache)

    private suspend inline fun <reified T> save(file: File, value: T) = withContext(Dispatchers.IO) {
        runCatching { file.writeText(Http.json.encodeToString(value)) }
            .onFailure { Log.w(TAG, "Could not write ${file.name}", it) }
    }

    private suspend inline fun <reified T> read(file: File): T? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        runCatching { Http.json.decodeFromString<T>(file.readText()) }.getOrNull()
    }

    /** Random films with a backdrop, a poster and an overview, for the screensaver. Empty when not connected. */
    suspend fun screensaverSlides(limit: Int = 40): List<ScreensaverSlide> = withContext(Dispatchers.IO) {
        val session = store.session.first() ?: return@withContext emptyList()
        val api = jellyfin.createApi(baseUrl = session.baseUrl, accessToken = session.accessToken)
        val items = try {
            api.libraryApi.getItems(
                GetItemsRequest(
                    userId = UUID.fromString(session.userId),
                    recursive = true,
                    includeItemTypes = listOf(BaseItemKind.MOVIE),
                    imageTypes = listOf(ImageType.BACKDROP),
                    fields = CARD_FIELDS,
                    sortBy = listOf(ItemSortBy.RANDOM),
                    limit = limit,
                ),
            ).content.items
        } catch (e: Exception) {
            Log.i(TAG, "Screensaver from the cache: the server did not answer", e)
            return@withContext read<List<ScreensaverSlide>>(slidesCache).orEmpty().shuffled()
        }
        items.mapNotNull { item ->
            val overview = item.overview?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val backdrop = api.backdropUrl(item) ?: return@mapNotNull null
            val poster = api.posterUrl(item) ?: return@mapNotNull null
            val meta = listOfNotNull(
                item.productionYear?.toString(),
                item.genres?.firstOrNull(),
                item.runTimeTicks?.let { formatMinutes(it / TICKS_PER_MS / 60_000) },
                item.communityRating?.let { "★ ${formatScore(it)}" },
            ).joinToString(" · ")
            ScreensaverSlide(item.name.orEmpty(), meta, overview, backdrop, poster)
        }.also { if (it.isNotEmpty()) save(slidesCache, it) }
    }

    /** Random backdrops from the library, for the wallpaper and the screensaver. Empty when not connected. */
    suspend fun wallpapers(limit: Int = 30): List<String> = withContext(Dispatchers.IO) {
        val session = store.session.first() ?: return@withContext emptyList()
        val api = jellyfin.createApi(baseUrl = session.baseUrl, accessToken = session.accessToken)
        try {
            api.randomBackdrops(UUID.fromString(session.userId), limit)
        } catch (e: Exception) {
            Log.i(TAG, "Wallpapers from the cache: the server did not answer", e)
            cachedHome()?.wallpapers.orEmpty()
        }
    }
}

private fun ApiClient.movieDetails(item: BaseItemDto, server: String?): Details {
    val media = toMediaItem(item)
    val runtimeMs = item.runTimeTicks?.let { it / TICKS_PER_MS }
    val endsAt = runtimeMs?.let { LocalTime.now().plusSeconds((it - media.positionMs) / 1_000).format(CLOCK) }
    val actions = if (media.positionMs > 0) {
        listOf(
            Action("Continuar", formatMinutes(media.positionMs / 60_000), media),
            Action("Desde el principio", item = media.copy(positionMs = 0), icon = "↺"),
        )
    } else {
        listOf(Action("Reproducir", item = media))
    } + listOfNotNull(trailer(item))
    return Details(
        source = Source.JELLYFIN,
        sourceName = server,
        title = item.name.orEmpty(),
        facts = listOfNotNull(item.productionYear?.toString(), runtimeMs?.let { formatMinutes(it / 60_000) }),
        rating = item.officialRating,
        score = item.communityRating?.let(::formatScore),
        note = endsAt?.let { "termina a las $it" },
        genres = item.genres.orEmpty(),
        overview = item.overview,
        backdropUrl = backdropUrl(item),
        progress = media.progress,
        remaining = media.remaining,
        actions = actions,
        cast = cast(item),
        tmdbId = item.providerIds?.get("Tmdb")?.toIntOrNull(),
        tmdbType = "movie",
    )
}

private suspend fun ApiClient.seriesDetails(userId: UUID, series: BaseItemDto, opened: BaseItemDto?, server: String?): Details {
    // The episode the card was for, else the next one up (the first episode of a series not started yet).
    val next = opened ?: showApi.getNextUp(
        userId = userId,
        seriesId = series.id,
        limit = 1,
        fields = CARD_FIELDS,
        enableResumable = true,
    ).content.items.firstOrNull()
    val seasons = showApi.getSeasons(seriesId = series.id, userId = userId).content.items
        // The server names seasons in its own language («Season 1»): number them in Spanish instead.
        .map { Season(it.id.toString(), it.indexNumber?.let { n -> if (n == 0) "Especiales" else "Temporada $n" } ?: it.name.orEmpty()) }
    val nextItem = next?.let { toMediaItem(it) }
    val label = next?.let { listOfNotNull(it.parentIndexNumber?.let { n -> "T$n" }, it.indexNumber?.let { n -> "E$n" }).joinToString(" · ") }
    val started = (nextItem?.positionMs ?: 0) > 0
    val actions = when {
        nextItem == null -> emptyList()
        started -> listOf(
            Action("Continuar", label, nextItem),
            Action("Desde el principio", label, nextItem.copy(positionMs = 0), icon = "↺"),
        )
        else -> listOf(Action("Reproducir", label, nextItem))
    } + listOfNotNull(trailer(series))
    return Details(
        source = Source.JELLYFIN,
        sourceName = server,
        title = series.name.orEmpty(),
        facts = listOfNotNull(
            series.productionYear?.toString(),
            seasons.size.takeIf { it > 0 }?.let { if (it == 1) "1 temporada" else "$it temporadas" },
        ),
        rating = series.officialRating,
        score = series.communityRating?.let(::formatScore),
        note = next?.let { listOfNotNull(label, it.name?.let { name -> "«$name»" }).joinToString(" ") },
        genres = series.genres.orEmpty(),
        overview = series.overview,
        backdropUrl = backdropUrl(series),
        progress = nextItem?.progress,
        remaining = nextItem?.remaining,
        actions = actions,
        cast = cast(series),
        seasons = seasons,
        seasonId = (next?.seasonId?.toString() ?: seasons.firstOrNull()?.id),
        seriesId = series.id.toString(),
        tmdbId = series.providerIds?.get("Tmdb")?.toIntOrNull(),
        tmdbType = "tv",
    )
}

/** The first YouTube trailer Jellyfin's metadata lists for the title (about two thirds of the library). */
private fun trailer(item: BaseItemDto): Action? =
    item.remoteTrailers.orEmpty().mapNotNull { it.url }.firstOrNull { "youtube.com" in it || "youtu.be" in it }
        ?.let(::trailerAction)

private fun ApiClient.cast(item: BaseItemDto): List<Person> =
    item.people.orEmpty().filter { it.type == PersonKind.ACTOR }.take(12).map { person ->
        Person(
            name = person.name.orEmpty(),
            role = person.role?.takeIf { it.isNotBlank() },
            imageUrl = person.primaryImageTag?.let { image(person.id, ImageType.PRIMARY, it, 240) },
        )
    }

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

private fun formatScore(score: Float): String = String.format(Locale.forLanguageTag("es"), "%.1f", score)

private suspend fun ApiClient.randomBackdrops(userId: UUID, limit: Int): List<String> =
    libraryApi.getItems(
        GetItemsRequest(
            userId = userId,
            recursive = true,
            includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
            imageTypes = listOf(ImageType.BACKDROP),
            sortBy = listOf(ItemSortBy.RANDOM),
            limit = limit,
        ),
    ).content.items.mapNotNull { backdropUrl(it) }

private fun ApiClient.toMediaItem(item: BaseItemDto): MediaItem {
    val isEpisode = item.type == BaseItemKind.EPISODE
    val title = if (isEpisode) item.seriesName ?: item.name.orEmpty() else item.name.orEmpty()
    val episode = if (isEpisode) {
        val number = listOfNotNull(
            item.parentIndexNumber?.let { "T$it" },
            item.indexNumber?.let { "E$it" },
        ).joinToString(" · ")
        listOf(number, item.name?.let { "«$it»" }).filter { !it.isNullOrBlank() }.joinToString(" ")
    } else null
    val runtimeMin = item.runTimeTicks?.let { it / TICKS_PER_MS / 60_000 }
    val meta = listOfNotNull(
        item.productionYear?.toString(),
        item.genres?.firstOrNull(),
        runtimeMin?.let(::formatMinutes),
        item.officialRating,
    ).joinToString(" · ")
    val positionTicks = item.userData?.playbackPositionTicks ?: 0L
    val played = item.userData?.playedPercentage?.toFloat()?.div(100f)
    val remainingMin = item.runTimeTicks?.let { (it - positionTicks) / TICKS_PER_MS / 60_000 }
    return MediaItem(
        id = item.id.toString(),
        source = Source.JELLYFIN,
        title = title,
        subtitle = episode ?: item.productionYear?.toString(),
        meta = meta,
        overview = item.overview,
        cardImageUrl = cardImageUrl(item),
        posterUrl = posterUrl(item),
        backdropUrl = backdropUrl(item),
        progress = played?.takeIf { it > 0f },
        remaining = if (positionTicks > 0 && remainingMin != null) "Quedan ${formatMinutes(remainingMin)}" else null,
        positionMs = positionTicks / TICKS_PER_MS,
    )
}

private fun formatMinutes(min: Long): String = if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"

/** 16:9 card: episode screenshot, else the item's thumb or backdrop, else the series'. */
private fun ApiClient.cardImageUrl(item: BaseItemDto): String? {
    val tags = item.imageTags.orEmpty()
    return when {
        item.type == BaseItemKind.EPISODE && ImageType.PRIMARY in tags ->
            image(item.id, ImageType.PRIMARY, tags[ImageType.PRIMARY], 640)
        ImageType.THUMB in tags -> image(item.id, ImageType.THUMB, tags[ImageType.THUMB], 640)
        !item.backdropImageTags.isNullOrEmpty() ->
            image(item.id, ImageType.BACKDROP, item.backdropImageTags!!.first(), 640)
        item.parentThumbItemId != null ->
            image(item.parentThumbItemId!!, ImageType.THUMB, item.parentThumbImageTag, 640)
        item.parentBackdropItemId != null ->
            image(item.parentBackdropItemId!!, ImageType.BACKDROP, item.parentBackdropImageTags?.firstOrNull(), 640)
        else -> null
    }
}

private fun ApiClient.posterUrl(item: BaseItemDto): String? {
    val tag = item.imageTags?.get(ImageType.PRIMARY) ?: return null
    return image(item.id, ImageType.PRIMARY, tag, 360)
}

private fun ApiClient.backdropUrl(item: BaseItemDto): String? = when {
    !item.backdropImageTags.isNullOrEmpty() -> image(item.id, ImageType.BACKDROP, item.backdropImageTags!!.first(), 1280)
    item.parentBackdropItemId != null ->
        image(item.parentBackdropItemId!!, ImageType.BACKDROP, item.parentBackdropImageTags?.firstOrNull(), 1280)
    else -> null
}

private fun ApiClient.image(id: UUID, type: ImageType, tag: String?, maxWidth: Int): String =
    imageApi.getItemImageUrl(itemId = id, imageType = type, tag = tag, maxWidth = maxWidth, quality = 85)
