package dev.cardozo.hollywood.launcher.news

import androidx.compose.ui.graphics.Color
import dev.cardozo.hollywood.launcher.content.MediaItem
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
import java.io.IOException
import java.time.Duration
import java.time.Instant

private const val NEWS_TTL_MS = 20 * 60_000L
/** So one channel that uploads all day does not push the others off the row. */
private const val PER_CHANNEL = 4
/** Older than this is not news any more. */
private const val MAX_AGE_HOURS = 48L

/** The Australian news channels of the «Noticias» row, with the colours of their badge on each card. */
enum class NewsChannel(val youtubeId: String, val label: String, val background: Color, val foreground: Color) {
    SEVEN("UC5T7D-Dh1eDGtsAFCuwv_Sw", "7NEWS", Color(0xFFE2001A), Color.White),
    NINE("UCIYLOcEUX6TbBo7HQVF2PKA", "9News", Color.White, Color(0xFF0A3D91)),
    TEN("UC64A-bbH15b5kN5A32CErOA", "10 News", Color(0xFF111111), Color.White),
    ABC("UCVgO39Bk5sMo66-6o6Spn6Q", "ABC", Color.Black, Color.White),
    SBS("UCuuTCooIMbBFHNbPcSS70uw", "SBS", Color(0xFFFFCC00), Color(0xFF111111)),
}

/**
 * The latest videos of [NewsChannel], newest first, at most [PER_CHANNEL] each (layout A of
 * docs/mockups/noticias.html). Only the channels' public feeds: no sign-in, no quota. The feeds carry
 * neither durations nor a live flag, so the cards show neither.
 */
class NewsRepository {

    private val loading = Mutex()
    private var videos: List<MediaItem> = emptyList()
    private var videosAtMs = 0L

    /** Cached for 20 minutes; a refresh where no feed answers keeps the last good list. */
    suspend fun latest(): List<MediaItem> = withContext(Dispatchers.IO) {
        loading.withLock {
            val now = System.currentTimeMillis()
            if (videos.isNotEmpty() && now - videosAtMs < NEWS_TTL_MS) return@withLock videos
            val cutoff = Instant.now().minus(Duration.ofHours(MAX_AGE_HOURS))
            val fetched = coroutineScope {
                NewsChannel.entries.map { channel ->
                    async { runCatching { channel to channelFeed(channel.youtubeId) }.getOrNull() }
                }.awaitAll()
            }
            if (fetched.all { it == null }) {
                if (videos.isNotEmpty()) return@withLock videos
                throw IOException("No se pudo leer ningún canal de noticias")
            }
            videos = fetched.filterNotNull()
                .flatMap { (channel, entries) ->
                    entries.filter { !it.isShort && it.published.isAfter(cutoff) }
                        .sortedByDescending { it.published }
                        .take(PER_CHANNEL)
                        .map { channel to it }
                }
                .sortedByDescending { (_, entry) -> entry.published }
                // The badge already names the channel: under the title, only how long ago.
                .map { (channel, entry) -> entry.toMediaItem().copy(subtitle = relativeAge(entry.published), newsChannel = channel) }
            videosAtMs = now
            videos
        }
    }
}
