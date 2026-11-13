package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.domain.model.ModuleDetail
import io.github.tophtab.lspstore.domain.model.TranslationProvider
import io.github.tophtab.lspstore.domain.model.TranslationSettings
import io.github.tophtab.lspstore.ui.screens.detail.DetailTranslationController
import io.github.tophtab.lspstore.ui.screens.detail.ModuleDetailUiState
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetailTranslationControllerTest {
    @Test
    fun manualRequestsRunConcurrentlyAndDeduplicateEachBlock() = runTest {
        val fixture = Fixture(this, uiDetail(uiModule(summary = "summary"), readme = "<p>README</p>"))
        val summary = CompletableDeferred<Result<String>>()
        val notes = CompletableDeferred<Result<String>>()
        val readme = CompletableDeferred<Result<String>>()
        fixture.translate = { if (it == "summary") summary.await() else notes.await() }
        fixture.translateHtml = { readme.await() }
        fixture.controller.toggleTranslation()
        fixture.controller.translateNotes("tag", "notes", manual = true)
        fixture.controller.translateNotes("tag", "notes", manual = true)
        runCurrent()
        assertEquals(3, fixture.state.value.translationsInFlight)
        assertEquals(listOf("summary", "notes"), fixture.textCalls)
        assertEquals(listOf("<p>README</p>"), fixture.htmlCalls)
        summary.complete(Result.success("translated summary"))
        readme.complete(Result.success("<p>translated README</p>"))
        runCurrent()
        assertEquals(1, fixture.state.value.translationsInFlight)
        notes.complete(Result.success("translated notes"))
        runCurrent()
        assertFalse(fixture.state.value.isTranslating)
        assertTrue(fixture.state.value.showTranslation)
        assertEquals("translated notes", fixture.state.value.translatedNotes["tag"])
    }

    @Test
    fun cancelledSuccessAndFailureCannotOverwriteOrStopAReplacement() = runTest {
        for (oldFails in listOf(false, true)) {
            val fixture = Fixture(this)
            val replacement = CompletableDeferred<Result<String>>()
            var calls = 0
            fixture.translate = {
                if (++calls == 1) {
                    withContext(NonCancellable) { delay(100) }
                    if (oldFails) Result.failure(IOException("old failure")) else Result.success("old result")
                } else replacement.await()
            }
            fixture.controller.toggleTranslation()
            runCurrent()
            fixture.controller.cancel()
            fixture.state.update { it.copy(detail = uiDetail(uiModule(summary = "new summary"))) }
            fixture.controller.onDetailLoaded()
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertEquals(1, fixture.state.value.translationsInFlight)
            assertNull(fixture.state.value.translatedSummary)
            assertNull(fixture.state.value.message)
            replacement.complete(Result.success("new result"))
            runCurrent()
            assertEquals("new result", fixture.state.value.translatedSummary)
            assertFalse(fixture.state.value.isTranslating)
        }
    }

    @Test
    fun showingOriginalRetainsTranslationsAndSuppressesAutomaticReloads() = runTest {
        val fixture = Fixture(this, preference = TranslationSettings(autoTranslate = true))
        fixture.controller.toggleTranslation()
        runCurrent()
        fixture.controller.toggleTranslation()
        assertFalse(fixture.state.value.showTranslation)
        assertEquals("translated:summary", fixture.state.value.translatedSummary)
        fixture.controller.cancel()
        fixture.state.update { it.copy(translatedSummary = null, detail = uiDetail(uiModule(summary = "new"))) }
        fixture.controller.onDetailLoaded()
        runCurrent()
        assertFalse(fixture.state.value.showTranslation)
        assertEquals(1, fixture.textCalls.size)
        fixture.controller.toggleTranslation()
        runCurrent()
        assertTrue(fixture.state.value.showTranslation)
        assertEquals(2, fixture.state.value.translateRequest)
        assertEquals("translated:new", fixture.state.value.translatedSummary)
    }

    @Test
    fun automaticTranslationRequiresConfiguredOptIn() = runTest {
        for (preference in listOf(
            TranslationSettings(),
            TranslationSettings(provider = TranslationProvider.DEEPL, autoTranslate = true),
            TranslationSettings(autoTranslate = true),
        )) {
            val fixture = Fixture(this, preference = preference)
            fixture.controller.onDetailLoaded()
            runCurrent()
            val enabled = preference.autoTranslate && preference.isConfigured
            assertEquals(enabled, fixture.state.value.showTranslation)
            assertEquals(if (enabled) 1 else 0, fixture.textCalls.size)
        }
    }

    @Test
    fun automaticSkipsMatchingContentWhileManualForcesIt() = runTest {
        val english = "This is the module you can install and use with the application."
        val fixture = Fixture(this, uiDetail(
            uiModule(summary = english), readme = "<p>$english</p>",
            releases = listOf(uiRelease().copy(descriptionHtml = english)),
        ), TranslationSettings(autoTranslate = true))
        fixture.controller.onDetailLoaded()
        runCurrent()
        assertTrue(fixture.textCalls.isEmpty())
        assertTrue(fixture.htmlCalls.isEmpty())
        assertFalse(fixture.state.value.showTranslation)
        fixture.controller.toggleTranslation()
        fixture.controller.translateNotes("tag", english, manual = true)
        runCurrent()
        assertEquals(2, fixture.textCalls.size)
        assertEquals(1, fixture.htmlCalls.size)
    }

    @Test
    fun automaticTranslationOnlyRequestsForeignBlocksAndNotesStayOnDemand() = runTest {
        val english = "This is the module you can install and use with the application."
        val fixture = Fixture(this, uiDetail(
            uiModule(summary = english), readme = "<p>这是模块的说明文档</p>",
            releases = listOf(uiRelease().copy(descriptionHtml = english)),
        ), TranslationSettings(autoTranslate = true))
        fixture.controller.onDetailLoaded()
        runCurrent()
        assertTrue(fixture.state.value.showTranslation)
        assertTrue(fixture.textCalls.isEmpty())
        assertEquals(1, fixture.htmlCalls.size)
        fixture.controller.translateNotes("english", english)
        fixture.controller.translateNotes("chinese", "这是本次更新的内容")
        runCurrent()
        assertEquals(listOf("这是本次更新的内容"), fixture.textCalls)
    }

    @Test
    fun allConcurrentFailuresReturnToOriginalWithOneMessage() = runTest {
        val fixture = Fixture(this, uiDetail(uiModule(summary = "summary"), readme = "README"))
        fixture.translate = { delay(if (it == "summary") 20 else 10); Result.failure(IOException("failed")) }
        fixture.translateHtml = { delay(30); Result.failure(IOException("failed")) }
        fixture.controller.toggleTranslation()
        fixture.controller.translateNotes("tag", "notes", manual = true)
        runCurrent()
        advanceTimeBy(21)
        runCurrent()
        assertTrue(fixture.state.value.showTranslation)
        assertEquals(1, fixture.state.value.translationsInFlight)
        advanceUntilIdle()
        assertFalse(fixture.state.value.showTranslation)
        assertFalse(fixture.state.value.isTranslating)
        assertEquals("translation failed", fixture.state.value.message)
        assertEquals(1, fixture.failureMessages)
    }

    @Test
    fun partialFailureKeepsTheSuccessfulTranslationVisible() = runTest {
        val fixture = Fixture(this, uiDetail(uiModule(summary = "summary"), readme = "README"))
        fixture.translate = { delay(20); Result.failure(IOException("failed")) }
        fixture.translateHtml = { delay(10); Result.success("translated README") }
        fixture.controller.toggleTranslation()
        advanceUntilIdle()
        assertTrue(fixture.state.value.showTranslation)
        assertEquals("translated README", fixture.state.value.translatedReadmeHtml)
        assertNull(fixture.state.value.translatedSummary)
        assertFalse(fixture.state.value.isTranslating)
    }

    @Test
    fun aPendingAutomaticSettingReadCannotUndoTheUsersOriginalChoice() = runTest {
        val preference = MutableSharedFlow<TranslationSettings>()
        val fixture = Fixture(this, settings = preference)
        fixture.controller.onDetailLoaded()
        runCurrent()
        fixture.controller.toggleTranslation()
        runCurrent()
        fixture.controller.toggleTranslation()
        preference.emit(TranslationSettings(autoTranslate = true))
        runCurrent()
        assertFalse(fixture.state.value.showTranslation)
        assertEquals(1, fixture.textCalls.size)
    }

    @Test
    fun manualPageChoiceSurvivesReloadAndDoesNotAutomaticallyTranslateNewNotes() = runTest {
        val english = "This is the module you can install and use with the application."
        val fixture = Fixture(this, uiDetail(uiModule(summary = english)))
        fixture.controller.toggleTranslation()
        runCurrent()
        fixture.controller.cancel()
        fixture.state.update { it.copy(translatedSummary = null) }
        fixture.controller.onDetailLoaded()
        runCurrent()
        assertEquals(2, fixture.textCalls.size)
        fixture.controller.translateNotes("new tag", english)
        runCurrent()
        assertTrue(fixture.state.value.translatedNotes.isEmpty())
        fixture.controller.translateNotes("new tag", english, manual = true)
        runCurrent()
        assertEquals("translated:$english", fixture.state.value.translatedNotes["new tag"])
    }

    private class Fixture(
        scope: TestScope,
        detail: ModuleDetail = uiDetail(uiModule(summary = "summary")),
        preference: TranslationSettings = TranslationSettings(),
        settings: Flow<TranslationSettings> = MutableStateFlow(preference),
    ) {
        val state = MutableStateFlow(ModuleDetailUiState(isLoading = false, detail = detail))
        val textCalls = mutableListOf<String>()
        val htmlCalls = mutableListOf<String>()
        var failureMessages = 0
        var translate: suspend (String) -> Result<String> = { Result.success("translated:$it") }
        var translateHtml: suspend (String) -> Result<String> = { Result.success("translated:$it") }
        val controller = DetailTranslationController(
            scope = scope,
            readState = { state.value },
            updateState = { state.update(it) },
            translate = { textCalls += it; translate(it) },
            translateHtml = { htmlCalls += it; translateHtml(it) },
            settings = settings,
            targetLanguage = { "en" },
            failureMessage = { failureMessages++; "translation failed" },
        )
    }
}
