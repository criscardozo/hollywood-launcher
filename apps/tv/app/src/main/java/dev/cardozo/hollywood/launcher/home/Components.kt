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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
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

/** Focus, layout D of docs/mockups/foco.html: a pink ring a little away from the card, so the page's dark
 *  shows between them (readable on white banners too), and a violet glow around it. */
private val FocusPink = Color(0xFFF472B6)
private val FocusGlow = Color(0xFFA78BFA)

@Composable
private fun cardBorder() = CardDefaults.border(
    focusedBorder = Border(BorderStroke(3.dp, FocusPink), inset = FOCUS_GAP, shape = CardShape),
)

/**
 * A violet halo around a focused card, outside its focus ring: rounded rectangles that grow and fade, a
 * cheap stand-in for a blur (BlurMaskFilter draws nothing on the TV's GPU path, and tv-material's Glow is
 * a coloured shadow that barely shows on a dark page). Drawn by a box around the card, because the card's
 * own surface clips whatever its modifier draws.
 */
private fun Modifier.focusGlow(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.hasFocus }.drawBehind {
        if (!focused) return@drawBehind
        val layers = 9
        for (layer in 0 until layers) {
            val grow = (7 + layer * 2.2f).dp.toPx()
            val fade = 1f - layer / layers.toFloat()
            drawRoundRect(
                color = FocusGlow.copy(alpha = 0.32f * fade),
                topLeft = Offset(-grow, -grow),
                size = Size(size.width + 2 * grow, size.height + 2 * grow),
                cornerRadius = CornerRadius(grow + 8.dp.toPx()),
            )
        }
    }
}

/** The rest of a row while one of its cards has focus: a little dimmed, so the focused one stands out. */
const val UNFOCUSED_ALPHA = 0.7f

@OptIn(ExperimentalFoundationApi::class) // BringIntoViewRequester
@Composable
fun MediaCard(item: MediaItem, modifier: Modifier = Modifier, showing: Boolean = false, onClick: () -> Unit) {
    // Focus brings the card into view; this brings the title under it too, or the last row shows it cut off.
    val wholeCard = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Column(
        modifier
            .width(180.dp)
            .bringIntoViewRequester(wholeCard)
            .onFocusChanged { if (it.hasFocus) scope.launch { wholeCard.bringIntoView() } },
    ) {
        Box(Modifier.focusGlow()) {
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
        }
        Text(
            item.title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Foreground,
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
    Box(Modifier.focusGlow()) {
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
}

/** Marks the card the idle hero is showing; it fills up until the hero moves on to the next one. */
@Composable
private fun ShowingBar(modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(HERO_ROTATE_MS.toInt(), easing = LinearEasing)) }
    Box(modifier.fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.45f))) {
        Box(Modifier.fillMaxWidth(progress.value).fillMaxHeight().background(Color.White))
    }
}

@Composable
fun AppTile(app: TvApp, focused: Boolean, modifier: Modifier = Modifier, nameBelow: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.focusGlow()) {
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
    }
    // The name of the focused app; the space is always there so the row does not jump.
    if (nameBelow) Text(
        app.label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Foreground, maxLines = 1,
        modifier = Modifier.padding(top = 10.dp).alpha(if (focused) 1f else 0f),
    )
    }
}

@Composable
fun AllAppsTile(onClick: () -> Unit) {
    Box(Modifier.focusGlow()) {
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
}

@Composable
fun ActionCard(
    title: String,
    subtitle: String,
    colors: List<Color> = listOf(Color(0xFF6A2C91), Color(0xFF00A4DC)),
    onClick: () -> Unit,
) {
    Box(Modifier.focusGlow()) {
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
}

/** Top right: the weather (once a place is set on the settings page) and the time. Nothing to focus. */
@Composable
fun TopBar(weather: Weather?, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
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
fun SystemTile(label: String, symbol: String, onClick: () -> Unit) {
    Box(Modifier.focusGlow()) {
        Card(
            onClick = onClick,
            shape = CardDefaults.shape(CardShape),
            scale = CardDefaults.scale(focusedScale = 1.08f),
            border = cardBorder(),
            colors = CardDefaults.colors(containerColor = Color.White.copy(alpha = 0.10f)),
            modifier = Modifier.size(150.dp, 61.dp),
        ) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(symbol, fontSize = 15.sp, color = Foreground)
                Spacer(Modifier.width(8.dp))
                Text(label, fontSize = 12.sp, color = Foreground)
            }
        }
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
