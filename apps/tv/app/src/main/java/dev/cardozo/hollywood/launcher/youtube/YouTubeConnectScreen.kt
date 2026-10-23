package dev.cristian.tv.launcher.youtube

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import dev.cristian.tv.launcher.launcherApp
import dev.cristian.tv.launcher.ui.Background
import dev.cristian.tv.launcher.ui.Dim
import dev.cristian.tv.launcher.ui.Foreground
import kotlinx.coroutines.flow.catch

/** Google's TV sign-in: a code to type at google.com/device on the phone. */
@Composable
fun YouTubeConnectScreen(onDone: () -> Unit) {
    val auth = LocalContext.current.launcherApp.youtubeAuth
    var state by remember { mutableStateOf<YouTubeSignIn?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        auth.signIn()
            .catch { state = YouTubeSignIn.Failed(it.message ?: it.toString()) }
            .collect {
                state = it
                if (it is YouTubeSignIn.Connected) onDone()
            }
    }

    Column(Modifier.fillMaxSize().background(Background).padding(start = 64.dp, top = 56.dp, end = 64.dp)) {
        Text("Conectar YouTube", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Foreground)
        Spacer(Modifier.height(24.dp))
        when (val s = state) {
            null -> Text("Pidiendo un código a Google…", fontSize = 14.sp, color = Dim)
            is YouTubeSignIn.WaitingForCode -> {
                Text("En el celular, abrí", fontSize = 14.sp, color = Dim)
                Text(s.url.removePrefix("https://"), fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Foreground)
                Spacer(Modifier.height(16.dp))
                Text("y escribí este código", fontSize = 14.sp, color = Dim)
                Text(s.code, fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Foreground, letterSpacing = 6.sp)
                Spacer(Modifier.height(12.dp))
                Text("Solo pide permiso de lectura: suscripciones y videos.", fontSize = 13.sp, color = Dim)
            }
            is YouTubeSignIn.Failed -> {
                Text(s.reason, fontSize = 14.sp, color = Color(0xFFFF8A8A))
                Spacer(Modifier.height(12.dp))
                Button(onClick = { state = null; attempt++ }) { Text("Probar de nuevo") }
            }
            YouTubeSignIn.Connected -> Text("Listo.", fontSize = 16.sp, color = Foreground)
        }
    }
}
