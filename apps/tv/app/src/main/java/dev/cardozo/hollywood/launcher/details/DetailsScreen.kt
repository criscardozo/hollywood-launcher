package dev.cardozo.hollywood.launcher.details

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.cardozo.hollywood.launcher.LauncherApplication
import dev.cardozo.hollywood.launcher.R
import dev.cardozo.hollywood.launcher.content.MediaItem
import dev.cardozo.hollywood.launcher.content.Source
import dev.cardozo.hollywood.launcher.home.Clock
import dev.cardozo.hollywood.launcher.home.MediaRow
import dev.cardozo.hollywood.launcher.home.ProgressBar
import dev.cardozo.hollywood.launcher.home.RowTitle
import dev.cardozo.hollywood.launcher.home.ScrollOnlyWhenHidden
import dev.cardozo.hollywood.launcher.jellyfin.Player
import dev.cardozo.hollywood.launcher.launcherApp
import dev.cardozo.hollywood.launcher.ui.Background
import dev.cardozo.hollywood.launcher.ui.Dim
import dev.cardozo.hollywood.launcher.ui.Faint
import dev.cardozo.hollywood.launcher.ui.Foreground

private const val TAG = "Details"
private val Edge = 48.dp

/**
 * A film or series before playing it, close to Jellyfin's own page (layout A of docs/mockups/detalle.html):
 * backdrop, facts, overview, the play buttons, where it plays from, and the cast or the episodes. It is
 * drawn over Home, which stays composed underneath so Back returns to the same card; focus cannot leave it.
 */
@OptIn(ExperimentalFoundationApi::class) // LocalBringIntoViewSpec
@Composable
fun DetailsScreen(item: MediaItem, player: Player, onPlay: (MediaItem) -> Unit) {
    val app = LocalContext.current.launcherApp
    val fallback = remember(item.id) {
        Details.from(item, if (item.source == Source.JELLYFIN) "Reproducir" else "Abrir en ${item.source.label}")
    }
    // The card's own data first, so the page never opens empty; the rest replaces it once it loads.
    val details by produceState(fallback, item.id) {
        value = runCatching { load(app, item) }
            .onFailure { Log.w(TAG, "Details for ${item.id} did not load", it) }
            .getOrNull() ?: fallback
    }
    val firstButton = remember { FocusRequester() }
    LaunchedEffect(details.actions.firstOrNull()?.label) { runCatching { firstButton.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Background)
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
    ) {
        AsyncImage(
            model = details.backdropUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Background.copy(alpha = 0.95f),
                    0.38f to Background.copy(alpha = 0.75f),
                    0.72f to Background.copy(alpha = 0.1f),
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.42f to Color.Transparent,
                    0.66f to Background.copy(alpha = 0.5f),
                    1f to Background.copy(alpha = 0.98f),
                ),
            ),
        )
        Box(Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = 36.dp)) { Clock() }

        CompositionLocalProvider(LocalBringIntoViewSpec provides ScrollOnlyWhenHidden) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 48.dp, bottom = 32.dp)) {
                Column(Modifier.padding(start = Edge).width(500.dp)) {
                    Text(
                        details.title, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, color = Foreground,
                        lineHeight = 46.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Facts(details, Modifier.padding(top = 10.dp))
                    if (details.genres.isNotEmpty()) {
                        Text(details.genres.joinToString(" · "), fontSize = 11.sp, color = Faint, modifier = Modifier.padding(top = 6.dp))
                    }
                    details.overview?.let {
                        Text(
                            it, fontSize = 12.5.sp, color = Dim, lineHeight = 18.sp, maxLines = 3,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    if (details.progress != null) {
                        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            ProgressBar(details.progress!!, Modifier.width(110.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(details.remaining.orEmpty(), fontSize = 10.sp, color = Dim)
                        }
                    }
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        details.actions.forEachIndexed { index, action ->
                            ActionButton(
                                action,
                                modifier = if (index == 0) Modifier.focusRequester(firstButton) else Modifier,
                            ) { onPlay(action.item) }
                        }
                    }
                    SourceBadge(details, item, player, Modifier.padding(top = 16.dp))
                }
                Spacer(Modifier.height(26.dp))
                if (details.isSeries) Episodes(details, onPlay) else Cast(details.cast)
            }
        }
    }
}

private suspend fun load(app: LauncherApplication, item: MediaItem): Details? = when (item.source) {
    Source.JELLYFIN -> app.jellyfin.details(item.id)?.let { withSeerrTrailer(app, it) }
    in Source.pinnable -> app.pins.list().firstOrNull { it.id == item.id }?.let { app.seerr.details(it) }
    else -> null
}

/** About a third of the library has no trailer in Jellyfin's metadata; TMDB (through Seerr) usually does. */
private suspend fun withSeerrTrailer(app: LauncherApplication, details: Details): Details {
    if (details.hasTrailer || details.tmdbId == null || details.tmdbType == null) return details
    if (!app.settings.current().seerrConfigured) return details
    val url = runCatching { app.seerr.trailer(details.tmdbId, details.tmdbType) }.getOrNull() ?: return details
    return details.copy(actions = details.actions + trailerAction(url))
}

@Composable
private fun Facts(details: Details, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        details.facts.forEach { Text(it, fontSize = 13.sp, color = Dim) }
        details.rating?.let {
            Text(
                it, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Dim,
                modifier = Modifier.border(1.dp, Faint, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp),
            )
        }
        details.score?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("★", fontSize = 13.sp, color = Color(0xFFFACC15))
                Text(" $it", fontSize = 13.sp, color = Dim)
            }
        }
        details.note?.let { Text(it, fontSize = 13.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun ActionButton(action: Action, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(7.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.14f),
            contentColor = Foreground,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF111111),
        ),
    ) {
        Row(Modifier.padding(horizontal = 15.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${action.icon}  ", fontSize = 11.sp)
            Text(action.label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            action.note?.let { Text("  $it", fontSize = 10.sp, modifier = Modifier.padding(top = 1.dp)) }
        }
    }
}

/** «Se reproduce en»: Ginebra's G▶ for Jellyfin, the installed app's own icon for a pinned title. */
@Composable
private fun SourceBadge(details: Details, item: MediaItem, player: Player, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val name: String
    val note: String
    if (details.source == Source.JELLYFIN) {
        name = details.sourceName ?: "Jellyfin"
        note = "Tu Jellyfin · se abre en ${if (player == Player.WHOLPHIN) "Wholphin" else "Jellyfin"}"
    } else {
        name = details.source.label
        note = if (item.link != null) "Fijado · abre el título en la app" else "Fijado · abre la app"
    }
    val appIcon = remember(details.source) {
        details.source.packageName?.takeIf { details.source != Source.JELLYFIN }?.let { pkg ->
            runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val logo = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
        when {
            details.source == Source.JELLYFIN -> Image(painterResource(R.drawable.ginebra), contentDescription = null, modifier = logo)
            appIcon != null -> Image(appIcon, contentDescription = null, modifier = logo)
            else -> Box(logo.background(details.source.color))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text("SE REPRODUCE EN", fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Faint)
            Text(name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Foreground)
            Text(note, fontSize = 9.sp, color = Faint)
        }
    }
}

@Composable
private fun Cast(cast: List<Person>) {
    if (cast.isEmpty()) return
    Column(Modifier.padding(start = Edge)) {
        RowTitle("Reparto")
        Row(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
            cast.take(9).forEach { person ->
                Column(Modifier.width(66.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(66.dp).clip(CircleShape).background(Color(0xFF2A2F3A)), contentAlignment = Alignment.Center) {
                        if (person.imageUrl != null) {
                            AsyncImage(person.imageUrl, contentDescription = person.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Text(person.name.take(1), fontSize = 22.sp, color = Faint)
                        }
                    }
                    Text(
                        person.name, fontSize = 9.5.sp, color = Foreground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 5.dp),
                    )
                    person.role?.let {
                        Text(it, fontSize = 8.sp, color = Faint, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun Episodes(details: Details, onPlay: (MediaItem) -> Unit) {
    val app = LocalContext.current.launcherApp
    var season by remember(details.seasonId) { mutableStateOf(details.seasonId) }
    val episodes by produceState(emptyList<MediaItem>(), details.seriesId, season) {
        val seriesId = details.seriesId ?: return@produceState
        val seasonId = season ?: return@produceState
        value = runCatching { app.jellyfin.episodes(seriesId, seasonId) }
            .onFailure { Log.w(TAG, "Episodes of $seasonId did not load", it) }
            .getOrDefault(emptyList())
    }
    Row(Modifier.padding(start = Edge, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        details.seasons.forEach { s ->
            val selected = s.id == season
            Surface(
                onClick = { season = s.id },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (selected) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.1f),
                    contentColor = if (selected) Color(0xFF111111) else Dim,
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color(0xFF111111),
                ),
            ) {
                Text(
                    s.name, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
    MediaRow(episodes, showing = null, onFocusItem = {}, onPlay = onPlay)
}
