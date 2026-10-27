package dev.cardozo.hollywood.launcher.net

import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import java.io.File

private const val TAG = "JsonFile"

/**
 * One value kept on disk as JSON, for showing the last good answer while a source does not respond.
 * AtomicFile, because the low-memory killer often stops the launcher while a streaming app runs and a write
 * cut in half would lose the cache; the lock keeps two refreshes from writing it at once.
 */
class JsonFile<T>(private val file: File, private val serializer: KSerializer<T>) {

    private val lock = Mutex()

    /** Null when there is nothing saved or it no longer reads (an older format). */
    suspend fun read(): T? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!file.exists()) return@withLock null
            runCatching { Http.json.decodeFromString(serializer, AtomicFile(file).readFully().decodeToString()) }
                .onFailure { Log.w(TAG, "Could not read ${file.name}", it) }
                .getOrNull()
        }
    }

    suspend fun write(value: T) = withContext(Dispatchers.IO) {
        lock.withLock {
            val atomic = AtomicFile(file)
            val out = atomic.startWrite()
            runCatching {
                out.write(Http.json.encodeToString(serializer, value).toByteArray())
                atomic.finishWrite(out)
            }.onFailure {
                atomic.failWrite(out)
                Log.w(TAG, "Could not write ${file.name}", it)
            }
        }
    }

    suspend fun delete() = withContext(Dispatchers.IO) { lock.withLock { AtomicFile(file).delete() } }
}
