package dev.cristian.tv.launcher.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cristian.tv.launcher.apps.TvApp
import dev.cristian.tv.launcher.apps.inHouseOrder
import dev.cristian.tv.launcher.content.MediaItem
import dev.cristian.tv.launcher.content.Source
import dev.cristian.tv.launcher.jellyfin.JellyfinHome
import dev.cristian.tv.launcher.jellyfin.Player
import dev.cristian.tv.launcher.launcherApp
import dev.cristian.tv.launcher.pins.toMediaItem
import dev.cristian.tv.launcher.settings.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface JellyfinUi {
    data object NotConnected : JellyfinUi
    data object Loading : JellyfinUi
    data class Ready(val home: JellyfinHome) : JellyfinUi
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

    init {
        viewModelScope.launch {
            app.jellyfin.session.map { it != null }.distinctUntilChanged().collect { refreshJellyfin() }
        }
        viewModelScope.launch {
            settings.map { it.youtubeConfigured to it.youtubeConnected }.distinctUntilChanged().collect { refreshYouTube() }
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
    }

    private fun refreshJellyfin() {
        viewModelScope.launch {
            if (_jellyfin.value !is JellyfinUi.Ready) _jellyfin.value = JellyfinUi.Loading
            _jellyfin.value = try {
                app.jellyfin.loadHome()?.let { JellyfinUi.Ready(it) } ?: JellyfinUi.NotConnected
            } catch (e: Exception) {
                Log.w(TAG, "Jellyfin home failed", e)
                JellyfinUi.Failed(e.message ?: e.javaClass.simpleName)
            }
            if (_wallpaper.value == null) {
                _wallpaper.value = (_jellyfin.value as? JellyfinUi.Ready)?.home?.wallpapers?.firstOrNull()
            }
        }
    }

    private fun refreshYouTube() {
        viewModelScope.launch {
            val s = app.settings.current()
            val savedChannels = s.youtubeChannels.isNotEmpty()
            if (!s.youtubeConfigured && !savedChannels) { _youtube.value = YouTubeUi.NotConfigured; return@launch }
            if (!s.youtubeConnected && !savedChannels) { _youtube.value = YouTubeUi.NotConnected; return@launch }
            if (_youtube.value !is YouTubeUi.Ready) _youtube.value = YouTubeUi.Loading
            _youtube.value = try {
                app.youtube.latest()?.let { YouTubeUi.Ready(it) } ?: YouTubeUi.NotConnected
            } catch (e: Exception) {
                Log.w(TAG, "YouTube failed", e)
                YouTubeUi.Failed(e.message ?: e.javaClass.simpleName)
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
