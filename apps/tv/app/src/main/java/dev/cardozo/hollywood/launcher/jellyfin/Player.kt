package dev.cardozo.hollywood.launcher.jellyfin

import android.content.Intent
import dev.cardozo.hollywood.launcher.content.MediaItem

/**
 * The Jellyfin client that plays what the launcher picks. The launcher only builds the intent: watch
 * state lives on the server, so switching players loses nothing.
 */
enum class Player(val packageName: String) {
    /**
     * From Wholphin's services/IntentService.kt: it reads intent.action first and only falls back to the
     * wholphin:// host when there is none, so an ACTION_VIEW intent always lands on the details page.
     * Its own playback action plus extras starts playing at the given position.
     */
    WHOLPHIN("com.github.damontecres.wholphin"),

    /**
     * From jellyfin-androidtv's ui/startup/StartupActivity.kt: an ACTION_VIEW without data falls back to
     * the "ItemId" extra. It opens the item's details page; the official app has no direct-play intent.
     */
    JELLYFIN("org.jellyfin.androidtv");

    fun playIntent(item: MediaItem): Intent = when (this) {
        WHOLPHIN -> Intent("com.github.damontecres.wholphin.PLAYBACK")
            .setClassName(packageName, "com.github.damontecres.wholphin.MainActivity")
            .putExtra("itemId", item.id)
            .putExtra("position", item.positionMs)

        JELLYFIN -> Intent(Intent.ACTION_VIEW)
            .setClassName(packageName, "org.jellyfin.androidtv.ui.startup.StartupActivity")
            .putExtra("ItemId", item.id)
    }

    companion object {
        val packages = entries.map { it.packageName }.toSet()
    }
}
