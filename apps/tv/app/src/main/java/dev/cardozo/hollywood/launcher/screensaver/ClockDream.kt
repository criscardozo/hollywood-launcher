package dev.cristian.tv.launcher.screensaver

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextClock
import coil3.SingletonImageLoader
import coil3.asDrawable
import coil3.request.ImageRequest
import dev.cristian.tv.launcher.launcherApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

private const val SLIDE_EVERY_MS = 45_000L
private const val FADE_MS = 2_000L

/**
 * Backdrops from the Jellyfin library, slowly crossfading, with a clock that moves a little every
 * minute so nothing burns in. Without Jellyfin it is just the clock on black. Plain views and one
 * coroutine: it replaces Google's ambient screensaver (dreamx, ~100 MB) and should cost little.
 */
class ClockDream : DreamService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var front: ImageView
    private lateinit var back: ImageView
    private lateinit var clock: TextClock

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false

        front = imageView()
        back = imageView().apply { alpha = 0f }
        val scrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(200, 0, 0, 0), Color.argb(40, 0, 0, 0)),
            )
        }
        clock = TextClock(this).apply {
            format12Hour = "HH:mm"
            format24Hour = "HH:mm"
            textSize = 72f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(Color.argb(220, 242, 243, 245))
            setShadowLayer(12f, 0f, 2f, Color.argb(160, 0, 0, 0))
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(back, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(front, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(scrim, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(
                clock,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END,
                ).apply { setMargins(0, 0, 96, 72) },
            )
        }
        setContentView(root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        scope.launch { slideshow() }
        scope.launch {
            while (isActive) {
                delay(60_000)
                // A few dozen pixels around the corner: enough to avoid burn-in, never in the way.
                clock.animate().translationX(-Random.nextInt(0, 60).toFloat())
                    .translationY(-Random.nextInt(0, 40).toFloat()).setDuration(3_000).start()
            }
        }
    }

    override fun onDreamingStopped() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        super.onDetachedFromWindow()
    }

    private suspend fun slideshow() {
        val urls = runCatching { launcherApp.jellyfin.wallpapers() }.getOrDefault(emptyList())
        if (urls.isEmpty()) return
        val loader = SingletonImageLoader.get(this)
        var i = 0
        while (scope.isActive) {
            val request = ImageRequest.Builder(this).data(urls[i % urls.size]).size(1920, 1080).build()
            val drawable = loader.execute(request).image?.asDrawable(resources)
            if (drawable != null) {
                back.setImageDrawable(drawable)
                back.animate().alpha(1f).setDuration(FADE_MS).start()
                front.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                    val old = front; front = back; back = old
                    back.alpha = 0f
                    back.setImageDrawable(null)
                }.start()
            }
            i++
            delay(SLIDE_EVERY_MS)
        }
    }

    private fun imageView() = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
}
