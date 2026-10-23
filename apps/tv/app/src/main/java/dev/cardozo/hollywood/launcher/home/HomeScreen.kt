package dev.cardozo.hollywood.launcher.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.SingletonImageLoader
import coil3.compose.asPainter
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import dev.cardozo.hollywood.launcher.apps.TvApp
import dev.cardozo.hollywood.launcher.content.MediaItem
import dev.cardozo.hollywood.launcher.content.Source
import dev.cardozo.hollywood.launcher.content.open
import dev.cardozo.hollywood.launcher.jellyfin.Player
import dev.cardozo.hollywood.launcher.system.launch
import dev.cardozo.hollywood.launcher.ui.Background
import dev.cardozo.hollywood.launcher.ui.Dim
import dev.cardozo.hollywood.launcher.ui.Faint
import dev.cardozo.hollywood.launcher.ui.Foreground
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Left edge of the content; rows pad their own content so a focused, scaled card is not clipped. */
private val Edge = 48.dp

/**
 * Layout D from docs/mockups/launcher-2.html: a hero with the focused item's info, the top shelf of
 * the app focused in the dock, the dock, and rows below it. Designed at 960×540 dp (the TV's 1080p).
 */
@Composable
fun HomeScreen(
    onConnectJellyfin: () -> Unit,
    onConnectYouTube: () -> Unit,
    onOpenSettings: () -> Unit,
    onAllApps: () -> Unit,
    focusDockSignal: Int,
    viewModel: HomeViewModel = viewModel(),
) {
    val context = LocalContext.current
    val dock by viewModel.dock.collectAsState()
    val jellyfin by viewModel.jellyfin.collectAsState()
    val wallpaper by viewModel.wallpaper.collectAsState()
    val youtube by viewModel.youtube.collectAsState()
    val pinned by viewModel.pinned.collectAsState()
    val settings by viewModel.settings.collectAsState()

    // The shelf follows the app last focused in the dock; the hero follows whatever is focused.
    var shelfApp by remember { mutableStateOf<String?>(null) }
    var focusedItem by remember { mutableStateOf<MediaItem?>(null) }
    val shelf = viewModel.shelfFor(shelfApp, jellyfin, youtube, pinned)
    // With the dock focused, the hero shows the first item of that app's shelf (once it has loaded).
    val heroItem = focusedItem ?: (shelf as? Shelf.Items)?.items?.firstOrNull()
    val play: (MediaItem) -> Unit = { context.open(it, settings.player) }

    val dockFocus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Back on the shelf or the dock, the page returns to the top so the hero is visible again. The wait lets
    // the focus change's own bring-into-view scroll finish first; otherwise it wins and the page stays down.
    val toTop: () -> Unit = {
        scope.launch {
            delay(50)
            scroll.animateScrollTo(0)
        }
    }
    LaunchedEffect(dock.isNotEmpty(), focusDockSignal) {
        if (dock.isNotEmpty()) {
            runCatching { dockFocus.requestFocus() }
            // Focusing the dock scrolls it into view; Home should always show the page from the top.
            delay(50)
            scroll.scrollTo(0)
        }
    }

    Box(Modifier.fillMaxSize().background(Background)) {
        Backdrop(url = heroItem?.backdropUrl ?: wallpaper)
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Background.copy(alpha = 0.94f),
                    0.34f to Background.copy(alpha = 0.70f),
                    0.68f to Background.copy(alpha = 0.05f),
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Background.copy(alpha = 0.55f),
                    0.18f to Color.Transparent,
                    0.4f to Color.Transparent,
                    1f to Background.copy(alpha = 0.98f),
                ),
            ),
        )

        Column(
            Modifier.fillMaxSize().verticalScroll(scroll),
        ) {
            Hero(heroItem, Modifier.height(244.dp).padding(start = Edge, top = 56.dp))

            ShelfRow(
                shelf = shelf,
                onFocusItem = { focusedItem = it; toTop() },
                onPlay = play,
                onConnectJellyfin = onConnectJellyfin,
                onConnectYouTube = onConnectYouTube,
                onOpenSettings = onOpenSettings,
            )

            Spacer(Modifier.height(20.dp))
            Dock(
                apps = dock,
                firstFocus = dockFocus,
                onFocusApp = { app ->
                    toTop()
                    shelfApp = app.packageName
                    focusedItem = null
                },
                onOpen = { context.launch(it) },
                onAllApps = onAllApps,
            )

            // With a Jellyfin player in the dock, its shelf already offers to connect: one card is enough.
            if (jellyfin == JellyfinUi.NotConnected && dock.none { it.packageName in Player.packages }) {
                Spacer(Modifier.height(36.dp))
                Box(Modifier.padding(start = Edge)) {
                    ActionCard(
                    title = "Conectar Jellyfin",
                    subtitle = "Con un código de Quick Connect, sin escribir contraseñas",
                    onClick = onConnectJellyfin,
                    )
                }
            }
            val recent = (jellyfin as? JellyfinUi.Ready)?.home?.recentlyAdded.orEmpty()
            if (recent.isNotEmpty()) {
                Spacer(Modifier.height(36.dp))
                Box(Modifier.padding(start = Edge)) { RowTitle("Agregado recientemente", "Jellyfin") }
                PosterRow(recent, onFocusItem = { focusedItem = it }, onPlay = play)
            }
            // The first videos live in YouTube's shelf; without the YouTube app in the dock they all go here.
            val videos = (youtube as? YouTubeUi.Ready)?.videos.orEmpty()
            val youtubeInDock = dock.any { it.packageName == Source.YOUTUBE.packageName }
            val moreVideos = if (youtubeInDock) videos.drop(SHELF_SIZE) else videos
            if (moreVideos.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                Box(Modifier.padding(start = Edge)) {
                    RowTitle(if (youtubeInDock) "Más de tus suscripciones" else "Nuevo en tus suscripciones", "YouTube")
                }
                MediaRow(moreVideos, onFocusItem = { focusedItem = it }, onPlay = play)
            }
            if (pinned.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                Box(Modifier.padding(start = Edge)) { RowTitle("Fijados", "Prime Video · Disney+ · Max") }
                MediaRow(pinned, onFocusItem = { focusedItem = it }, onPlay = play)
            }
            Spacer(Modifier.height(48.dp))
        }

        TopBar(down = dockFocus, modifier = Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = 36.dp))
    }
}

@Composable
private fun Backdrop(url: String?) {
    val context = LocalContext.current
    // The next backdrop is loaded before it replaces the current one, so the crossfade goes from image to
    // image. Crossfading on the URL faded the old one out to black while the new one was still loading.
    var shown by remember { mutableStateOf<coil3.Image?>(null) }
    LaunchedEffect(url) {
        if (url == null) {
            shown = null
            return@LaunchedEffect
        }
        // Moving quickly along a row cancels this, so only the backdrop where the focus stops is loaded.
        delay(150)
        val result = SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(url).build())
        if (result is SuccessResult) shown = result.image
    }
    Crossfade(targetState = shown, animationSpec = tween(600), label = "backdrop") { current ->
        if (current != null) {
            Image(
                painter = remember(current) { current.asPainter(context) },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(Color(0xFF1B2A4A), Color(0xFF5B3A6B), Color(0xFF0D0B1A))),
                ),
            )
        }
    }
}

@Composable
private fun Hero(item: MediaItem?, modifier: Modifier = Modifier) {
    Column(modifier.width(440.dp)) {
        if (item == null) return@Column
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(item.source.color, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(item.source.label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = Foreground)
        }
        Text(
            item.title, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Foreground,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 42.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
        )
        // Movies use the year as subtitle, which meta already starts with.
        val subtitle = item.subtitle?.takeUnless { item.meta.startsWith(it) }
        val meta = listOfNotNull(subtitle, item.meta.ifBlank { null }).joinToString(" · ")
        Text(meta, fontSize = 13.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        item.overview?.let {
            Text(
                it, fontSize = 13.sp, color = Dim, maxLines = 2, overflow = TextOverflow.Ellipsis,
                lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (item.progress != null) {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                ProgressBar(item.progress, Modifier.width(120.dp))
                Spacer(Modifier.width(12.dp))
                Text(item.remaining.orEmpty(), fontSize = 12.sp, color = Dim)
            }
        }
    }
}

@Composable
private fun ShelfRow(
    shelf: Shelf,
    onFocusItem: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onConnectJellyfin: () -> Unit,
    onConnectYouTube: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(Modifier.height(168.dp).fillMaxWidth()) {
        when (shelf) {
            is Shelf.Items -> MediaRow(shelf.items, onFocusItem, onPlay)
            Shelf.ConnectJellyfin -> Box(Modifier.padding(start = Edge, top = 8.dp)) {
                ActionCard(
                    title = "Conectar Jellyfin",
                    subtitle = "Con un código de Quick Connect, sin escribir contraseñas",
                    onClick = onConnectJellyfin,
                )
            }
            Shelf.ConnectYouTube -> Box(Modifier.padding(start = Edge, top = 8.dp)) {
                ActionCard(
                    title = "Conectar YouTube",
                    subtitle = "Los videos nuevos de tus suscripciones, con un código en google.com/device",
                    colors = listOf(Color(0xFF8B0000), Color(0xFFFF2D2D)),
                    onClick = onConnectYouTube,
                )
            }
            is Shelf.NothingPinned -> Box(Modifier.padding(start = Edge, top = 8.dp)) {
                ActionCard(
                    title = "Nada fijado de ${shelf.source.label}",
                    subtitle = "${shelf.source.label} no comparte qué estás viendo. Fijá series desde la página de configuración.",
                    colors = listOf(Color(0xFF1B2433), shelf.source.color),
                    onClick = onOpenSettings,
                )
            }
            Shelf.Empty -> Unit
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MediaRow(items: List<MediaItem>, onFocusItem: (MediaItem) -> Unit, onPlay: (MediaItem) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(horizontal = Edge, vertical = 8.dp),
        modifier = Modifier.focusRestorer(),
    ) {
        items(items, key = { it.id }) { item ->
            MediaCard(item, Modifier.onFocusChanged { if (it.isFocused) onFocusItem(item) }) { onPlay(item) }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Dock(
    apps: List<TvApp>,
    firstFocus: FocusRequester,
    onFocusApp: (TvApp) -> Unit,
    onOpen: (TvApp) -> Unit,
    onAllApps: () -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        contentPadding = PaddingValues(horizontal = Edge, vertical = 8.dp),
        modifier = Modifier.focusRestorer(firstFocus),
    ) {
        items(apps, key = { it.packageName }) { app ->
            val first = app == apps.first()
            AppTile(
                app,
                Modifier
                    .then(if (first) Modifier.focusRequester(firstFocus) else Modifier)
                    .onFocusChanged { if (it.isFocused) onFocusApp(app) },
            ) { onOpen(app) }
        }
        item { AllAppsTile(onAllApps) }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PosterRow(items: List<MediaItem>, onFocusItem: (MediaItem) -> Unit, onPlay: (MediaItem) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        contentPadding = PaddingValues(horizontal = Edge, vertical = 8.dp),
        modifier = Modifier.focusRestorer(),
    ) {
        items(items, key = { it.id }) { item ->
            PosterCard(item, Modifier.onFocusChanged { if (it.isFocused) onFocusItem(item) }) { onPlay(item) }
        }
    }
}

@Composable
fun RowTitle(title: String, note: String? = null) {
    Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Foreground)
        if (note != null) {
            Spacer(Modifier.width(6.dp))
            Text(note, fontSize = 10.sp, color = Faint)
        }
    }
}

@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(3.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(2.dp))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(Color.White, RoundedCornerShape(2.dp)))
    }
}
