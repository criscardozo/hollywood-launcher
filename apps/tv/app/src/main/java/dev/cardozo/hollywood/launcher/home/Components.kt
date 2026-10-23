package dev.cristian.tv.launcher.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.cristian.tv.launcher.apps.TvApp
import dev.cristian.tv.launcher.content.MediaItem
import dev.cristian.tv.launcher.system.openInputs
import dev.cristian.tv.launcher.system.openSettings
import dev.cristian.tv.launcher.ui.Dim
import dev.cristian.tv.launcher.ui.Foreground
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val CardShape = RoundedCornerShape(8.dp)

@Composable
private fun cardBorder() = CardDefaults.border(
    focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = CardShape),
)

@Composable
fun MediaCard(item: MediaItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.width(180.dp)) {
        Card(
            onClick = onClick,
            shape = CardDefaults.shape(CardShape),
            scale = CardDefaults.scale(focusedScale = 1.06f),
            border = cardBorder(),
            colors = CardDefaults.colors(containerColor = Color(0xFF1A1C22)),
            modifier = Modifier.size(180.dp, 101.dp),
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = item.cardImageUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (item.progress != null) {
                    ProgressBar(item.progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp))
                }
                item.duration?.let {
                    Text(
                        it, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                            .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
        }
        Text(
            item.title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Foreground,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
        )
        item.subtitle?.let {
            Text(it, fontSize = 9.5.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun PosterCard(item: MediaItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(CardShape),
        scale = CardDefaults.scale(focusedScale = 1.06f),
        border = cardBorder(),
        colors = CardDefaults.colors(containerColor = Color(0xFF1A1C22)),
        modifier = modifier.size(98.dp, 147.dp),
    ) {
        AsyncImage(
            model = item.posterUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun AppTile(app: TvApp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(CardShape),
        scale = CardDefaults.scale(focusedScale = 1.08f),
        border = cardBorder(),
        colors = CardDefaults.colors(containerColor = Color(0xFF1A1C22)),
        modifier = modifier.size(108.dp, 61.dp),
    ) {
        Image(
            bitmap = app.art,
            contentDescription = app.label,
            contentScale = if (app.hasBanner) ContentScale.Crop else ContentScale.Fit,
            modifier = Modifier.fillMaxSize().then(if (app.hasBanner) Modifier else Modifier.padding(10.dp)),
        )
    }
}

@Composable
fun AllAppsTile(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(CardShape),
        scale = CardDefaults.scale(focusedScale = 1.08f),
        border = cardBorder(),
        colors = CardDefaults.colors(containerColor = Color.White.copy(alpha = 0.10f)),
        modifier = Modifier.size(108.dp, 61.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Todas las apps", fontSize = 11.sp, color = Foreground)
        }
    }
}

@Composable
fun ActionCard(
    title: String,
    subtitle: String,
    colors: List<Color> = listOf(Color(0xFF6A2C91), Color(0xFF00A4DC)),
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(CardShape),
        scale = CardDefaults.scale(focusedScale = 1.04f),
        border = cardBorder(),
        colors = CardDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.size(380.dp, 101.dp),
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(Brush.linearGradient(colors))
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(subtitle, fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

@Composable
fun TopBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill("Entradas") { context.openInputs() }
        Pill("Ajustes") { context.openSettings() }
        Clock()
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.12f),
            contentColor = Dim,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF111111),
        ),
    ) {
        Text(label, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

private val ClockFormat = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun Clock(fontSize: Int = 17) {
    val time by produceState(LocalTime.now().format(ClockFormat)) {
        while (true) {
            value = LocalTime.now().format(ClockFormat)
            delay(1_000)
        }
    }
    Text(time, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold, color = Foreground)
}
