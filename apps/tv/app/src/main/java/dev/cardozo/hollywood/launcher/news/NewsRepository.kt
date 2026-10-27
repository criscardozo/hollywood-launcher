package dev.cardozo.hollywood.launcher.news

import android.util.Log
import androidx.compose.ui.graphics.Color
import dev.cardozo.hollywood.launcher.content.MediaItem
import dev.cardozo.hollywood.launcher.net.JsonFile
import dev.cardozo.hollywood.launcher.youtube.FeedEntry
import dev.cardozo.hollywood.launcher.youtube.SavedVideo
import dev.cardozo.hollywood.launcher.youtube.saved
import dev.cardozo.hollywood.launcher.youtube.channelFeed
import dev.cardozo.hollywood.launcher.youtube.relativeAge
import dev.cardozo.hollywood.launcher.youtube.toMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant

private const val TAG = "News"
private const val NEWS_TTL_MS = 20 * 60_000L
/** So one channel that uploads all day does not push the others off the row. */
private const val PER_CHANNEL = 4
/** Older than this is not news any more. */
private const val MAX_AGE_HOURS = 48L
private val NINE_BULLETIN = Regex("""^9News ([^|]+?) \|""")

/** The Australian news channels of the «Noticias» row, with the colours of their badge on each card. */
@Serializable
enum class NewsChannel(
    val youtubeId: String,
    val label: String,
    val background: Color,
    val foreground: Color,
    /** Which videos of the channel go in the row, by title. */
    val keeps: (String) -> Boolean = { true },
) {
    SEVEN("UC5T7D-Dh1eDGtsAFCuwv_Sw", "7NEWS", Color(0xFFE2001A), Color.White),
    // 9News uploads the full bulletin of each city («9News Brisbane | Sunday 25 October | Full Bulletin»),
    // all alike: only Sydney's.
    NINE("UCIYLOcEUX6TbBo7HQVF2PKA", "9News", Color.White, Color(0xFF0A3D91), keeps = { title ->
        NINE_BULLETIN.find(title)?.groupValues?.get(1)?.equals("Sydney", ignoreCase = true) ?: true
    }),
    TEN("UC64A-bbH15b5kN5A32CErOA", "10 News", Color(0xFF111111), Color.White),
    ABC("UCVgO39Bk5sMo66-6o6Spn6Q", "ABC", Color.Black, Color.White),
    SBS("UCuuTCooIMbBFHNbPcSS70uw", "SBS", Color(0xFFFFCC00), Color(0xFF111111)),
}

/**
 * The latest videos of [NewsChannel], newest first, at most [PER_CHANNEL] each (layout A of
 * docs/mockups/noticias.html). Only the channels' public feeds: no sign-in, no quota. The feeds carry
 * neither durations nor a live flag, so the cards show neither.
 */
class NewsRepository(filesDir: File) {

    private val loading = Mutex()
    private var videos: List<MediaItem> = emptyList()
    private var videosAtMs = 0L
    // The last good entries of each channel: a channel whose feed does not answer (YouTube's feeds go down
    // for hours now and then) keeps what it had, also across a restart of the launcher.
    private val saved = JsonFile(File(filesDir, "news-videos.json"), MapSerializer(NewsChannel.serializer(), ListSerializer(SavedVideo.serializer())))

    /** The last row that loaded, from disk, for Home to show while it asks for a fresh one. */
    suspend fun cached(): List<MediaItem> = row(saved.read().orEmpty().mapValues { (_, list) -> list.map { it.entry() } })

    /** Cached for 20 minutes; a channel that does not answer keeps its last good entries. */
    suspend fun latest(): List<MediaItem> = withContext(Dispatchers.IO) {
        loading.withLock {
            val now = System.currentTimeMillis()
            if (videos.isNotEmpty() && now - videosAtMs < NEWS_TTL_MS) return@withLock videos
            val fetched = coroutineScope {
                NewsChannel.entries.map { channel ->
                    async {
                        runCatching { channel to channelFeed(channel.youtubeId) }
                            .onFailure { Log.w(TAG, "${channel.label} did not load", it) }
                            .getOrNull()
                    }
                }.awaitAll().filterNotNull().toMap()
            }
            val before = saved.read().orEmpty()
            val entries = NewsChannel.entries.associateWith { channel ->
                fetched[channel] ?: before[channel]?.map { it.entry() }.orEmpty()
            }
            if (fetched.isNotEmpty()) saved.write(entries.mapValues { (_, list) -> list.filter { !it.isShort }.map { it.saved() } })
            val row = row(entries)
            if (row.isEmpty() && fetched.isEmpty()) {
                if (videos.isNotEmpty()) return@withLock videos
                throw IOException("No se pudo leer ningún canal de noticias")
            }
            videos = row
            videosAtMs = now
            videos
        }
    }

    /** Newest first, at most [PER_CHANNEL] each, nothing older than [MAX_AGE_HOURS]. */
    private fun row(entries: Map<NewsChannel, List<FeedEntry>>): List<MediaItem> {
        val cutoff = Instant.now().minus(Duration.ofHours(MAX_AGE_HOURS))
        return entries
            .flatMap { (channel, list) ->
                list.filter { !it.isShort && it.published.isAfter(cutoff) && channel.keeps(it.title) }
                    .sortedByDescending { it.published }
                    .take(PER_CHANNEL)
                    .map { channel to it }
            }
            .sortedByDescending { (_, entry) -> entry.published }
            // The badge already names the channel: under the title, only how long ago.
            .map { (channel, entry) -> entry.toMediaItem().copy(subtitle = relativeAge(entry.published), newsChannel = channel) }
    }
}
