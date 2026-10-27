package dev.cardozo.hollywood.launcher.youtube

import android.util.Xml
import dev.cardozo.hollywood.launcher.content.MediaItem
import dev.cardozo.hollywood.launcher.content.Source
import dev.cardozo.hollywood.launcher.net.Http
import dev.cardozo.hollywood.launcher.net.JsonFile
import dev.cardozo.hollywood.launcher.settings.LauncherSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

private const val API = "https://www.googleapis.com/youtube/v3"
private const val SUBSCRIPTIONS_TTL_MS = 6 * 60 * 60_000L
private const val VIDEOS_TTL_MS = 20 * 60_000L
private const val MAX_AGE_DAYS = 30L
private const val MAX_VIDEOS = 40
/** The charts move slowly: once an hour is plenty, at one unit of quota each. */
private const val POPULAR_TTL_MS = 60 * 60_000L
private const val POPULAR_MAX = 30

@Serializable private data class Page<T>(val items: List<T> = emptyList(), val nextPageToken: String? = null)
@Serializable private data class Subscription(val snippet: SubscriptionSnippet)
@Serializable private data class SubscriptionSnippet(val title: String, val resourceId: ResourceId)
@Serializable private data class ResourceId(val channelId: String? = null)
@Serializable private data class Video(val id: String, val contentDetails: ContentDetails? = null, val statistics: Statistics? = null)
@Serializable private data class ContentDetails(val duration: String? = null)
@Serializable private data class Statistics(val viewCount: String? = null)
@Serializable private data class PopularVideo(
    val id: String,
    val snippet: PopularSnippet,
    val contentDetails: ContentDetails? = null,
    val statistics: Statistics? = null,
)
@Serializable private data class PopularSnippet(
    val title: String,
    val channelTitle: String,
    val publishedAt: String,
    val description: String? = null,
)

/** The last popular row, for the region and account it was asked for. */
@Serializable
private data class SavedPopular(val account: Int, val region: String, val videos: List<SavedVideo>)

internal data class FeedEntry(
    val videoId: String,
    val title: String,
    val channel: String,
    val published: Instant,
    val description: String?,
    val isShort: Boolean,
)

/** A video as kept on disk: what the feed and videos.list said, so ages are worked out again when read. */
@Serializable
internal data class SavedVideo(
    val videoId: String,
    val title: String,
    val channel: String,
    val publishedMs: Long,
    val description: String? = null,
    val durationSeconds: Long? = null,
    val views: Long? = null,
) {
    fun entry() = FeedEntry(videoId, title, channel, Instant.ofEpochMilli(publishedMs), description, isShort = false)
    fun toMediaItem() = entry().toMediaItem(durationSeconds?.let(Duration::ofSeconds), views)
}

internal fun FeedEntry.saved(duration: Duration? = null, views: Long? = null) =
    SavedVideo(videoId, title, channel, published.toEpochMilli(), description, duration?.seconds, views)

/** The last good list, tied to the account it was loaded with (a hash of its refresh token, not the token). */
@Serializable
private data class SavedVideos(val account: Int, val videos: List<SavedVideo>)

/**
 * New videos from the channels Cristian is subscribed to. Quota-light by design: the subscription list
 * comes from the YouTube Data API (one unit per 50 channels, refreshed every 6 h); the uploads come from
 * each channel's public RSS feed (no quota); one videos.list call adds durations and views.
 */
class YouTubeRepository(private val auth: YouTubeAuth, private val settings: LauncherSettings, filesDir: File) {

    private val loading = Mutex()
    // Feeds go down now and then (every channel answering 404 for hours): the row keeps its last good list,
    // also across a restart of the launcher.
    private val saved = JsonFile(File(filesDir, "youtube-videos.json"), SavedVideos.serializer())
    private val filesDir = filesDir
    private val loadingPopular = Mutex()
    /** Per region: the account hash it was loaded for, when, and the row. */
    private val popular = mutableMapOf<String, Triple<Int, Long, List<MediaItem>>>()
    private val savedPopular = mutableMapOf<String, JsonFile<SavedPopular>>()

    private fun savedPopular(region: String) = savedPopular.getOrPut(region) {
        JsonFile(File(filesDir, "youtube-popular-$region.json"), SavedPopular.serializer())
    }
    /** The refresh token the in-memory lists were loaded with. */
    private var account: String? = null
    private var channelsAtMs = 0L
    private var videos: List<MediaItem> = emptyList()
    private var videosAtMs = 0L

    /**
     * Null when there is neither a sign-in nor a saved channel list. Cached for 20 minutes. Without a
     * valid sign-in (Google expires it after 7 days while the OAuth app is in "Testing") it keeps going
     * with the saved channels; only durations and views are missing then.
     */
    /** The last popular row for [region] that loaded, from disk; null when there is none for it. */
    suspend fun cachedPopular(region: String): List<MediaItem>? {
        val account = settings.current().youtubeRefreshToken.hashCode()
        return savedPopular(region).read()?.takeIf { it.account == account && it.region == region }
            ?.videos?.map { it.toMediaItem() }?.ifEmpty { null }
    }

    /**
     * YouTube's most popular videos in [region] (videos.list with chart=mostPopular: one unit of quota),
     * Shorts left out. Needs the sign-in; null without it. Cached for an hour, and on disk for when YouTube
     * does not answer.
     */
    suspend fun popular(region: String): List<MediaItem>? = withContext(Dispatchers.IO) {
        loadingPopular.withLock {
            val s = settings.current()
            val account = s.youtubeRefreshToken.hashCode()
            val now = System.currentTimeMillis()
            val last = popular[region]?.takeIf { it.first == account && it.third.isNotEmpty() }
            if (last != null && now - last.second < POPULAR_TTL_MS) return@withLock last.third
            val token = runCatching { auth.accessToken() }.getOrNull() ?: return@withLock null
            val url = "$API/videos".toHttpUrl().newBuilder()
                .addQueryParameter("part", "snippet,contentDetails,statistics")
                .addQueryParameter("chart", "mostPopular")
                .addQueryParameter("regionCode", region)
                .addQueryParameter("maxResults", POPULAR_MAX.toString())
                .build()
            val items = try {
                Http.json.decodeFromString<Page<PopularVideo>>(Http.get(authorized(url.toString(), token))).items
            } catch (e: Exception) {
                return@withLock last?.third ?: cachedPopular(region) ?: throw e
            }
            val kept = items.mapNotNull { video ->
                val duration = video.contentDetails?.duration?.let(::parseDuration)
                if (duration != null && duration <= Duration.ofSeconds(60)) return@mapNotNull null
                val published = runCatching { OffsetDateTime.parse(video.snippet.publishedAt).toInstant() }.getOrNull()
                    ?: return@mapNotNull null
                SavedVideo(
                    video.id, video.snippet.title, video.snippet.channelTitle, published.toEpochMilli(),
                    video.snippet.description, duration?.seconds, video.statistics?.viewCount?.toLongOrNull(),
                )
            }
            val row = kept.map { it.toMediaItem() }
            popular[region] = Triple(account, now, row)
            if (kept.isNotEmpty()) savedPopular(region).write(SavedPopular(account, region, kept))
            row
        }
    }

    /** The last list that loaded, from disk, for Home to show while it asks for a fresh one. */
    suspend fun cached(): List<MediaItem>? {
        val account = settings.current().youtubeRefreshToken.hashCode()
        return saved.read()?.takeIf { it.account == account }?.videos?.map { it.toMediaItem() }?.ifEmpty { null }
    }

    suspend fun latest(force: Boolean = false): List<MediaItem>? = withContext(Dispatchers.IO) {
        // Home (twice at start) and the settings page can ask at once: one load, the others reuse it.
        loading.withLock { load(force) }
    }

    private suspend fun load(force: Boolean): List<MediaItem>? {
        val s = settings.current()
        // Another account signed in since the last load: what is in memory belongs to the old one.
        if (s.youtubeRefreshToken != account) {
            account = s.youtubeRefreshToken
            channelsAtMs = 0L
            videos = emptyList()
            videosAtMs = 0L
        }
        val token = runCatching { auth.accessToken() }.getOrNull()
        var channels = settings.current().youtubeChannels
        if (token == null && channels.isEmpty()) return null
        val now = System.currentTimeMillis()
        if (!force && videos.isNotEmpty() && now - videosAtMs < VIDEOS_TTL_MS) return videos

        if (token != null && (channels.isEmpty() || now - channelsAtMs > SUBSCRIPTIONS_TTL_MS)) {
            // A quota error or an outage here must not cost the row: the saved channels still work over RSS.
            runCatching { subscriptions(token) }
                .onSuccess {
                    channels = it
                    settings.setYoutubeChannels(it)
                    channelsAtMs = now
                }
                .onFailure { if (channels.isEmpty()) throw it }
        }
        val cutoff = Instant.now().minus(Duration.ofDays(MAX_AGE_DAYS))
        val fetched = feeds(channels)
        // Not one feed answered (the internet is down): keep the last good list rather than empty the row.
        if (channels.isNotEmpty() && fetched.all { it == null }) {
            if (videos.isNotEmpty()) return videos
            cached()?.let { return it }
            throw IOException("No se pudo leer ningún canal")
        }
        val entries = fetched.filterNotNull().flatten()
            .filter { !it.isShort && it.published.isAfter(cutoff) }
            .sortedByDescending { it.published }
            .take(MAX_VIDEOS)
        val details = if (token != null) details(token, entries.map { it.videoId }) else emptyMap()
        val kept = entries.mapNotNull { entry ->
            val video = details[entry.videoId]
            val duration = video?.contentDetails?.duration?.let(::parseDuration)
            // Shorts that the feed does not mark as such: anything up to a minute.
            if (duration != null && duration <= Duration.ofSeconds(60)) return@mapNotNull null
            entry.saved(duration, video?.statistics?.viewCount?.toLongOrNull())
        }
        videos = kept.map { it.toMediaItem() }
        videosAtMs = now
        if (kept.isNotEmpty()) saved.write(SavedVideos(s.youtubeRefreshToken.hashCode(), kept))
        return videos
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

    /** Each channel's entries, or null for a channel whose feed did not load. */
    private suspend fun feeds(channelIds: List<String>): List<List<FeedEntry>?> = coroutineScope {
        val limit = Semaphore(8)
        channelIds.map { id ->
            async {
                limit.withPermit {
                    runCatching { channelFeed(id) }.getOrNull()
                }
            }
        }.awaitAll()
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

/** A channel's latest 15 uploads, from its public feed: no sign-in and no quota. */
internal suspend fun channelFeed(channelId: String): List<FeedEntry> =
    parseFeed(Http.get(Request.Builder().url("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId").build()))

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

internal fun FeedEntry.toMediaItem(duration: Duration? = null, views: Long? = null): MediaItem {
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
