package io.github.tophtab.lspstore.data.install

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Keep queued interactive installs until the user returns to the store. */
class InstallForegroundState {
    private val resumed = MutableStateFlow(false)
    val isResumed: Boolean get() = resumed.value

    fun setResumed(value: Boolean) { resumed.value = value }

    suspend fun awaitResumed() { resumed.first { it } }
}
