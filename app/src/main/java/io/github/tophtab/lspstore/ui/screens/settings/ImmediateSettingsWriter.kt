package io.github.tophtab.lspstore.ui.screens.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Saves every changed value in order, comparing against storage after earlier writes. */
internal class ImmediateSettingsWriter(private val scope: CoroutineScope) {
    private val locks = mutableMapOf<Flow<*>, Mutex>()

    fun <T> save(current: Flow<T>, value: T, write: suspend (T) -> Unit) {
        val lock = locks.getOrPut(current) { Mutex() }
        scope.launch {
            lock.withLock {
                if (current.first() != value) write(value)
            }
        }
    }
}
