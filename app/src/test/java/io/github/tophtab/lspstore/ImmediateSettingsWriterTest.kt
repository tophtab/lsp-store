package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.ui.screens.settings.ImmediateSettingsWriter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImmediateSettingsWriterTest {
    @Test
    fun duplicateValuesDoNotWriteAndRapidChangesBackToTheOriginalAreStillSaved() = runTest {
        val stored = MutableStateFlow(false)
        val writes = mutableListOf<Boolean>()
        val writer = ImmediateSettingsWriter(this)
        fun save(value: Boolean) = writer.save(stored, value) { delay(100); writes += it; stored.value = it }
        save(false)
        save(true)
        save(true)
        save(false)
        advanceUntilIdle()
        assertEquals(listOf(true, false), writes)
        assertFalse(stored.value)
    }

    @Test
    fun everyTextEditSavesImmediatelyWithoutWaitingForAnotherEdit() = runTest {
        val stored = MutableStateFlow("")
        val writes = mutableListOf<String>()
        val writer = ImmediateSettingsWriter(this)
        writer.save(stored, "a") { writes += it; stored.value = it }
        runCurrent()
        assertEquals(listOf("a"), writes)
        writer.save(stored, "ab") { writes += it; stored.value = it }
        runCurrent()
        assertEquals(listOf("a", "ab"), writes)
    }

    @Test
    fun aSlowSettingDoesNotHoldUpAnotherSetting() = runTest {
        val slow = MutableStateFlow(false)
        val text = MutableStateFlow("")
        val finish = CompletableDeferred<Unit>()
        val writer = ImmediateSettingsWriter(this)
        writer.save(slow, true) { finish.await(); slow.value = it }
        writer.save(text, "saved") { text.value = it }
        runCurrent()
        assertEquals("saved", text.value)
        assertFalse(slow.value)
        finish.complete(Unit)
        advanceUntilIdle()
        assertTrue(slow.value)
    }
}
