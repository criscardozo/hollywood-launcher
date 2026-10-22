package dev.cristian.tv.launcher.content

import androidx.compose.ui.graphics.Color

/** Where a card comes from: it decides the hero badge and what OK does. */
enum class Source(val label: String, val color: Color, val packageName: String?) {
    JELLYFIN("Jellyfin", Color(0xFFA95CE0), null),
    YOUTUBE("YouTube", Color(0xFFFF2D2D), "com.google.android.youtube.tv"),
    PRIME("Prime Video", Color(0xFF1FA2FF), "com.amazon.amazonvideo.livingroom"),
    DISNEY("Disney+", Color(0xFF3D7BFF), "com.disney.disneyplus"),
    MAX("Max", Color(0xFF5A6BFF), "com.wbd.stream");

    companion object {
        /** The services whose content can only be pinned by hand (no API, no «continue watching»). */
        val pinnable = listOf(PRIME, DISNEY, MAX)

        fun forPackage(packageName: String?): Source? = entries.firstOrNull { it.packageName == packageName }
    }
}

/** A card on the home screen, already resolved to image URLs, whatever its source. */
data class MediaItem(
    val id: String,
    val source: Source,
    val title: String,
    /** Episode («T2 · E4 «Name»»), channel, or year. */
    val subtitle: String?,
    /** Year · genre · runtime · rating, or channel · views · age, for the hero. */
    val meta: String,
    val overview: String?,
    /** 16:9 card image. */
    val cardImageUrl: String?,
    /** 2:3 poster, for poster rows. */
    val posterUrl: String?,
    /** Full-screen backdrop shown behind everything while the item is focused. */
    val backdropUrl: String?,
    /** 0..1, null when it has not been started. */
    val progress: Float? = null,
    /** «Quedan 20 min», null when it has not been started. */
    val remaining: String? = null,
    val positionMs: Long = 0,
    /** Badge on the card, e.g. a video's duration. */
    val duration: String? = null,
    /** What OK opens for YouTube and pinned items (a watch URL or the link the user pinned). */
    val link: String? = null,
)
