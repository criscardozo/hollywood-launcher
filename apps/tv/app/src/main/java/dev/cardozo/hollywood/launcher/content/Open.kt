package dev.cardozo.hollywood.launcher.content

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import dev.cardozo.hollywood.launcher.jellyfin.Player
import dev.cardozo.hollywood.launcher.system.start

/** What OK on a card does, by source. */
fun Context.open(item: MediaItem, player: Player) {
    when (item.source) {
        Source.JELLYFIN -> start(player.playIntent(item))
        // A watch URL handed to the TV app plays the video directly.
        Source.YOUTUBE, Source.PRIME, Source.DISNEY, Source.MAX -> {
            val opened = item.link?.let { link ->
                start(Intent(Intent.ACTION_VIEW, link.toUri()).setPackage(item.source.packageName))
            } ?: false
            if (!opened) openApp(item.source.packageName)
        }
    }
}

private fun Context.openApp(packageName: String?) {
    packageName ?: return
    val intent = packageManager.getLeanbackLaunchIntentForPackage(packageName)
        ?: packageManager.getLaunchIntentForPackage(packageName)
        ?: return
    start(intent)
}
