package dev.cristian.tv.launcher.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import dev.cristian.tv.launcher.system.launch
import dev.cristian.tv.launcher.ui.Background
import dev.cristian.tv.launcher.ui.Dim
import dev.cristian.tv.launcher.ui.Foreground

@Composable
fun AllAppsScreen(onOpenSettings: () -> Unit, viewModel: HomeViewModel = viewModel()) {
    val context = LocalContext.current
    val apps by viewModel.allApps.collectAsState()
    val first = remember { FocusRequester() }
    LaunchedEffect(apps.isNotEmpty()) { if (apps.isNotEmpty()) runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize().background(Background).padding(start = 48.dp, top = 36.dp, end = 48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Todas las apps", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Foreground)
                Text("${apps.size} instaladas", fontSize = 12.sp, color = Dim)
            }
            Button(onClick = onOpenSettings) { Text("Configuración del inicio") }
        }
        Spacer(Modifier.height(20.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 36.dp, top = 6.dp),
        ) {
            itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                Column(Modifier.width(120.dp)) {
                    AppTile(app, if (index == 0) Modifier.focusRequester(first) else Modifier) { context.launch(app) }
                    Text(
                        app.label, fontSize = 10.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
