package dev.cardozo.hollywood.launcher.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cardozo.hollywood.launcher.apps.TvApp
import dev.cardozo.hollywood.launcher.apps.inHouseOrder
import dev.cardozo.hollywood.launcher.content.MediaItem
import dev.cardozo.hollywood.launcher.content.Source
import dev.cardozo.hollywood.launcher.jellyfin.JellyfinHome
import dev.cardozo.hollywood.launcher.jellyfin.Player
import dev.cardozo.hollywood.launcher.launcherApp
import dev.cardozo.hollywood.launcher.pins.toMediaItem
import dev.cardozo.hollywood.launcher.settings.Settings
import dev.cardozo.hollywood.launcher.weather.Weather
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.exception.InvalidStatusException

sealed interface JellyfinUi {
    data object NotConnected : JellyfinUi
    data object Loading : JellyfinUi
    /** [stale]: the server did not answer, this is the last home that loaded (kept in memory or on disk). */
    data class Ready(val home: JellyfinHome, val stale: Boolean = false) : JellyfinUi
    data class Failed(val reason: String) : JellyfinUi
}

sealed interface YouTubeUi {
    /** No Google client in the settings page yet. */
    data object NotConfigured : YouTubeUi
    data object NotConnected : YouTubeUi
    data object Loading : YouTubeUi
    data class Ready(val videos: List<MediaItem>) : YouTubeUi
    data class Failed(val reason: String) : YouTubeUi
}

/** What the top shelf shows for the app focused in the dock. */
sealed interface Shelf {
    data class Items(val items: List<MediaItem>) : Shelf
    data object ConnectJellyfin : Shelf
    data object ConnectYouTube : Shelf
    /** A pinnable service with nothing pinned yet: explains where to pin from. */
    data class NothingPinned(val source: Source) : Shelf
    data object Empty : Shelf
}

private const val TAG = "HomeViewModel"
private const val WALLPAPER_EVERY_MS = 3 * 60_000L
const val SHELF_SIZE = 15
const val WEATHER_EVERY_MS = 30 * 60_000L

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application.launcherApp

    val allApps: StateFlow<List<TvApp>> =
        app.installedApps.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val dock: StateFlow<List<TvApp>> =
        allApps.map { it.inHouseOrder() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val settings: StateFlow<Settings> = app.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val pinned: StateFlow<List<MediaItem>> =
        app.pins.pins.map { pins -> pins.map { it.toMediaItem() } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _jellyfin = MutableStateFlow<JellyfinUi>(JellyfinUi.Loading)
    val jellyfin: StateFlow<JellyfinUi> = _jellyfin.asStateFlow()

    private val _youtube = MutableStateFlow<YouTubeUi>(YouTubeUi.Loading)
    val youtube: StateFlow<YouTubeUi> = _youtube.asStateFlow()

    private val _wallpaper = MutableStateFlow<String?>(null)
    val wallpaper: StateFlow<String?> = _wallpaper.asStateFlow()

    private val _weather = MutableStateFlow<Weather?>(null)
    val weather: StateFlow<Weather?> = _weather.asStateFlow()
    private var weatherAt = 0L

    init {
        viewModelScope.launch {
            app.jellyfin.session.map { it != null }.distinctUntilChanged().collect { refreshJellyfin() }
        }
        // From the store itself, not the `settings` StateFlow: its made-up initial Settings() would trigger
        // one extra load at start.
        viewModelScope.launch {
            app.settings.settings.map { it.youtubeConfigured to it.youtubeConnected }.distinctUntilChanged().collect { refreshYouTube() }
        }
        viewModelScope.launch {
            app.settings.settings.map { it.weatherLatitude to it.weatherLongitude }.distinctUntilChanged()
                .collect { refreshWeather(force = true) }
        }
        viewModelScope.launch {
            var i = 0
            while (true) {
                delay(WALLPAPER_EVERY_MS)
                val walls = (jellyfin.value as? JellyfinUi.Ready)?.home?.wallpapers.orEmpty()
                if (walls.isNotEmpty()) _wallpaper.value = walls[++i % walls.size]
            }
        }
    }

    /** Called when Home comes back to the front: what is in progress changes while another app plays. */
    fun refresh() {
        refreshJellyfin()
        refreshYouTube()
        refreshWeather()
    }

    /** At most every half hour unless the place changed; Home calls it while it is on screen. */
    fun refreshWeather(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - weatherAt < WEATHER_EVERY_MS) return
        weatherAt = System.currentTimeMillis()
        viewModelScope.launch {
            _weather.value = try {
                app.weather.current()
            } catch (e: Exception) {
                Log.w(TAG, "Weather failed", e)
                weatherAt = 0L // try again next time instead of keeping a stale reading for half an hour
                _weather.value
            }
        }
    }

    /** Home retries this every couple of minutes, while it is on screen, until the server answers again. */
    fun retryJellyfin() = refreshJellyfin()

    // One load per source at a time: at start the session collector and onResume both ask, and the retry
    // can meet a resume. A second request while one runs is dropped; the running one brings the news.
    private var jellyfinJob: Job? = null
    private var youtubeJob: Job? = null

    private fun refreshJellyfin() {
        if (jellyfinJob?.isActive == true) return
        jellyfinJob = viewModelScope.launch {
            // First load (e.g. after the TV restarts): show the last home from disk at once, then the fresh one.
            if (_jellyfin.value !is JellyfinUi.Ready) {
                _jellyfin.value = app.jellyfin.cachedHome()?.let { JellyfinUi.Ready(it) } ?: JellyfinUi.Loading
            }
            _jellyfin.value = try {
                app.jellyfin.loadHome()?.let { JellyfinUi.Ready(it) } ?: JellyfinUi.NotConnected
            } catch (e: InvalidStatusException) {
                Log.w(TAG, "Jellyfin answered ${e.status}", e)
                // The server is up but no longer accepts this session (user deleted, sessions revoked): connect
                // again, rather than say it does not answer forever.
                if (e.status == 401 || e.status == 403) {
                    app.jellyfin.disconnect()
                    JellyfinUi.NotConnected
                } else {
                    staleOrFailed(e)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Jellyfin home failed", e)
                staleOrFailed(e)
            }
            if (_wallpaper.value == null) {
                _wallpaper.value = (_jellyfin.value as? JellyfinUi.Ready)?.home?.wallpapers?.firstOrNull()
            }
        }
    }

    /** Keep showing what there was rather than emptying Home while the server is off. */
    private suspend fun staleOrFailed(e: Exception): JellyfinUi {
        val last = (_jellyfin.value as? JellyfinUi.Ready)?.home ?: app.jellyfin.cachedHome()
        return if (last != null) JellyfinUi.Ready(last, stale = true) else JellyfinUi.Failed(e.message ?: e.javaClass.simpleName)
    }

    private fun refreshYouTube() {
        if (youtubeJob?.isActive == true) return
        youtubeJob = viewModelScope.launch {
            val s = app.settings.current()
            val savedChannels = s.youtubeChannels.isNotEmpty()
            if (!s.youtubeConfigured && !savedChannels) { _youtube.value = YouTubeUi.NotConfigured; return@launch }
            if (!s.youtubeConnected && !savedChannels) { _youtube.value = YouTubeUi.NotConnected; return@launch }
            if (_youtube.value !is YouTubeUi.Ready) _youtube.value = YouTubeUi.Loading
            _youtube.value = try {
                app.youtube.latest()?.let { YouTubeUi.Ready(it) } ?: YouTubeUi.NotConnected
            } catch (e: Exception) {
                Log.w(TAG, "YouTube failed", e)
                // A failed refresh keeps the videos already on screen.
                _youtube.value as? YouTubeUi.Ready ?: YouTubeUi.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun shelfFor(appPackage: String?, jellyfin: JellyfinUi, youtube: YouTubeUi, pinned: List<MediaItem>): Shelf {
        if (appPackage in Player.packages) {
            return when (jellyfin) {
                is JellyfinUi.Ready -> Shelf.Items(jellyfin.home.continueWatching)
                JellyfinUi.NotConnected -> Shelf.ConnectJellyfin
                else -> Shelf.Empty
            }
        }
        val source = Source.forPackage(appPackage) ?: return Shelf.Empty
        return when (source) {
            Source.YOUTUBE -> when (youtube) {
                is YouTubeUi.Ready -> Shelf.Items(youtube.videos.take(SHELF_SIZE))
                YouTubeUi.NotConnected, YouTubeUi.NotConfigured -> Shelf.ConnectYouTube
                else -> Shelf.Empty
            }
            in Source.pinnable -> pinned.filter { it.source == source }
                .let { if (it.isEmpty()) Shelf.NothingPinned(source) else Shelf.Items(it) }
            else -> Shelf.Empty
        }
    }
}
