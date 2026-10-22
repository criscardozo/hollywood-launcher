package dev.cristian.tv.launcher

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import dev.cristian.tv.launcher.admin.AdminServer
import dev.cristian.tv.launcher.apps.InstalledApps
import dev.cristian.tv.launcher.jellyfin.JellyfinRepository
import dev.cristian.tv.launcher.jellyfin.JellyfinSessionStore
import dev.cristian.tv.launcher.pins.PinsRepository
import dev.cristian.tv.launcher.pins.SeerrClient
import dev.cristian.tv.launcher.remote.RemoteKeys
import dev.cristian.tv.launcher.settings.LauncherSettings
import dev.cristian.tv.launcher.youtube.YouTubeAuth
import dev.cristian.tv.launcher.youtube.YouTubeRepository

class LauncherApplication : Application(), SingletonImageLoader.Factory {

    val settings by lazy { LauncherSettings(this) }
    val installedApps by lazy { InstalledApps(this) }
    val jellyfin by lazy { JellyfinRepository(this, JellyfinSessionStore(this)) }
    val youtubeAuth by lazy { YouTubeAuth(settings) }
    val youtube by lazy { YouTubeRepository(youtubeAuth, settings) }
    val pins by lazy { PinsRepository(settings) }
    val seerr by lazy { SeerrClient(settings) }
    val remoteKeys by lazy { RemoteKeys(this) }
    private val admin by lazy { AdminServer(this, settings, pins, seerr, jellyfin, remoteKeys, youtube) }

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
