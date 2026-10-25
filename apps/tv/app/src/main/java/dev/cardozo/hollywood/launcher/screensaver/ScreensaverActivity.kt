package dev.cardozo.hollywood.launcher.screensaver

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager

/**
 * The screensaver on demand, from Home's «Salvapantallas» tile: an app cannot start a dream (that takes a
 * system permission), so this shows the same [FilmShow] in an activity. Like the dream, any key ends it.
 */
class ScreensaverActivity : Activity() {

    private lateinit var show: FilmShow
    /** The OK that opened this may still be going up: only a key pressed here closes it. */
    private var pressed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        show = FilmShow(this)
        setContentView(show.view)
    }

    override fun onStart() {
        super.onStart()
        show.start()
    }

    override fun onStop() {
        show.stop()
        super.onStop()
        // Gone from the screen (Home pressed, an app opened): it is not coming back.
        finish()
    }

    override fun onDestroy() {
        show.release()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Swallow the press and close on its release, so the key does not also act on Home underneath.
        if (event.action == KeyEvent.ACTION_DOWN) pressed = true
        else if (event.action == KeyEvent.ACTION_UP && pressed) finish()
        return true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) finish()
        return true
    }
}
