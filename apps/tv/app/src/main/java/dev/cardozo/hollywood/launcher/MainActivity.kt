package dev.cardozo.hollywood.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.cardozo.hollywood.launcher.content.MediaItem
import dev.cardozo.hollywood.launcher.content.open
import dev.cardozo.hollywood.launcher.details.DetailsScreen
import dev.cardozo.hollywood.launcher.screensaver.filmToOpen
import dev.cardozo.hollywood.launcher.home.AllAppsScreen
import dev.cardozo.hollywood.launcher.home.HomeScreen
import dev.cardozo.hollywood.launcher.home.HomeViewModel
import dev.cardozo.hollywood.launcher.jellyfin.ConnectScreen
import dev.cardozo.hollywood.launcher.settings.LauncherSettingsScreen
import dev.cardozo.hollywood.launcher.youtube.YouTubeConnectScreen
import dev.cardozo.hollywood.launcher.ui.LauncherTheme

private enum class Screen { HOME, ALL_APPS, CONNECT_JELLYFIN, CONNECT_YOUTUBE, SETTINGS }

class MainActivity : ComponentActivity() {

    private val homeViewModel: HomeViewModel by viewModels()
    private var screen by mutableStateOf(Screen.HOME)
    private var focusDockSignal by mutableIntStateOf(0)
    /** A film or series whose details page is open over Home. */
    private var details by mutableStateOf<MediaItem?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.filmToOpen()?.let { details = it }
        setContent {
            LauncherTheme {
                val settings by homeViewModel.settings.collectAsState()
                // A launcher never leaves on Back: from a sub-screen it returns home, at home it does nothing.
                BackHandler { if (details != null) details = null else screen = Screen.HOME }
                when (screen) {
                    // Details is drawn over Home rather than replacing it, so Home keeps its focus and scroll.
                    Screen.HOME -> Box {
                        HomeScreen(
                            onConnectJellyfin = { screen = Screen.CONNECT_JELLYFIN },
                            onConnectYouTube = { screen = Screen.CONNECT_YOUTUBE },
                            onOpenSettings = { screen = Screen.SETTINGS },
                            onAllApps = { screen = Screen.ALL_APPS },
                            onOpenDetails = { details = it },
                            focusDockSignal = focusDockSignal,
                            active = details == null,
                            viewModel = homeViewModel,
                        )
                        details?.let { item ->
                            DetailsScreen(item, settings.player, onPlay = { open(it, settings.player) })
                        }
                    }
                    Screen.ALL_APPS -> AllAppsScreen(onOpenSettings = { screen = Screen.SETTINGS }, viewModel = homeViewModel)
                    Screen.CONNECT_JELLYFIN -> ConnectScreen(onDone = { screen = Screen.HOME })
                    Screen.CONNECT_YOUTUBE -> YouTubeConnectScreen(onDone = { screen = Screen.HOME })
                    Screen.SETTINGS -> LauncherSettingsScreen(
                        onConnectYouTube = { screen = Screen.CONNECT_YOUTUBE },
                        onConnectJellyfin = { screen = Screen.CONNECT_JELLYFIN },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        homeViewModel.refresh()
    }

    /** The Home key while the launcher is already in front: back to the dock. Or a film from the screensaver. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        screen = Screen.HOME
        // OK on the screensaver: that film's details page over Home, focus left where it was.
        details = intent.filmToOpen()
        if (details == null) focusDockSignal++
    }
}
