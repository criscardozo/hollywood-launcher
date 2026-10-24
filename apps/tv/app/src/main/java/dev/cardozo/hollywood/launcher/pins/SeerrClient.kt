package dev.cardozo.hollywood.launcher.pins

import dev.cardozo.hollywood.launcher.content.Source
import dev.cardozo.hollywood.launcher.details.Action
import dev.cardozo.hollywood.launcher.details.Details
import dev.cardozo.hollywood.launcher.details.Person
import dev.cardozo.hollywood.launcher.details.trailerAction
import dev.cardozo.hollywood.launcher.net.Http
import dev.cardozo.hollywood.launcher.settings.LauncherSettings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.Locale

/** A TMDB search result, as Seerr returns it. */
@Serializable
data class SearchResult(
    val id: Int,
    val mediaType: String,
    val title: String? = null,
    val name: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
)

private const val TMDB_PROFILES = "https://image.tmdb.org/t/p/w185"

/** Titles and overviews in Spanish when TMDB has them. */
private const val LANGUAGE = "es"

@Serializable private data class SearchPage(val results: List<SearchResult> = emptyList())
@Serializable private data class WatchProviders(val watchProviders: List<RegionProviders> = emptyList())
@Serializable private data class RegionProviders(val iso_3166_1: String, val flatrate: List<Provider> = emptyList())
@Serializable private data class Provider(val name: String)

@Serializable private data class Genre(val name: String)
@Serializable private data class CastMember(val name: String, val character: String? = null, val profilePath: String? = null)
@Serializable private data class Credits(val cast: List<CastMember> = emptyList())
@Serializable private data class Certification(val certification: String = "")
@Serializable
private data class CountryRelease(
    @SerialName("iso_3166_1") val country: String,
    @SerialName("release_dates") val dates: List<Certification> = emptyList(),
)
@Serializable private data class Releases(val results: List<CountryRelease> = emptyList())
@Serializable private data class CountryRating(@SerialName("iso_3166_1") val country: String, val rating: String = "")
@Serializable private data class ContentRatings(val results: List<CountryRating> = emptyList())
@Serializable private data class Video(val type: String? = null, val site: String? = null, val url: String? = null)

/** The fields of Seerr's /movie and /tv responses the details page uses. */
@Serializable
private data class TitleDetails(
    val runtime: Int? = null,
    val numberOfSeasons: Int? = null,
    val genres: List<Genre> = emptyList(),
    val voteAverage: Double? = null,
    val credits: Credits = Credits(),
    val releases: Releases = Releases(),
    val contentRatings: ContentRatings = ContentRatings(),
    val relatedVideos: List<Video> = emptyList(),
) {
    /** TMDB's first YouTube trailer. */
    val trailer get() = relatedVideos.firstOrNull { it.type == "Trailer" && it.site == "YouTube" && it.url != null }?.url
}

/**
 * TMDB through Cristian's Seerr (Ginebra, port 5055): search, and which subscription services carry a
 * title in Australia, to suggest the service when pinning. The API key is typed into the settings page.
 */
class SeerrClient(private val settings: LauncherSettings) {

    suspend fun search(query: String): List<SearchResult> {
        val url = endpoint("search").newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("page", "1")
            .addQueryParameter("language", LANGUAGE)
            .build()
        return Http.json.decodeFromString<SearchPage>(get(url.toString())).results
            .filter { it.mediaType == "movie" || it.mediaType == "tv" }
    }

    /** The pinnable services that stream this title in Australia, best guess first. */
    suspend fun services(tmdbId: Int, mediaType: String, region: String = "AU"): List<Source> {
        val url = endpoint(titlePath(mediaType, tmdbId)).newBuilder().addQueryParameter("language", LANGUAGE).build()
        val details = Http.json.decodeFromString<WatchProviders>(get(url.toString()))
        val names = details.watchProviders.firstOrNull { it.iso_3166_1 == region }?.flatrate.orEmpty().map { it.name.lowercase() }
        return names.mapNotNull { name ->
            when {
                "prime" in name || "amazon" in name -> Source.PRIME
                "disney" in name -> Source.DISNEY
                name == "max" || "hbo" in name -> Source.MAX
                else -> null
            }
        }.distinct()
    }

    /** The details page of a pinned title: TMDB's metadata, with the pin's own service as the source. */
    suspend fun details(pin: Pin, region: String = "AU"): Details {
        val url = endpoint(titlePath(pin.mediaType, pin.tmdbId)).newBuilder().addQueryParameter("language", LANGUAGE).build()
        val d = Http.json.decodeFromString<TitleDetails>(get(url.toString()))
        val item = pin.toMediaItem()
        // Australian classification: per release for films, per country for series.
        val rating = d.releases.results.firstOrNull { it.country == region }?.dates?.map { it.certification }?.firstOrNull { it.isNotBlank() }
            ?: d.contentRatings.results.firstOrNull { it.country == region }?.rating?.takeIf { it.isNotBlank() }
        val length = d.runtime?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min" }
            ?: d.numberOfSeasons?.let { if (it == 1) "1 temporada" else "$it temporadas" }
        return Details(
            source = pin.source,
            title = pin.title,
            facts = listOfNotNull(pin.year, length),
            rating = rating,
            score = d.voteAverage?.takeIf { it > 0 }?.let { String.format(Locale.forLanguageTag("es"), "%.1f", it) },
            note = null,
            genres = d.genres.map { it.name },
            overview = pin.overview,
            backdropUrl = item.backdropUrl,
            actions = listOfNotNull(Action("Abrir en ${pin.source.label}", item = item), d.trailer?.let(::trailerAction)),
            cast = d.credits.cast.take(12).map { Person(it.name, it.character?.takeIf { c -> c.isNotBlank() }, it.profilePath?.let { p -> "$TMDB_PROFILES$p" }) },
        )
    }

    /** A trailer from TMDB, for a Jellyfin title whose own metadata has none. */
    suspend fun trailer(tmdbId: Int, mediaType: String): String? {
        val url = endpoint(titlePath(mediaType, tmdbId)).newBuilder().addQueryParameter("language", LANGUAGE).build()
        return Http.json.decodeFromString<TitleDetails>(get(url.toString())).trailer
    }

    /** A search with a URL and key that are not saved yet: throws when either is wrong. */
    suspend fun test(url: String, apiKey: String) {
        val base = url.trim().trimEnd('/')
        require(base.startsWith("http://") || base.startsWith("https://")) { "La dirección empieza con http:// o https://" }
        require(apiKey.isNotBlank()) { "Falta la API key de Seerr" }
        val search = "$base/api/v1/search".toHttpUrl().newBuilder().addQueryParameter("query", "test").build()
        Http.get(Request.Builder().url(search).header("X-Api-Key", apiKey.trim()).build())
    }

    private suspend fun endpoint(path: String) =
        "${settings.current().seerrUrl}/api/v1/$path".toHttpUrl()

    /** «movie» or «tv» only: anything else would reach other Seerr endpoints with the admin key. */
    private fun titlePath(mediaType: String, tmdbId: Int): String {
        require(mediaType == "movie" || mediaType == "tv") { "mediaType: movie o tv" }
        return "$mediaType/$tmdbId"
    }

    private suspend fun get(url: String): String {
        val key = settings.current().seerrApiKey
        require(key.isNotBlank()) { "Falta la API key de Seerr" }
        return Http.get(Request.Builder().url(url).header("X-Api-Key", key).build())
    }
}
