package dev.cardozo.hollywood.launcher.system

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The TV leaves adb on the network on (TCL turns it on at every boot); Ginebra uses it too. */
private const val ADB_PORT = 5555
/** Long enough for someone to read the TV's «Allow debugging?» prompt the first time and accept it. */
private const val AUTH_WAIT_MS = 90_000

/**
 * Reboots the TV. An app cannot ([android.Manifest.permission.REBOOT] is for the system), so the
 * launcher talks adb to the TV itself, with a key of its own kept in its files. The first time the TV asks
 * whether to allow it; with «Always allow» ticked it never asks again.
 */
suspend fun Context.rebootTv(): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        val dir = File(filesDir, "adb")
        val private = File(dir, "adbkey")
        val public = File(dir, "adbkey.pub")
        if (!private.exists() || !public.exists()) AdbKeyPair.generate(private, public)
        Dadb.create("127.0.0.1", ADB_PORT, AdbKeyPair.read(private, public), connectTimeout = 5_000, socketTimeout = AUTH_WAIT_MS)
            .use { it.shell("reboot") }
        Unit
    }
}
