package dev.cardozo.hollywood.launcher.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import dev.cardozo.hollywood.launcher.apps.TvApp

private const val TAG = "SystemActions"

// TCL's input picker (com.tcl.suspension) answers this; stock Android TV uses the same action.
private const val ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS"

fun Context.launch(app: TvApp) =
    start(
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setComponent(app.component),
    )

fun Context.openInputs() = start(Intent(ACTION_VIEW_INPUTS))

fun Context.openSettings() = start(Intent(Settings.ACTION_SETTINGS))

/** Starts an activity from the launcher; returns false (and logs) when nothing answers. */
fun Context.start(intent: Intent): Boolean =
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "Nothing answers $intent", e)
        false
    }
