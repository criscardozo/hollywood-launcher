package dev.cristian.tv.launcher.pins

import dev.cristian.tv.launcher.content.Source
import dev.cristian.tv.launcher.net.Http
import dev.cristian.tv.launcher.settings.LauncherSettings
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

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
) {
    val displayTitle get() = title ?: name.orEmpty()
    val year get() = (releaseDate ?: firstAirDate)?.take(4)?.takeIf { it.isNotBlank() }
}

/** Titles and overviews in Spanish when TMDB has them. */
private const val LANGUAGE = "es"

@Serializable private data class SearchPage(val results: List<SearchResult> = emptyList())
@Serializable private data class Details(val watchProviders: List<RegionProviders> = emptyList())
@Serializable private data class RegionProviders(val iso_3166_1: String, val flatrate: List<Provider> = emptyList())
@Serializable private data class Provider(val name: String)

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
        val url = endpoint("$mediaType/$tmdbId").newBuilder().addQueryParameter("language", LANGUAGE).build()
        val details = Http.json.decodeFromString<Details>(get(url.toString()))
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

    private suspend fun endpoint(path: String) =
        "${settings.current().seerrUrl}/api/v1/$path".toHttpUrl()

    private suspend fun get(url: String): String {
        val key = settings.current().seerrApiKey
        require(key.isNotBlank()) { "Falta la API key de Seerr" }
        return Http.get(Request.Builder().url(url).header("X-Api-Key", key).build())
    }
}
