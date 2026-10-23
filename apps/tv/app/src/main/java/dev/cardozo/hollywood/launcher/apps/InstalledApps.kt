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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

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

    /** Every app a TV home screen can open, refreshed whenever a package is added, removed or changed. */
    fun observe(): Flow<List<TvApp>> = callbackFlow {
        trySend(load())
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                trySend(load())
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
    }.flowOn(Dispatchers.IO)

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
