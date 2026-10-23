package dev.cardozo.hollywood.launcher.screensaver

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.service.dreams.DreamService
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import coil3.SingletonImageLoader
import coil3.asDrawable
import coil3.request.ImageRequest
import dev.cardozo.hollywood.launcher.jellyfin.ScreensaverSlide
import dev.cardozo.hollywood.launcher.launcherApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

private const val SLIDE_EVERY_MS = 20_000L
private const val FADE_MS = 1_500L
/** Without films to show, plain backdrops at the old, slower pace. */
private const val BACKDROP_EVERY_MS = 45_000L
/** How far the slow zoom goes over one slide. */
private const val ZOOM = 1.08f

/**
 * Random films from the Jellyfin library, one every 20 s (layout B of docs/mockups/salvapantallas.html):
 * the backdrop full screen with a slow zoom, the poster, title, facts and overview at the bottom left,
 * and the clock at the top right. Text and clock drift a few pixels so nothing burns in. Without
 * Jellyfin it is just the clock on black. Plain views and one coroutine: it replaces Google's ambient
 * screensaver (dreamx, ~100 MB) and should cost little.
 */
class ClockDream : DreamService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var front: ImageView
    private lateinit var back: ImageView
    private lateinit var info: LinearLayout
    private lateinit var poster: ImageView
    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var overview: TextView
    private lateinit var clock: TextClock

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false

        front = imageView()
        back = imageView().apply { alpha = 0f }
        // Dark at the bottom, where the text is; clear from a little above the middle.
        val scrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(235, 0, 0, 0), Color.argb(140, 0, 0, 0), Color.TRANSPARENT, Color.TRANSPARENT),
            )
        }
        // A light shade along the top, so the clock still reads over a bright backdrop.
        val topScrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(120, 0, 0, 0), Color.TRANSPARENT),
            )
        }
        poster = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(Color.argb(60, 255, 255, 255)) }
            clipToOutline = true
        }
        title = text(36f, Color.argb(255, 242, 243, 245), bold = true).apply { maxLines = 2 }
        meta = text(13f, Color.argb(190, 242, 243, 245)).apply { maxLines = 1 }
        overview = text(12.5f, Color.argb(190, 242, 243, 245)).apply { maxLines = 2; setLineSpacing(0f, 1.3f) }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            addView(title)
            addView(meta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            addView(overview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        info = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            alpha = 0f
            addView(poster, LinearLayout.LayoutParams(dp(100), dp(150)))
            addView(texts, LinearLayout.LayoutParams(dp(580), -2).apply { marginStart = dp(22); bottomMargin = dp(6) })
        }
        clock = TextClock(this).apply {
            format12Hour = "HH:mm"
            format24Hour = "HH:mm"
            textSize = 32f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(Color.argb(220, 242, 243, 245))
            setShadowLayer(12f, 0f, 2f, Color.argb(160, 0, 0, 0))
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(back, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(front, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(scrim, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(topScrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(140), Gravity.TOP))
            addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(48), 0, 0, dp(48)) })
            addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(24), dp(48), 0) })
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
                clock.animate().translationX(-Random.nextInt(0, 40).toFloat())
                    .translationY(Random.nextInt(0, 30).toFloat()).setDuration(3_000).start()
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
        var slides = films()
        if (slides.isEmpty()) return backdropsOnly()
        var i = 0
        while (scope.isActive) {
            // Each round through the list asks for a new random batch, so it does not repeat in the same order.
            if (i > 0 && i % slides.size == 0) slides = films().ifEmpty { slides }
            val slide = slides[i % slides.size]
            val backdrop = load(slide.backdropUrl, 1920, 1080)
            if (backdrop != null) {
                crossfade(backdrop)
                showInfo(slide, load(slide.posterUrl, 400, 600))
            }
            i++
            delay(SLIDE_EVERY_MS)
        }
    }

    private suspend fun films(): List<ScreensaverSlide> =
        runCatching { launcherApp.jellyfin.screensaverSlides() }.getOrDefault(emptyList())

    /** The previous screensaver: backdrops only, when there are no films with poster and overview. */
    private suspend fun backdropsOnly() {
        val urls = runCatching { launcherApp.jellyfin.wallpapers() }.getOrDefault(emptyList())
        if (urls.isEmpty()) return
        var i = 0
        while (scope.isActive) {
            load(urls[i % urls.size], 1920, 1080)?.let { crossfade(it) }
            i++
            delay(BACKDROP_EVERY_MS)
        }
    }

    private suspend fun load(url: String, width: Int, height: Int): Drawable? {
        val request = ImageRequest.Builder(this).data(url).size(width, height).build()
        return SingletonImageLoader.get(this).execute(request).image?.asDrawable(resources)
    }

    /**
     * Fades the new backdrop in as the old one fades out (both animate, so their stacking order does not
     * matter) and starts its slow zoom towards a random point. ViewPropertyAnimator only cancels the
     * properties a new animation sets, so the long zoom and the short fade run side by side.
     */
    private fun crossfade(drawable: Drawable) {
        val incoming = back
        val outgoing = front
        incoming.animate().cancel()
        incoming.setImageDrawable(drawable)
        incoming.scaleX = 1f
        incoming.scaleY = 1f
        incoming.pivotX = incoming.width * Random.nextFloat()
        incoming.pivotY = incoming.height * Random.nextFloat()
        incoming.animate().scaleX(ZOOM).scaleY(ZOOM).setInterpolator(LinearInterpolator())
            .setDuration(SLIDE_EVERY_MS + FADE_MS).start()
        incoming.animate().alpha(1f).setDuration(FADE_MS).start()
        outgoing.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            outgoing.animate().cancel()
            outgoing.setImageDrawable(null)
        }.start()
        front = incoming
        back = outgoing
    }

    /** Swaps the text and poster with a short fade, a few pixels away from where the last one was. */
    private fun showInfo(slide: ScreensaverSlide, posterImage: Drawable?) {
        info.animate().alpha(0f).setDuration(500).withEndAction {
            title.text = slide.title
            meta.text = slide.meta
            overview.text = slide.overview
            poster.setImageDrawable(posterImage)
            poster.visibility = if (posterImage != null) View.VISIBLE else View.GONE
            info.translationX = Random.nextInt(0, dp(12)).toFloat()
            info.translationY = -Random.nextInt(0, dp(8)).toFloat()
            info.animate().alpha(1f).setDuration(900).start()
        }.start()
    }

    private fun imageView() = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }

    private fun text(sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(10f, 0f, 2f, Color.argb(140, 0, 0, 0))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
