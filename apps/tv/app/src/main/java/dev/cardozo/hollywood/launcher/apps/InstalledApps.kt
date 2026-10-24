package dev.cardozo.hollywood.launcher.apps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

data class TvApp(
    val packageName: String,
    val label: String,
    val component: ComponentName,
    /** 16:9 banner when the app has one, otherwise its icon. */
    val art: ImageBitmap,
    val hasBanner: Boolean,
)

class InstalledApps(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    /**
     * Every app a TV home screen can open, refreshed when a package is added, removed or changed. The
     * receiver only signals: the reload runs on IO, once per burst (an update sends removed, added and
     * changed in a row). CHANGED also fires whenever any app toggles one of its own components, which Play
     * Services does often, so it only counts for an app that is listed or that can now be launched.
     */
    @OptIn(FlowPreview::class)
    fun observe(): Flow<List<TvApp>> = callbackFlow {
        trySend(true) // the first load, at once
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val pkg = intent.data?.schemeSpecificPart
                if (intent.action == Intent.ACTION_PACKAGE_CHANGED && pkg != null && pkg !in listed && !launchable(pkg)) return
                trySend(false)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }.debounce { first -> if (first) 0L else 500L }.map { load() }.flowOn(Dispatchers.IO)

    /** The packages of the last load, for the receiver's filter. */
    @Volatile private var listed: Set<String> = emptySet()

    private fun launchable(pkg: String): Boolean =
        pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null

    private fun load(): List<TvApp> {
        val leanback = query(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val seen = leanback.map { it.activityInfo.packageName }.toSet()
        // Some apps only declare a phone launcher activity; list them too, after the TV ones.
        val phoneOnly = query(Intent.CATEGORY_LAUNCHER).filter { it.activityInfo.packageName !in seen }
        return (leanback + phoneOnly)
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map(::toTvApp)
            .sortedBy { it.label.lowercase() }
            .also { apps -> listed = apps.mapTo(HashSet()) { it.packageName } }
    }

    private fun query(category: String): List<ResolveInfo> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)

    private fun toTvApp(info: ResolveInfo): TvApp {
        val activity = info.activityInfo
        val banner = activity.loadBanner(pm) ?: activity.applicationInfo.loadBanner(pm)
        val art = banner ?: activity.loadIcon(pm)
        return TvApp(
            packageName = activity.packageName,
            label = info.loadLabel(pm).toString(),
            component = ComponentName(activity.packageName, activity.name),
            art = art.toBitmap(width = if (banner != null) 320 else 160, height = if (banner != null) 180 else 160)
                .asImageBitmap(),
            hasBanner = banner != null,
        )
    }
}
