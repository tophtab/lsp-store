package io.github.tophtab.lspstore

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.tophtab.lspstore.data.repository.decodedField
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreferenceFlowsTest {
    @Test
    fun `unrelated preference writes neither decode nor notify a field`() = runTest {
        val field = stringPreferencesKey("variant_state")
        val favourites = stringPreferencesKey("favourites")
        val recent = stringPreferencesKey("recently_viewed")
        val preferences = MutableSharedFlow<androidx.datastore.preferences.core.Preferences>()
        val decoded = mutableListOf<String?>()
        val received = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            preferences.decodedField(field) { raw ->
                decoded += raw
                raw.orEmpty()
            }.collect { received += it }
        }

        preferences.emit(preferencesOf(field to "state"))
        preferences.emit(preferencesOf(field to "state", favourites to "one"))
        preferences.emit(preferencesOf(field to "state", favourites to "one", recent to "two"))
        assertEquals(listOf("state"), decoded)
        assertEquals(listOf("state"), received)

        preferences.emit(preferencesOf(field to "new state"))
        preferences.emit(preferencesOf())
        preferences.emit(preferencesOf(recent to "three"))
        assertEquals(listOf("state", "new state", null), decoded)
        assertEquals(listOf("state", "new state", ""), received)
    }
}
