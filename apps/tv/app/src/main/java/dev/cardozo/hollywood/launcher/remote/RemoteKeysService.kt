package dev.cristian.tv.launcher.remote

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dev.cristian.tv.launcher.launcherApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Gives the remote's app keys a new job: Netflix, TCL Channel and the film key go dead once those apps
 * are disabled, so they can open any app instead. The mapping (keycode → package) is set from the
 * settings page, after "learning" which keycode a button sends ([RemoteKeys.recent]).
 *
 * Enabled from adb, not from the TV's menus:
 *   settings put secure enabled_accessibility_services dev.cristian.tv.launcher/.remote.RemoteKeysService
 * Some TCL firmwares refuse to auto-start sideloaded services (OP_AUTO_START), and keys injected with
 * `input keyevent` never reach accessibility services there: test with the real remote.
 */
private const val TAG = "RemoteKeys"

class RemoteKeysService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mapping: Map<Int, String> = emptyMap()
    /** Keys we consumed on ACTION_DOWN, so the matching ACTION_UP is consumed too. */
    private val swallowed = mutableSetOf<Int>()

    override fun onServiceConnected() {
        Log.i(TAG, "connected, flags=${serviceInfo?.flags}")
        launcherApp.remoteKeys.mapping.onEach { mapping = it }.launchIn(scope)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) RemoteKeys.record(event)
        val target = mapping[event.keyCode]
        return when {
            target != null && event.action == KeyEvent.ACTION_DOWN -> {
                swallowed += event.keyCode
                true
            }
            target != null && event.action == KeyEvent.ACTION_UP && swallowed.remove(event.keyCode) -> {
                launcherApp.remoteKeys.open(target)
                true
            }
            else -> false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
