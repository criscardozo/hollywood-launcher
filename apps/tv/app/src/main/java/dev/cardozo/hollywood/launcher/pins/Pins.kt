package dev.cardozo.hollywood.launcher.pins

import dev.cardozo.hollywood.launcher.content.MediaItem
import dev.cardozo.hollywood.launcher.content.Source
import dev.cardozo.hollywood.launcher.net.Http
import dev.cardozo.hollywood.launcher.settings.LauncherSettings
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

    val pins: Flow<List<Pin>> = settings.settings.map { runCatching { decode(it.pinsJson) }.getOrDefault(emptyList()) }

    suspend fun list(): List<Pin> = runCatching { decode(settings.current().pinsJson) }.getOrDefault(emptyList())

    suspend fun add(pin: Pin): Pin {
        update { pins -> pins.filterNot { it.tmdbId == pin.tmdbId && it.source == pin.source } + pin }
        return pin
    }

    suspend fun remove(id: String) = update { pins -> pins.filterNot { it.id == id } }

    /**
     * One read-modify-write. Stored pins that no longer decode (say, a service renamed in a later build) make
     * this fail instead of being replaced by a list holding only the new pin.
     */
    private suspend fun update(change: (List<Pin>) -> List<Pin>) =
        settings.updatePinsJson { json -> Http.json.encodeToString(change(decode(json))) }

    private fun decode(json: String): List<Pin> = Http.json.decodeFromString<List<Pin>>(json)
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
