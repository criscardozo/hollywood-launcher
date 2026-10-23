package dev.cristian.tv.launcher.apps

/** The apps the house uses (decided 2026-10-22), in the order they appear on the Apps row. */
val houseApps = listOf(
    "com.google.android.youtube.tv",
    "com.github.damontecres.wholphin",
    "com.amazon.amazonvideo.livingroom",
    "com.disney.disneyplus",
    "com.wbd.stream",
    "com.spotify.tv.android",
    "au.com.ninenow.ctv",
    "com.swm.live",
    "au.com.tenplay",
    "com.sbs.ondemand.tv",
    "au.net.abc.iview",
)

/**
 * The house apps that are installed, in house order. On a device with none of them (the emulator),
 * falls back to whatever is installed so the dock is never empty.
 */
fun List<TvApp>.inHouseOrder(): List<TvApp> {
    val byPackage = associateBy { it.packageName }
    return houseApps.mapNotNull { byPackage[it] }.ifEmpty { take(8) }
}
