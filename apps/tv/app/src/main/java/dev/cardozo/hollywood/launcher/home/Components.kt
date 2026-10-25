package dev.cardozo.hollywood.launcher.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.cardozo.hollywood.launcher.apps.TvApp
import dev.cardozo.hollywood.launcher.content.MediaItem
import dev.cardozo.hollywood.launcher.ui.Dim
import dev.cardozo.hollywood.launcher.weather.Weather
import dev.cardozo.hollywood.launcher.ui.Foreground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val CardShape = RoundedCornerShape(8.dp)
/** How far the focus ring sits from the card. */
private val FOCUS_GAP = 3.dp

/** Focus, layout B of docs/mockups/foco.html: a white ring a little away from the card, so the page's dark
 *  shows between them (readable on white banners too), and the rest of the row dimmed. */

@Composable
private fun cardBorder() = CardDefaults.border(
    focusedBorder = Border(BorderStroke(3.dp, Color.White), inset = FOCUS_GAP, shape = CardShape),
)

/** The rest of a row while one of its cards has focus: a little dimmed, so the focused one stands out. */
const val UNFOCUSED_ALPHA = 0.45f

@OptIn(ExperimentalFoundationApi::class) // BringIntoViewRequester
@Composable
fun MediaCard(item: MediaItem, modifier: Modifier = Modifier, showing: Boolean = false, onClick: () -> Unit) {
    // Focus brings the card into view; this brings the title under it too, or the last row shows it cut off.
    val wholeCard = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier
            .width(180.dp)
            .bringIntoViewRequester(wholeCard)
            .onFocusChanged {
                focused = it.hasFocus
                if (it.hasFocus) scope.launch { wholeCard.bringIntoView() }
            },
    ) {
        Card(
            onClick = onClick,
            shape = CardDefaults.shape(CardShape),
            scale = CardDefaults.scale(focusedScale = 1.08f),
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
                if (showing) ShowingBar(Modifier.align(Alignment.TopCenter))
                item.newsChannel?.let { channel ->
                    Text(
                        channel.label, fontSize = 9.sp, fontWeight = FontWeight.Black, color = channel.foreground,
                        modifier = Modifier.align(Alignment.TopStart).padding(5.dp)
                            .background(channel.background, RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
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
            // The focused card's title a little larger, so it reads as the one selected.
            item.title, fontSize = if (focused) 12.5.sp else 11.sp, fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold, color = Foreground,
            // Clear of the focus ring, which sits outside the scaled card.
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 11.dp),
        )
        item.subtitle?.let {
            Text(it, fontSize = 9.5.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class) // BringIntoViewRequester
@Composable
fun PosterCard(item: MediaItem, modifier: Modifier = Modifier, showing: Boolean = false, onClick: () -> Unit) {
    // With its margin, so the scaled card and its focus ring are not cut off at the bottom of the screen.
    val withMargin = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .bringIntoViewRequester(withMargin)
            .onFocusChanged { if (it.hasFocus) scope.launch { withMargin.bringIntoView() } }
            .padding(vertical = 12.dp),
    ) {
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(CardShape),
        scale = CardDefaults.scale(focusedScale = 1.08f),
        border = cardBorder(),
        colors = CardDefaults.colors(containerColor = Color(0xFF1A1C22)),
        modifier = Modifier.size(98.dp, 147.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = item.posterUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (showing) ShowingBar(Modifier.align(Alignment.TopCenter))
        }
    }
    }
}

/** Marks the card the idle hero is showing; it fills up until the hero moves on to the next one. */
@Composable
private fun ShowingBar(modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(HERO_ROTATE_MS.toInt(), easing = LinearEasing)) }
    // The fill is drawn, not laid out: reading the animation only while drawing keeps it from recomposing
    // and measuring every frame for as long as Home sits idle.
    Box(
        modifier.fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.45f)).drawBehind {
            drawRect(Color.White, size = size.copy(width = size.width * progress.value))
        },
    )
}

@Composable
fun AppTile(app: TvApp, focused: Boolean, modifier: Modifier = Modifier, nameBelow: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(CardShape),
        scale = CardDefaults.scale(focusedScale = 1.12f),
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
    // The name of the focused app; the space is always there so the row does not jump.
    if (nameBelow) Text(
        app.label, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Foreground, maxLines = 1,
        modifier = Modifier.padding(top = 10.dp).alpha(if (focused) 1f else 0f),
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

/** Top right: the weather (once a place is set on the settings page) and the time. Nothing to focus. */
@Composable
fun TopBar(weather: Weather?, notice: String?, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        // A server that does not answer (Ginebra off): said once, quietly, next to the time.
        if (notice != null) {
            Text(
                "⚠  $notice", fontSize = 11.sp, color = Dim,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
        if (weather != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(weather.symbol, fontSize = 17.sp)
                Spacer(Modifier.width(5.dp))
                Text("${weather.temperature}°", fontSize = 19.sp, fontWeight = FontWeight.Medium, color = Foreground)
            }
        }
        Clock(fontSize = 24)
    }
}

/** A tile in the last row of Home, for the TV's own screens (inputs, settings). */
@Composable
fun SystemTile(label: String, symbol: String? = null, icon: Painter? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(CardShape),
        scale = CardDefaults.scale(focusedScale = 1.08f),
        border = cardBorder(),
        colors = CardDefaults.colors(containerColor = Color.White.copy(alpha = 0.10f)),
        modifier = modifier.size(150.dp, 61.dp),
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            if (symbol != null) Text(symbol, fontSize = 15.sp, color = Foreground)
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 12.sp, color = Foreground)
        }
    }
}

private val ClockFormat = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun Clock(fontSize: Int = 17) {
    // Wakes once a minute, on the minute, rather than every second for as long as the launcher lives.
    val time by produceState(LocalTime.now().format(ClockFormat)) {
        while (true) {
            value = LocalTime.now().format(ClockFormat)
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
        }
    }
    Text(time, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold, color = Foreground)
}
