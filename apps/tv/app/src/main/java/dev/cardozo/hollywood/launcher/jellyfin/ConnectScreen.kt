package dev.cardozo.hollywood.launcher.jellyfin

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import dev.cardozo.hollywood.launcher.launcherApp
import dev.cardozo.hollywood.launcher.ui.Background
import dev.cardozo.hollywood.launcher.ui.Dim
import dev.cardozo.hollywood.launcher.ui.Foreground
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withTimeoutOrNull

/** Find the server, show a Quick Connect code, wait for it to be approved from the phone or the web. */
@Composable
fun ConnectScreen(onDone: () -> Unit) {
    val repo = LocalContext.current.launcherApp.jellyfin
    val servers = remember { mutableStateListOf<DiscoveredServer>() }
    var searching by remember { mutableStateOf(true) }
    var address by remember { mutableStateOf("http://") }
    var target by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf<QuickConnectState?>(null) }

    LaunchedEffect(Unit) {
        withTimeoutOrNull(4_000) {
            repo.discover().catch { }.collect { if (servers.none { s -> s.address == it.address }) servers += it }
        }
        searching = false
    }
    LaunchedEffect(target) {
        val url = target ?: return@LaunchedEffect
        repo.quickConnect(url)
            .catch {
                Log.w("ConnectScreen", "Quick Connect failed", it)
                state = QuickConnectState.Failed(it.toString())
            }
            .collect {
                state = it
                if (it is QuickConnectState.Connected) onDone()
            }
    }

    Column(Modifier.fillMaxSize().background(Background).padding(start = 64.dp, top = 56.dp, end = 64.dp)) {
        Text("Conectar Jellyfin", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Foreground)
        Spacer(Modifier.height(24.dp))

        when (val s = state) {
            is QuickConnectState.WaitingForApproval -> {
                Text("Código de Quick Connect", fontSize = 14.sp, color = Dim)
                Text(s.code, fontSize = 64.sp, fontWeight = FontWeight.Bold, color = Foreground, letterSpacing = 8.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    "En el celular o en la web de Jellyfin: tu usuario → Quick Connect → escribí este código.",
                    fontSize = 14.sp, color = Dim,
                )
            }
            is QuickConnectState.Failed -> {
                Text(s.reason, fontSize = 14.sp, color = Color(0xFFFF8A8A))
                Spacer(Modifier.height(12.dp))
                Button(onClick = { state = null; target = null }) { Text("Probar de nuevo") }
            }
            QuickConnectState.Connected -> Text("Listo.", fontSize = 16.sp, color = Foreground)
            null -> ServerPicker(
                servers = servers,
                searching = searching,
                address = address,
                onAddressChange = { address = it },
                onPick = { target = it.trimEnd('/') },
            )
        }
    }
}

@Composable
private fun ServerPicker(
    servers: List<DiscoveredServer>,
    searching: Boolean,
    address: String,
    onAddressChange: (String) -> Unit,
    onPick: (String) -> Unit,
) {
    Text(if (searching) "Buscando servidores en la red…" else "Servidores encontrados", fontSize = 14.sp, color = Dim)
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        servers.forEach { server ->
            Button(onClick = { onPick(server.address) }) { Text("${server.name} · ${server.address}") }
        }
        if (!searching && servers.isEmpty()) Text("Ninguno (la búsqueda no cruza el emulador).", fontSize = 13.sp, color = Dim)
    }
    Spacer(Modifier.height(28.dp))
    Text("O escribí la dirección", fontSize = 14.sp, color = Dim)
    Spacer(Modifier.height(8.dp))
    var focused by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicTextField(
            value = address,
            onValueChange = onAddressChange,
            singleLine = true,
            textStyle = TextStyle(color = Foreground, fontSize = 16.sp),
            cursorBrush = SolidColor(Foreground),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onPick(address) }),
            modifier = Modifier
                .width(360.dp)
                .onFocusChanged { focused = it.isFocused }
                .border(BorderStroke(2.dp, if (focused) Color.White else Dim), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        Button(onClick = { onPick(address) }) { Text("Conectar") }
    }
}
