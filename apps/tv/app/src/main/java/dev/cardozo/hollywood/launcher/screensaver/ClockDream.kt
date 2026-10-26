package dev.cardozo.hollywood.launcher.screensaver

import android.service.dreams.DreamService
import android.view.KeyEvent

/**
 * The screensaver: [FilmShow] as a dream. It replaces Google's ambient screensaver (dreamx, ~100 MB)
 * once it is chosen in the TV's settings (`screensaver_components`). Interactive, so OK can open the film
 * on screen; any other key ends it, as a plain screensaver would.
 */
class ClockDream : DreamService() {

    private var show: FilmShow? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        // Full brightness: the films are the point now. The clock-only version dimmed the screen.
        isScreenBright = true
        show = FilmShow(this).also { setContentView(it.view) }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        show?.start()
    }

    override fun onDreamingStopped() {
        show?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        show?.release()
        show = null
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.isVolume()) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP) {
            val film = show?.current?.takeIf { event.isOk() && it.id.isNotEmpty() }
            if (film != null) openFilm(film)
            finish()
        }
        return true
    }
}
