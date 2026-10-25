package dev.cardozo.hollywood.launcher.home

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.SingletonImageLoader
import coil3.compose.asPainter
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import dev.cardozo.hollywood.launcher.R
import dev.cardozo.hollywood.launcher.apps.TvApp
import dev.cardozo.hollywood.launcher.content.MediaItem
import dev.cardozo.hollywood.launcher.content.Source
import dev.cardozo.hollywood.launcher.content.open
import dev.cardozo.hollywood.launcher.jellyfin.Player
import dev.cardozo.hollywood.launcher.news.NewsChannel
import dev.cardozo.hollywood.launcher.system.launch
import dev.cardozo.hollywood.launcher.system.isDefaultHome
import dev.cardozo.hollywood.launcher.system.openHomeSettings
import dev.cardozo.hollywood.launcher.system.PLAYSTATION_INPUT
import dev.cardozo.hollywood.launcher.system.openInput
import dev.cardozo.hollywood.launcher.system.openInputs
import dev.cardozo.hollywood.launcher.system.openSettings
import dev.cardozo.hollywood.launcher.system.requestDefaultHomeIntent
import dev.cardozo.hollywood.launcher.ui.Background
import dev.cardozo.hollywood.launcher.ui.Dim
import dev.cardozo.hollywood.launcher.ui.Faint
import dev.cardozo.hollywood.launcher.ui.Foreground
import androidx.tv.material3.Text
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Left edge of the content; rows pad their own content so a focused, scaled card is not clipped. */
private val Edge = 48.dp

/** With no key pressed for this long, the hero starts walking the focused row on its own. */
private const val IDLE_BEFORE_ROTATING_MS = 10_000L
private val Arrows = setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)

/** Which row has focus; with the id of the focused card, what the hero shows. */
private enum class RowKey { SHELF, NEWS, RECENT, VIDEOS, PINNED, OTHER }

private data class Focused(val row: RowKey, val id: String)

/** A role dialog «answered» faster than this was never shown (Google TV declined it). */
private const val DIALOG_NOT_SHOWN_MS = 700L

/** How often Home asks Jellyfin's server again while it does not answer. */
private const val JELLYFIN_RETRY_MS = 2 * 60_000L

/** How long the idle hero stays on each item. */
const val HERO_ROTATE_MS = 8_000L

/** The interface's own default: the smallest scroll that makes the focused item fully visible. */
internal val ScrollOnlyWhenHidden = object : BringIntoViewSpec {}

/**
 * Layout D from docs/mockups/launcher-2.html: a hero with the focused item's info, the top shelf of
 * the app focused in the dock, the dock, and rows below it. Designed at 960×540 dp (the TV's 1080p).
 */
@OptIn(ExperimentalFoundationApi::class) // LocalBringIntoViewSpec
@Composable
fun HomeScreen(
    onConnectJellyfin: () -> Unit,
    onConnectYouTube: () -> Unit,
    onOpenSettings: () -> Unit,
    onAllApps: () -> Unit,
    onOpenDetails: (MediaItem) -> Unit,
    focusDockSignal: Int,
    /** False while a page (details) is drawn over Home: the idle hero pauses, and focus returns on true. */
    active: Boolean = true,
    viewModel: HomeViewModel = viewModel(),
) {
    val context = LocalContext.current
    val dock by viewModel.dock.collectAsStateWithLifecycle()
    val jellyfin by viewModel.jellyfin.collectAsStateWithLifecycle()
    val wallpaper by viewModel.wallpaper.collectAsStateWithLifecycle()
    val youtube by viewModel.youtube.collectAsStateWithLifecycle()
    val pinned by viewModel.pinned.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val weather by viewModel.weather.collectAsStateWithLifecycle()
    val news by viewModel.news.collectAsStateWithLifecycle()

    // The shelf follows the app last focused in the dock; the hero follows whatever is focused.
    var shelfApp by remember { mutableStateOf<String?>(null) }
    val shelf = viewModel.shelfFor(shelfApp, jellyfin, youtube, pinned)
    val shelfItems = (shelf as? Shelf.Items)?.items.orEmpty()
    val recent = (jellyfin as? JellyfinUi.Ready)?.home?.recentlyAdded.orEmpty()
    // The first videos live in YouTube's shelf; without the YouTube app in the dock they all go here.
    val videos = (youtube as? YouTubeUi.Ready)?.videos.orEmpty()
    val youtubeInDock = dock.any { it.packageName == Source.YOUTUBE.packageName }
    val moreVideos = if (youtubeInDock) videos.drop(SHELF_SIZE) else videos

    // What has focus, as a row and an id, never a copy of the item: the lists are replaced when Home
    // refreshes (coming back from a player) while focus stays put, and the hero must show the new data.
    var focus by remember { mutableStateOf<Focused?>(null) }
    fun rowOf(key: RowKey): List<MediaItem> = when (key) {
        RowKey.SHELF -> shelfItems
        RowKey.NEWS -> news
        RowKey.RECENT -> recent
        RowKey.VIDEOS -> moreVideos
        RowKey.PINNED -> pinned
        RowKey.OTHER -> emptyList()
    }
    val focusedItem = focus?.let { f -> rowOf(f.row).firstOrNull { it.id == f.id } }
    // The row the hero walks: the focused one, or with the dock focused, that app's shelf. None while focus is
    // on something that is not a card (the TV row, a connect card).
    val row = focus?.let { rowOf(it.row) } ?: shelfItems

    // Left alone, the hero walks the row so the screen has some movement. Focus never moves, so OK still
    // opens what was chosen; any key brings the hero back. Paused while Home is not on screen.
    var keyPresses by remember { mutableIntStateOf(0) }
    var showing by remember { mutableStateOf<MediaItem?>(null) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val onScreen = active && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    // Jellyfin's server (Ginebra) off: say so, keep the last home on screen, and try again every two minutes.
    val jellyfinOffline = jellyfin is JellyfinUi.Failed || (jellyfin as? JellyfinUi.Ready)?.stale == true
    val jellyfinNotice = when {
        jellyfin is JellyfinUi.Failed -> "Jellyfin no responde"
        jellyfinOffline -> "${(jellyfin as JellyfinUi.Ready).home.serverName ?: "Jellyfin"} no responde · mostrando lo último"
        else -> null
    }
    LaunchedEffect(onScreen, jellyfinOffline) {
        while (onScreen && jellyfinOffline) {
            delay(JELLYFIN_RETRY_MS)
            viewModel.retryJellyfin()
        }
    }
    // The weather refreshes itself (at most every half hour) only while Home is on screen.
    LaunchedEffect(onScreen) {
        while (onScreen) {
            viewModel.refreshWeather()
            delay(WEATHER_EVERY_MS)
        }
    }
    LaunchedEffect(onScreen) {
        while (onScreen) {
            viewModel.refreshNews()
            // Nothing loaded yet (the network was not up at boot): try again soon rather than in 20 minutes.
            delay(if (news.isEmpty()) JELLYFIN_RETRY_MS else NEWS_EVERY_MS)
        }
    }
    LaunchedEffect(keyPresses, row, focusedItem, onScreen) {
        showing = null
        if (!onScreen || row.size < 2) return@LaunchedEffect
        delay(IDLE_BEFORE_ROTATING_MS)
        var index = row.indexOf(focusedItem ?: row.first()).coerceAtLeast(0)
        while (true) {
            index = (index + 1) % row.size
            showing = row[index]
            delay(HERO_ROTATE_MS)
        }
    }

    // With the dock focused, the hero shows the first item of that app's shelf (once it has loaded).
    val heroItem = showing ?: focusedItem ?: shelfItems.firstOrNull()
    // Films and series open their details page first; a video plays straight away.
    val play: (MediaItem) -> Unit = {
        if (it.source == Source.YOUTUBE) context.open(it, settings.player) else onOpenDetails(it)
    }

    val dockFocus = remember { FocusRequester() }
    // Back from details: the restorer puts focus on the card that opened it.
    val pageFocus = remember { FocusRequester() }
    val tvRow = remember { BringIntoViewRequester() }
    // «Usar como inicio» shows until Hollywood holds the HOME role; checked again whenever Home comes back.
    var isDefaultHome by remember { mutableStateOf(context.isDefaultHome()) }
    LaunchedEffect(onScreen) { if (onScreen) isDefaultHome = context.isDefaultHome() }
    val settingsTile = remember { FocusRequester() }
    var askedAt by remember { mutableLongStateOf(0L) }
    val askForHome = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefaultHome = context.isDefaultHome()
        // The tile that had focus is gone now: hand focus to its neighbour rather than lose it.
        if (isDefaultHome) runCatching { settingsTile.requestFocus() }
        // Google TV may turn the dialog down without showing it, which comes back at once: then the Home app
        // setting. A «Cancel» the user chose takes longer and is respected.
        else if (it.resultCode != Activity.RESULT_OK && System.currentTimeMillis() - askedAt < DIALOG_NOT_SHOWN_MS) {
            context.openHomeSettings()
        }
    }
    var wasActive by remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (active && !wasActive) runCatching { pageFocus.requestFocus() }
        wasActive = active
    }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Back on the shelf or the dock, the page returns to the top so the hero is visible again. The wait lets
    // the focus change's own bring-into-view scroll finish first; otherwise it wins and the page stays down.
    // Focus moving on to a lower row cancels it, or it would scroll that row off screen.
    var toTopJob by remember { mutableStateOf<Job?>(null) }
    val toTop: () -> Unit = {
        toTopJob?.cancel()
        toTopJob = scope.launch {
            delay(50)
            scroll.animateScrollTo(0)
        }
    }
    val focusBelow: (RowKey, MediaItem) -> Unit = { key, item ->
        toTopJob?.cancel()
        focus = Focused(key, item.id)
    }
    LaunchedEffect(dock.isNotEmpty(), focusDockSignal) {
        if (dock.isNotEmpty()) {
            runCatching { dockFocus.requestFocus() }
            // Focusing the dock scrolls it into view; Home should always show the page from the top.
            delay(50)
            scroll.scrollTo(0)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Background)
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val wakingUp = showing != null && it.key in Arrows
                keyPresses++
                // While the hero walks the row, the focused card may be scrolled out of sight: the first arrow
                // only stops the walk and brings the row back to it, instead of moving focus from where
                // nobody can see it. OK still opens the focused card.
                wakingUp
            },
    ) {
        // Not every YouTube video has a maxres thumbnail: the card's image stands in for a missing backdrop.
        val backdrops = listOfNotNull(heroItem?.backdropUrl, heroItem?.cardImageUrl).ifEmpty { listOfNotNull(wallpaper) }
        Backdrop(backdrops)
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

        // On TV devices Compose scrolls every focus change to a fixed pivot. That made the rows slide on each
        // key press and the page dip down before toTop() pulled it back: scroll only when something is hidden.
        CompositionLocalProvider(LocalBringIntoViewSpec provides ScrollOnlyWhenHidden) {
            Column(
                Modifier.fillMaxSize().focusRequester(pageFocus).focusRestorer().verticalScroll(scroll),
            ) {
                Hero(heroItem, Modifier.height(244.dp).padding(start = Edge, top = 56.dp))

                ShelfRow(
                    shelf = shelf,
                    showing = showing,
                    onFocusItem = { focus = Focused(RowKey.SHELF, it.id); toTop() },
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
                        focus = null
                    },
                    onOpen = { context.launch(it) },
                    onAllApps = onAllApps,
                )

                if (news.isNotEmpty()) {
                    Spacer(Modifier.height(36.dp))
                    Box(Modifier.padding(start = Edge)) { RowTitle("Noticias", NewsChannel.entries.joinToString(" · ") { it.label }) }
                    MediaRow(news, showing, onFocusItem = { focusBelow(RowKey.NEWS, it) }, onPlay = play)
                }
                // With a Jellyfin player in the dock, its shelf already offers to connect: one card is enough.
                if (jellyfin == JellyfinUi.NotConnected && dock.none { it.packageName in Player.packages }) {
                    Spacer(Modifier.height(36.dp))
                    Box(Modifier.padding(start = Edge).onFocusChanged { if (it.hasFocus) focus = Focused(RowKey.OTHER, "") }) {
                        ActionCard(
                        title = "Conectar Jellyfin",
                        subtitle = "Con un código de Quick Connect, sin escribir contraseñas",
                        onClick = onConnectJellyfin,
                        )
                    }
                }
                if (recent.isNotEmpty()) {
                    Spacer(Modifier.height(36.dp))
                    Box(Modifier.padding(start = Edge)) { RowTitle("Agregado recientemente", "Jellyfin") }
                    PosterRow(recent, showing, onFocusItem = { focusBelow(RowKey.RECENT, it) }, onPlay = play)
                }
                if (moreVideos.isNotEmpty()) {
                    Spacer(Modifier.height(28.dp))
                    Box(Modifier.padding(start = Edge)) {
                        RowTitle(if (youtubeInDock) "Más de tus suscripciones" else "Nuevo en tus suscripciones", "YouTube")
                    }
                    MediaRow(moreVideos, showing, onFocusItem = { focusBelow(RowKey.VIDEOS, it) }, onPlay = play)
                }
                if (pinned.isNotEmpty()) {
                    Spacer(Modifier.height(28.dp))
                    Box(Modifier.padding(start = Edge)) { RowTitle("Fijados", "Prime Video · Disney+ · Max") }
                    MediaRow(pinned, showing, onFocusItem = { focusBelow(RowKey.PINNED, it) }, onPlay = play)
                }
                // The TV's own screens, last: they used to float in the top bar, where focus could get stuck.
                Spacer(Modifier.height(28.dp))
                Box(Modifier.padding(start = Edge)) { RowTitle("Tele") }
                Row(
                    Modifier
                        // Focus would only bring the tile itself into view, flush with the bottom of the screen.
                        .bringIntoViewRequester(tvRow)
                        .onFocusChanged {
                            if (it.hasFocus) {
                                toTopJob?.cancel()
                                focus = Focused(RowKey.OTHER, "")
                                scope.launch { tvRow.bringIntoView() }
                            }
                        }
                        .padding(start = Edge, top = 8.dp, bottom = 28.dp),
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    SystemTile("PlayStation 5", icon = painterResource(R.drawable.playstation)) { context.openInput(PLAYSTATION_INPUT) }
                    SystemTile("Entradas", "⇄") { context.openInputs() }
                    SystemTile("Ajustes", "⚙", modifier = Modifier.focusRequester(settingsTile)) { context.openSettings() }
                    if (!isDefaultHome) {
                        SystemTile("Usar como inicio", "⌂") {
                            val dialog = context.requestDefaultHomeIntent()
                            if (dialog != null) {
                                askedAt = System.currentTimeMillis()
                                askForHome.launch(dialog)
                            } else {
                                context.openHomeSettings()
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }

        TopBar(weather, jellyfinNotice, Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 36.dp))
    }
}

@Composable
private fun Backdrop(urls: List<String>) {
    val context = LocalContext.current
    // The next backdrop is loaded before it replaces the current one, so the crossfade goes from image to
    // image. Crossfading on the URL faded the old one out to black while the new one was still loading.
    var shown by remember { mutableStateOf<coil3.Image?>(null) }
    LaunchedEffect(urls) {
        // Moving quickly along a row cancels this, so only the backdrop where the focus stops is loaded.
        if (urls.isNotEmpty()) delay(150)
        val loader = SingletonImageLoader.get(context)
        // The first that loads; none (or no URL) falls back to the gradient instead of keeping the last one.
        shown = urls.firstNotNullOfOrNull { url ->
            (loader.execute(ImageRequest.Builder(context).data(url).build()) as? SuccessResult)?.image
        }
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
    showing: MediaItem?,
    onFocusItem: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onConnectJellyfin: () -> Unit,
    onConnectYouTube: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(Modifier.height(168.dp).fillMaxWidth()) {
        when (shelf) {
            is Shelf.Items -> MediaRow(shelf.items, showing, onFocusItem, onPlay)
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
fun MediaRow(items: List<MediaItem>, showing: MediaItem?, onFocusItem: (MediaItem) -> Unit, onPlay: (MediaItem) -> Unit) {
    var focusedId by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    FollowShowing(list, items, showing, focusedId)
    LazyRow(
        state = list,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(horizontal = Edge, vertical = 8.dp),
        modifier = Modifier.focusRestorer().onFocusChanged { if (!it.hasFocus) focusedId = null },
    ) {
        items(items, key = { it.id }) { item ->
            MediaCard(
                item,
                Modifier
                    .alpha(if (focusedId != null && focusedId != item.id) UNFOCUSED_ALPHA else 1f)
                    .onFocusChanged { if (it.isFocused) { focusedId = item.id; onFocusItem(item) } },
                showing = item.id == showing?.id,
            ) {
                onPlay(item)
            }
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
    var focusedPackage by remember { mutableStateOf<String?>(null) }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        contentPadding = PaddingValues(horizontal = Edge, vertical = 8.dp),
        modifier = Modifier.focusRestorer(firstFocus).onFocusChanged { if (!it.hasFocus) focusedPackage = null },
    ) {
        items(apps, key = { it.packageName }) { app ->
            val first = app == apps.first()
            AppTile(
                app,
                focused = focusedPackage == app.packageName,
                modifier = Modifier
                    .then(if (first) Modifier.focusRequester(firstFocus) else Modifier)
                    .alpha(if (focusedPackage != null && focusedPackage != app.packageName) UNFOCUSED_ALPHA else 1f)
                    .onFocusChanged { if (it.isFocused) { focusedPackage = app.packageName; onFocusApp(app) } },
            ) { onOpen(app) }
        }
        item { AllAppsTile(onAllApps) }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PosterRow(items: List<MediaItem>, showing: MediaItem?, onFocusItem: (MediaItem) -> Unit, onPlay: (MediaItem) -> Unit) {
    var focusedId by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    FollowShowing(list, items, showing, focusedId)
    LazyRow(
        state = list,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        contentPadding = PaddingValues(horizontal = Edge, vertical = 8.dp),
        modifier = Modifier.focusRestorer().onFocusChanged { if (!it.hasFocus) focusedId = null },
    ) {
        items(items, key = { it.id }) { item ->
            PosterCard(
                item,
                Modifier
                    .alpha(if (focusedId != null && focusedId != item.id) UNFOCUSED_ALPHA else 1f)
                    .onFocusChanged { if (it.isFocused) { focusedId = item.id; onFocusItem(item) } },
                showing = item.id == showing?.id,
            ) {
                onPlay(item)
            }
        }
    }
}

/**
 * Keeps the row on what matters: the card the idle hero is showing while it walks the row (it can be far
 * to the right, off screen), and the focused card again once any key stops it. Otherwise the bar marking
 * the shown card, or the focus itself, can sit where nobody sees it. A card already fully visible does not
 * move the row.
 */
@Composable
private fun FollowShowing(list: LazyListState, items: List<MediaItem>, showing: MediaItem?, focusedId: String?) {
    LaunchedEffect(showing?.id) {
        val target = showing?.id ?: focusedId ?: return@LaunchedEffect
        val index = items.indexOfFirst { it.id == target }
        if (index < 0) return@LaunchedEffect
        val layout = list.layoutInfo
        val card = layout.visibleItemsInfo.firstOrNull { it.index == index }
        val fullyVisible = card != null && card.offset >= layout.viewportStartOffset &&
            card.offset + card.size <= layout.viewportEndOffset - layout.afterContentPadding
        // One card of context to its left, so it does not sit flush against the edge.
        if (!fullyVisible) list.animateScrollToItem((index - 1).coerceAtLeast(0))
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
