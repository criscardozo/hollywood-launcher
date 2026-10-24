package dev.cardozo.hollywood.launcher.system

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
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

/** Whether Hollywood holds the HOME role, i.e. the Home button opens it. */
fun Context.isDefaultHome(): Boolean {
    val roles = getSystemService(RoleManager::class.java) ?: return false
    return roles.isRoleAvailable(RoleManager.ROLE_HOME) && roles.isRoleHeld(RoleManager.ROLE_HOME)
}

/** Android's own «make this the Home app» dialog, when the system offers it. */
fun Context.requestDefaultHomeIntent(): Intent? {
    val roles = getSystemService(RoleManager::class.java) ?: return null
    return if (roles.isRoleAvailable(RoleManager.ROLE_HOME)) roles.createRequestRoleIntent(RoleManager.ROLE_HOME) else null
}

/**
 * The Home app setting, for when the dialog is not offered. On the TV two activities answer this action
 * (the permission controller's real page and an empty stub in TV settings): ask for the real one first.
 */
fun Context.openHomeSettings(): Boolean =
    start(Intent(Settings.ACTION_HOME_SETTINGS).setPackage("com.google.android.permissioncontroller")) ||
        start(Intent(Settings.ACTION_HOME_SETTINGS))

/**
 * The PlayStation 5, on HDMI 2. TCL's id for that input (read from `dumpsys tv_input` on the TV: hardware
 * 16, hdmi_port=2); its live TV app (com.tcl.tv) opens passthrough inputs.
 */
const val PLAYSTATION_INPUT = "com.tcl.tvinput/.TvPassThroughService/HW16"
private const val PLAYSTATION_PORT = 2

/** Switches the TV to an HDMI input, the way the system's input picker does. */
fun Context.openInput(inputId: String): Boolean =
    start(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(resolveInput(inputId))))

/**
 * The input's current id. A firmware update or HDMI-CEC could renumber it: when the known id is no longer
 * listed, the HDMI input whose label ends in its port number («HDMI 2») stands in.
 */
private fun Context.resolveInput(inputId: String): String {
    val inputs = runCatching { getSystemService(TvInputManager::class.java)?.tvInputList }.getOrNull().orEmpty()
    if (inputs.isEmpty() || inputs.any { it.id == inputId }) return inputId
    val port = PLAYSTATION_PORT.toString()
    return inputs.firstOrNull { info ->
        info.type == TvInputInfo.TYPE_HDMI && info.parentId == null &&
            info.loadLabel(this).toString().replace(" ", "").endsWith("HDMI$port", ignoreCase = true)
    }?.id ?: inputId
}
