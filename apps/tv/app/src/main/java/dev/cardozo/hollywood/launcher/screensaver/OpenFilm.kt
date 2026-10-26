package dev.cardozo.hollywood.launcher.screensaver

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import dev.cardozo.hollywood.launcher.MainActivity
import dev.cardozo.hollywood.launcher.content.MediaItem
import dev.cardozo.hollywood.launcher.content.Source
import dev.cardozo.hollywood.launcher.jellyfin.ScreensaverSlide

/** Home opens the details page of the film in this intent's extras: OK on the screensaver. */
const val ACTION_OPEN_FILM = "dev.cardozo.hollywood.launcher.OPEN_FILM"
private const val EXTRA_ID = "id"
private const val EXTRA_TITLE = "title"
private const val EXTRA_META = "meta"
private const val EXTRA_OVERVIEW = "overview"
private const val EXTRA_BACKDROP = "backdrop"
private const val EXTRA_POSTER = "poster"

/** OK on the remote, whatever it sends for it on this TV. */
fun KeyEvent.isOk() = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

/** Keys the screensaver lets through to the system, so they neither close it nor go unheard. */
fun KeyEvent.isVolume() = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
    keyCode == KeyEvent.KEYCODE_VOLUME_MUTE

fun Context.openFilm(slide: ScreensaverSlide) {
    startActivity(
        Intent(this, MainActivity::class.java)
            .setAction(ACTION_OPEN_FILM)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_ID, slide.id)
            .putExtra(EXTRA_TITLE, slide.title)
            .putExtra(EXTRA_META, slide.meta)
            .putExtra(EXTRA_OVERVIEW, slide.overview)
            .putExtra(EXTRA_BACKDROP, slide.backdropUrl)
            .putExtra(EXTRA_POSTER, slide.posterUrl),
    )
}

/** The film [openFilm] asked for, enough for the details page to draw while it loads the rest. */
fun Intent.filmToOpen(): MediaItem? {
    if (action != ACTION_OPEN_FILM) return null
    val id = getStringExtra(EXTRA_ID)?.takeIf { it.isNotEmpty() } ?: return null
    return MediaItem(
        id = id,
        source = Source.JELLYFIN,
        title = getStringExtra(EXTRA_TITLE).orEmpty(),
        subtitle = null,
        meta = getStringExtra(EXTRA_META).orEmpty(),
        overview = getStringExtra(EXTRA_OVERVIEW),
        cardImageUrl = getStringExtra(EXTRA_BACKDROP),
        posterUrl = getStringExtra(EXTRA_POSTER),
        backdropUrl = getStringExtra(EXTRA_BACKDROP),
    )
}
