package dev.cristian.tv.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.cristian.tv.launcher.home.AllAppsScreen
import dev.cristian.tv.launcher.home.HomeScreen
import dev.cristian.tv.launcher.home.HomeViewModel
import dev.cristian.tv.launcher.jellyfin.ConnectScreen
import dev.cristian.tv.launcher.settings.LauncherSettingsScreen
import dev.cristian.tv.launcher.youtube.YouTubeConnectScreen
import dev.cristian.tv.launcher.ui.LauncherTheme

private enum class Screen { HOME, ALL_APPS, CONNECT_JELLYFIN, CONNECT_YOUTUBE, SETTINGS }

class MainActivity : ComponentActivity() {

    private val homeViewModel: HomeViewModel by viewModels()
    private var screen by mutableStateOf(Screen.HOME)
    private var focusDockSignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LauncherTheme {
                // A launcher never leaves on Back: from a sub-screen it returns home, at home it does nothing.
                BackHandler { screen = Screen.HOME }
                when (screen) {
                    Screen.HOME -> HomeScreen(
                        onConnectJellyfin = { screen = Screen.CONNECT_JELLYFIN },
                        onConnectYouTube = { screen = Screen.CONNECT_YOUTUBE },
                        onOpenSettings = { screen = Screen.SETTINGS },
                        onAllApps = { screen = Screen.ALL_APPS },
                        focusDockSignal = focusDockSignal,
                        viewModel = homeViewModel,
                    )
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

    /** The Home key while the launcher is already in front: back to the dock. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        screen = Screen.HOME
        focusDockSignal++
    }
}
