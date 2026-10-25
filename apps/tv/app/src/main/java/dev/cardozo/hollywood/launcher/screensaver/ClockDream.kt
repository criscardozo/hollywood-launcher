package dev.cardozo.hollywood.launcher.screensaver

import android.service.dreams.DreamService

/**
 * The screensaver: [FilmShow] as a dream. It replaces Google's ambient screensaver (dreamx, ~100 MB)
 * once it is chosen in the TV's settings (`screensaver_components`).
 */
class ClockDream : DreamService() {

    private var show: FilmShow? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
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
}
