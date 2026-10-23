package dev.cristian.tv.launcher.jellyfin

import android.content.Context
import android.util.Log
import dev.cristian.tv.launcher.content.MediaItem
import dev.cristian.tv.launcher.content.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.QuickConnectDto
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.util.UUID

data class DiscoveredServer(val name: String, val address: String)

sealed interface QuickConnectState {
    data class WaitingForApproval(val code: String) : QuickConnectState
    data object Connected : QuickConnectState
    data class Failed(val reason: String) : QuickConnectState
}

/** What the home screen shows from Jellyfin. */
data class JellyfinHome(
    /** In progress first, then next up episodes. */
    val continueWatching: List<MediaItem>,
    val recentlyAdded: List<MediaItem>,
    /** Backdrops from the library, used as the wallpaper. */
    val wallpapers: List<String>,
)

private const val TAG = "Jellyfin"
private const val TICKS_PER_MS = 10_000L
private val CARD_FIELDS = listOf(ItemFields.OVERVIEW, ItemFields.GENRES)

class JellyfinRepository(context: Context, private val store: JellyfinSessionStore) {

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
        JellyfinHome(
            continueWatching = (resume + upNext).map { api.toMediaItem(it) },
            recentlyAdded = latest.map { api.toMediaItem(it) },
            wallpapers = api.randomBackdrops(userId, 20),
        )
    }

    /** Random backdrops from the library, for the wallpaper and the screensaver. Empty when not connected. */
    suspend fun wallpapers(limit: Int = 30): List<String> = withContext(Dispatchers.IO) {
        val session = store.session.first() ?: return@withContext emptyList()
        val api = jellyfin.createApi(baseUrl = session.baseUrl, accessToken = session.accessToken)
        api.randomBackdrops(UUID.fromString(session.userId), limit)
    }
}

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
