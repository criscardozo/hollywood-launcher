package dev.cardozo.hollywood.launcher.details

import dev.cardozo.hollywood.launcher.content.MediaItem
import dev.cardozo.hollywood.launcher.content.Source

data class Person(val name: String, val role: String?, val imageUrl: String?)

data class Season(val id: String, val name: String)

/** A button on the details page and what it plays. */
data class Action(val label: String, val note: String? = null, val item: MediaItem)

/**
 * Everything the details page shows for a film or a series, from Jellyfin or (for pinned titles) from
 * TMDB through Seerr. [source] says where it plays from: the badge under the buttons.
 */
data class Details(
    val source: Source,
    /** The Jellyfin server's own name («Ginebra»); null for pinned titles, which use the service's. */
    val sourceName: String? = null,
    val title: String,
    /** Year, runtime or number of seasons. */
    val facts: List<String>,
    /** Classification (M, MA15+…), shown boxed. */
    val rating: String?,
    /** Community or TMDB score, «7,8». */
    val score: String?,
    /** «termina a las 21:42» for a film, the episode it continues from for a series. */
    val note: String?,
    val genres: List<String>,
    val overview: String?,
    val backdropUrl: String?,
    /** Where playback stands, for the bar above the buttons. */
    val progress: Float? = null,
    val remaining: String? = null,
    val actions: List<Action>,
    val cast: List<Person> = emptyList(),
    val seasons: List<Season> = emptyList(),
    /** The season to open on: the one the next episode is in. */
    val seasonId: String? = null,
    val seriesId: String? = null,
) {
    val isSeries get() = seasons.isNotEmpty()

    companion object {
        /** What the card already knows, shown while the rest loads or if it cannot load. */
        fun from(item: MediaItem, playLabel: String) = Details(
            source = item.source,
            title = item.title,
            facts = listOf(item.meta).filter { it.isNotBlank() },
            rating = null,
            score = null,
            note = null,
            genres = emptyList(),
            overview = item.overview,
            backdropUrl = item.backdropUrl ?: item.cardImageUrl,
            progress = item.progress,
            remaining = item.remaining,
            actions = listOf(Action(playLabel, item = item)),
        )
    }
}
