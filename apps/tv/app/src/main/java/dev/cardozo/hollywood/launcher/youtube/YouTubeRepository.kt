package dev.cardozo.hollywood.launcher.youtube

import android.util.Xml
import dev.cardozo.hollywood.launcher.content.MediaItem
import dev.cardozo.hollywood.launcher.content.Source
import dev.cardozo.hollywood.launcher.net.Http
import dev.cardozo.hollywood.launcher.settings.LauncherSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

private const val API = "https://www.googleapis.com/youtube/v3"
private const val SUBSCRIPTIONS_TTL_MS = 6 * 60 * 60_000L
private const val VIDEOS_TTL_MS = 20 * 60_000L
private const val MAX_AGE_DAYS = 30L
private const val MAX_VIDEOS = 40

@Serializable private data class Page<T>(val items: List<T> = emptyList(), val nextPageToken: String? = null)
@Serializable private data class Subscription(val snippet: SubscriptionSnippet)
@Serializable private data class SubscriptionSnippet(val title: String, val resourceId: ResourceId)
@Serializable private data class ResourceId(val channelId: String? = null)
@Serializable private data class Video(val id: String, val contentDetails: ContentDetails? = null, val statistics: Statistics? = null)
@Serializable private data class ContentDetails(val duration: String? = null)
@Serializable private data class Statistics(val viewCount: String? = null)

private data class FeedEntry(
    val videoId: String,
    val title: String,
    val channel: String,
    val published: Instant,
    val description: String?,
    val isShort: Boolean,
)

/**
 * New videos from the channels Cristian is subscribed to. Quota-light by design: the subscription list
 * comes from the YouTube Data API (one unit per 50 channels, refreshed every 6 h); the uploads come from
 * each channel's public RSS feed (no quota); one videos.list call adds durations and views.
 */
class YouTubeRepository(private val auth: YouTubeAuth, private val settings: LauncherSettings) {

    private var channelsAtMs = 0L
    private var videos: List<MediaItem> = emptyList()
    private var videosAtMs = 0L

    /**
     * Null when there is neither a sign-in nor a saved channel list. Cached for 20 minutes. Without a
     * valid sign-in (Google expires it after 7 days while the OAuth app is in "Testing") it keeps going
     * with the saved channels; only durations and views are missing then.
     */
    suspend fun latest(force: Boolean = false): List<MediaItem>? = withContext(Dispatchers.IO) {
        val token = runCatching { auth.accessToken() }.getOrNull()
        var channels = settings.current().youtubeChannels
        if (token == null && channels.isEmpty()) return@withContext null
        val now = System.currentTimeMillis()
        if (!force && videos.isNotEmpty() && now - videosAtMs < VIDEOS_TTL_MS) return@withContext videos

        if (token != null && (channels.isEmpty() || now - channelsAtMs > SUBSCRIPTIONS_TTL_MS)) {
            channels = subscriptions(token)
            settings.setYoutubeChannels(channels)
            channelsAtMs = now
        }
        val cutoff = Instant.now().minus(Duration.ofDays(MAX_AGE_DAYS))
        val entries = feeds(channels)
            .filter { !it.isShort && it.published.isAfter(cutoff) }
            .sortedByDescending { it.published }
            .take(MAX_VIDEOS)
        val details = if (token != null) details(token, entries.map { it.videoId }) else emptyMap()
        videos = entries.mapNotNull { entry ->
            val video = details[entry.videoId]
            val duration = video?.contentDetails?.duration?.let(::parseDuration)
            // Shorts that the feed does not mark as such: anything up to a minute.
            if (duration != null && duration <= Duration.ofSeconds(60)) return@mapNotNull null
            entry.toMediaItem(duration, video?.statistics?.viewCount?.toLongOrNull())
        }
        videosAtMs = now
        videos
    }

    private suspend fun subscriptions(token: String): List<String> {
        val ids = mutableListOf<String>()
        var pageToken: String? = null
        do {
            val url = "$API/subscriptions".toHttpUrl().newBuilder()
                .addQueryParameter("part", "snippet")
                .addQueryParameter("mine", "true")
                .addQueryParameter("maxResults", "50")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val page = Http.json.decodeFromString<Page<Subscription>>(Http.get(authorized(url.toString(), token)))
            ids += page.items.mapNotNull { it.snippet.resourceId.channelId }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return ids
    }

    private suspend fun feeds(channelIds: List<String>): List<FeedEntry> = coroutineScope {
        val limit = Semaphore(8)
        channelIds.map { id ->
            async {
                limit.withPermit {
                    runCatching {
                        parseFeed(Http.get(Request.Builder().url("https://www.youtube.com/feeds/videos.xml?channel_id=$id").build()))
                    }.getOrDefault(emptyList())
                }
            }
        }.awaitAll().flatten()
    }

    private suspend fun details(token: String, ids: List<String>): Map<String, Video> {
        if (ids.isEmpty()) return emptyMap()
        return ids.chunked(50).flatMap { chunk ->
            val url = "$API/videos".toHttpUrl().newBuilder()
                .addQueryParameter("part", "contentDetails,statistics")
                .addQueryParameter("id", chunk.joinToString(","))
                .build()
            runCatching {
                Http.json.decodeFromString<Page<Video>>(Http.get(authorized(url.toString(), token))).items
            }.getOrDefault(emptyList())
        }.associateBy { it.id }
    }

    private fun authorized(url: String, token: String) =
        Request.Builder().url(url).header("Authorization", "Bearer $token").build()
}

/** The channel feed: Atom with yt: and media: extensions. Shorts link to /shorts/ instead of /watch. */
private fun parseFeed(xml: String): List<FeedEntry> {
    val parser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(xml.reader())
    }
    val entries = mutableListOf<FeedEntry>()
    var inEntry = false
    var videoId = ""; var title = ""; var channel = ""; var published = ""; var description: String? = null
    var isShort = false
    var inAuthor = false
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "entry" -> { inEntry = true; videoId = ""; title = ""; channel = ""; published = ""; description = null; isShort = false }
                "author" -> inAuthor = true
                "yt:videoId" -> if (inEntry) videoId = parser.nextText()
                "title" -> if (inEntry && !inAuthor) title = parser.nextText()
                "name" -> if (inEntry && inAuthor) channel = parser.nextText()
                "published" -> if (inEntry) published = parser.nextText()
                "media:description" -> if (inEntry) description = parser.nextText()
                "link" -> if (inEntry && parser.getAttributeValue(null, "href")?.contains("/shorts/") == true) isShort = true
            }
            XmlPullParser.END_TAG -> when (parser.name) {
                "author" -> inAuthor = false
                "entry" -> {
                    inEntry = false
                    // The feed writes 2026-10-20T10:00:00+00:00; Instant.parse rejects the offset on Android 12.
                    val instant = runCatching { OffsetDateTime.parse(published).toInstant() }.getOrNull()
                    if (videoId.isNotBlank() && instant != null) {
                        entries += FeedEntry(videoId, title, channel, instant, description, isShort)
                    }
                }
            }
        }
    }
    return entries
}

private fun FeedEntry.toMediaItem(duration: Duration?, views: Long?): MediaItem {
    val age = relativeAge(published)
    return MediaItem(
        id = videoId,
        source = Source.YOUTUBE,
        title = title,
        subtitle = "$channel · $age",
        meta = listOfNotNull(channel, views?.let { "${compactCount(it)} vistas" }, age).joinToString(" · "),
        overview = description?.lineSequence()?.firstOrNull { it.isNotBlank() },
        cardImageUrl = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
        posterUrl = null,
        backdropUrl = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg",
        duration = duration?.let(::clock),
        link = "https://www.youtube.com/watch?v=$videoId",
    )
}

/** ISO 8601 durations as YouTube sends them: PT1H2M3S, PT45S, P1DT2H. */
internal fun parseDuration(iso: String): Duration? = runCatching { Duration.parse(iso) }.getOrNull()

internal fun clock(d: Duration): String {
    val h = d.toHours(); val m = d.toMinutesPart(); val s = d.toSecondsPart()
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

internal fun compactCount(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.forLanguageTag("es-AR"), "%.1f M", n / 1_000_000.0).replace(",0 ", " ")
    n >= 1_000 -> "${n / 1_000} k"
    else -> n.toString()
}

internal fun relativeAge(at: Instant, now: Instant = Instant.now()): String {
    val minutes = Duration.between(at, now).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 60 -> "hace $minutes min"
        minutes < 24 * 60 -> "hace ${minutes / 60} h"
        minutes < 48 * 60 -> "ayer"
        else -> "hace ${minutes / (24 * 60)} días"
    }
}
