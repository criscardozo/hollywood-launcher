package dev.cardozo.hollywood.launcher.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import dev.cardozo.hollywood.launcher.system.openSettings
import dev.cardozo.hollywood.launcher.system.rebootTv
import dev.cardozo.hollywood.launcher.ui.Dim
import dev.cardozo.hollywood.launcher.ui.Foreground
import kotlinx.coroutines.launch

private sealed interface RebootState {
    data object Asking : RebootState
    data object Rebooting : RebootState
    data class Failed(val reason: String) : RebootState
}

/** «Reiniciar» in the TV row: asks first, since it cuts whatever is playing for about a minute. */
@Composable
fun RebootDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<RebootState>(RebootState.Asking) }
    val first = remember { FocusRequester() }
    LaunchedEffect(state) { runCatching { first.requestFocus() } }

    // Back always closes it, even while waiting on the TV's prompt.
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(440.dp).background(Color(0xFF15171C), RoundedCornerShape(16.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (val s = state) {
                RebootState.Asking -> {
                    Text("¿Reiniciar la tele?", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Foreground)
                    Text(
                        "Se corta lo que esté sonando y vuelve en un minuto, más liviana. La primera vez la tele " +
                            "pregunta si permite la depuración: marcá «Permitir siempre» y elegí «Permitir».",
                        fontSize = 12.sp, color = Dim, lineHeight = 17.sp,
                    )
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                        SystemTile("Reiniciar", "↻", modifier = Modifier.focusRequester(first)) {
                            state = RebootState.Rebooting
                            scope.launch {
                                // On success the TV goes down before anything comes back.
                                context.rebootTv().onFailure { state = RebootState.Failed(it.message ?: it.javaClass.simpleName) }
                            }
                        }
                        SystemTile("Cancelar", onClick = onDismiss)
                    }
                }
                RebootState.Rebooting -> {
                    Text("Reiniciando…", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Foreground)
                    Text(
                        "Si la tele pregunta si permite la depuración, marcá «Permitir siempre» y elegí «Permitir» " +
                            "(está a la izquierda de «Cancelar»).",
                        fontSize = 12.sp, color = Dim, lineHeight = 17.sp,
                    )
                }
                is RebootState.Failed -> {
                    Text("No se pudo reiniciar", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Foreground)
                    Text(
                        "${s.reason}. También se puede desde Ajustes → Sistema → Reiniciar.",
                        fontSize = 12.sp, color = Dim, lineHeight = 17.sp,
                    )
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                        SystemTile("Ajustes", "⚙", modifier = Modifier.focusRequester(first)) {
                            onDismiss()
                            context.openSettings()
                        }
                        SystemTile("Cerrar", onClick = onDismiss)
                    }
                }
            }
        }
    }
}
