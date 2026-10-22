package dev.cristian.tv.launcher.pins

import dev.cristian.tv.launcher.content.MediaItem
import dev.cristian.tv.launcher.content.Source
import dev.cristian.tv.launcher.net.Http
import dev.cristian.tv.launcher.settings.LauncherSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A show or film on a service with no API (Prime Video, Disney+, Max), pinned by hand. Metadata and
 * images come from TMDB through Seerr; [link] is what the service's share button gives
 * (e.g. https://app.primevideo.com/detail?gti=…). Without a link, OK just opens the app.
 */
@Serializable
data class Pin(
    val id: String = UUID.randomUUID().toString(),
    val tmdbId: Int,
    val mediaType: String,
    val title: String,
    val year: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val source: Source,
    val link: String? = null,
)

private const val TMDB_IMAGES = "https://image.tmdb.org/t/p"

class PinsRepository(private val settings: LauncherSettings) {

    val pins: Flow<List<Pin>> = settings.settings.map { decode(it.pinsJson) }

    suspend fun list(): List<Pin> = decode(settings.current().pinsJson)

    suspend fun add(pin: Pin): Pin {
        save(list().filterNot { it.tmdbId == pin.tmdbId && it.source == pin.source } + pin)
        return pin
    }

    suspend fun remove(id: String) = save(list().filterNot { it.id == id })

    private suspend fun save(pins: List<Pin>) = settings.setPinsJson(Http.json.encodeToString(pins))

    private fun decode(json: String): List<Pin> =
        runCatching { Http.json.decodeFromString<List<Pin>>(json) }.getOrDefault(emptyList())
}

fun Pin.toMediaItem(): MediaItem {
    val kind = if (mediaType == "tv") "Serie" else "Película"
    return MediaItem(
        id = id,
        source = source,
        title = title,
        subtitle = "Fijado · ${source.label}",
        meta = listOfNotNull(year, kind, source.label).joinToString(" · "),
        overview = overview,
        cardImageUrl = backdropPath?.let { "$TMDB_IMAGES/w780$it" } ?: posterPath?.let { "$TMDB_IMAGES/w500$it" },
        posterUrl = posterPath?.let { "$TMDB_IMAGES/w342$it" },
        backdropUrl = backdropPath?.let { "$TMDB_IMAGES/w1280$it" },
        link = link,
    )
}
