package dev.cristian.tv.launcher.settings

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import dev.cristian.tv.launcher.admin.ADMIN_PORT
import dev.cristian.tv.launcher.launcherApp
import dev.cristian.tv.launcher.ui.Background
import dev.cristian.tv.launcher.ui.Dim
import dev.cristian.tv.launcher.ui.Foreground
import kotlinx.coroutines.launch
import java.net.Inet4Address

/** Where to find the settings page, its code, and the connections that can be undone from the TV. */
@Composable
fun LauncherSettingsScreen(onConnectYouTube: () -> Unit, onConnectJellyfin: () -> Unit) {
    val context = LocalContext.current
    val app = context.launcherApp
    val scope = rememberCoroutineScope()
    val settings by app.settings.settings.collectAsState(Settings())
    val jellyfinSession by app.jellyfin.session.collectAsState(null)
    val token by produceState("…") { value = app.settings.adminToken() }
    val address = remember { context.lanAddress() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize().background(Background).padding(start = 64.dp, top = 48.dp, end = 64.dp)) {
        Text("Configuración del inicio", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Foreground)
        Spacer(Modifier.height(20.dp))
        Text("En el navegador del celular o la compu, en la red de casa:", fontSize = 14.sp, color = Dim)
        // 10.0.2.x is the emulator's own NAT address: from the Mac it is reached through `adb forward`.
        val host = if (address?.startsWith("10.0.2.") == true) "localhost" else address ?: "<ip de la tele>"
        Text("http://$host:$ADMIN_PORT", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Foreground)
        Spacer(Modifier.height(8.dp))
        Text("Código", fontSize = 14.sp, color = Dim)
        Text(token, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Foreground, letterSpacing = 6.sp)
        Text(
            "Ahí se cargan el cliente de Google (YouTube), la clave de Seerr y los fijados.",
            fontSize = 13.sp, color = Dim,
        )

        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (settings.youtubeConnected) {
                Button(onClick = { scope.launch { app.youtubeAuth.signOut() } }, modifier = Modifier.focusRequester(first)) {
                    Text("Desconectar YouTube")
                }
            } else {
                Button(onClick = onConnectYouTube, modifier = Modifier.focusRequester(first)) {
                    Text(if (settings.youtubeConfigured) "Conectar YouTube" else "YouTube: falta el client ID")
                }
            }
            if (jellyfinSession != null) {
                Button(onClick = { scope.launch { app.jellyfin.disconnect() } }) { Text("Desconectar Jellyfin") }
            } else {
                Button(onClick = onConnectJellyfin) { Text("Conectar Jellyfin") }
            }
        }
    }
}

/** The TV's IPv4 address on the active network, for the settings page URL. */
private fun Context.lanAddress(): String? {
    val cm = getSystemService(ConnectivityManager::class.java) ?: return null
    val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
    return props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
}
