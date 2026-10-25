package dev.cardozo.hollywood.launcher

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import dev.cardozo.hollywood.launcher.admin.AdminServer
import dev.cardozo.hollywood.launcher.apps.InstalledApps
import dev.cardozo.hollywood.launcher.jellyfin.JellyfinRepository
import dev.cardozo.hollywood.launcher.jellyfin.JellyfinSessionStore
import dev.cardozo.hollywood.launcher.news.NewsRepository
import dev.cardozo.hollywood.launcher.pins.PinsRepository
import dev.cardozo.hollywood.launcher.pins.SeerrClient
import dev.cardozo.hollywood.launcher.remote.RemoteKeys
import dev.cardozo.hollywood.launcher.settings.LauncherSettings
import dev.cardozo.hollywood.launcher.weather.WeatherRepository
import dev.cardozo.hollywood.launcher.youtube.YouTubeAuth
import dev.cardozo.hollywood.launcher.youtube.YouTubeRepository

class LauncherApplication : Application(), SingletonImageLoader.Factory {

    val settings by lazy { LauncherSettings(this) }
    val installedApps by lazy { InstalledApps(this) }
    val jellyfin by lazy { JellyfinRepository(this, JellyfinSessionStore(this)) }
    val youtubeAuth by lazy { YouTubeAuth(settings) }
    val youtube by lazy { YouTubeRepository(youtubeAuth, settings) }
    val news by lazy { NewsRepository() }
    val pins by lazy { PinsRepository(settings) }
    val seerr by lazy { SeerrClient(settings) }
    val weather by lazy { WeatherRepository(settings) }
    val remoteKeys by lazy { RemoteKeys(this) }
    private val admin by lazy { AdminServer(this, settings, pins, seerr, jellyfin, remoteKeys, youtube, youtubeAuth, weather) }

    override fun onCreate() {
        super.onCreate()
        // Lives as long as the launcher process, which as Home is always running. No service needed.
        admin.startQuietly()
    }

    // The TV has 1.8 GB shared with the streaming apps: keep the image cache small.
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.10).build() }
            .crossfade(true)
            .build()
}

val android.content.Context.launcherApp: LauncherApplication
    get() = applicationContext as LauncherApplication
