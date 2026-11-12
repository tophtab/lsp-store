package io.github.tophtab.lspstore.data.probe

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Includes waiting callers in the reference count, including cancelled waiters. */
internal class KeyedMutex<K> {
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val entries = mutableMapOf<K, Entry>()

    val size: Int get() = synchronized(entries) { entries.size }

    suspend fun <T> withLock(key: K, action: suspend () -> T): T {
        val entry = synchronized(entries) {
            entries.getOrPut(key) { Entry() }.also { it.users++ }
        }
        try {
            return entry.mutex.withLock { action() }
        } finally {
            synchronized(entries) {
                entry.users--
                if (entry.users == 0) entries.remove(key)
            }
        }
    }
}
