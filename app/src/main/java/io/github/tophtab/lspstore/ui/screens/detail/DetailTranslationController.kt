package io.github.tophtab.lspstore.ui.screens.detail

import io.github.tophtab.lspstore.data.translation.ContentLanguage
import io.github.tophtab.lspstore.domain.model.ModuleDetail
import io.github.tophtab.lspstore.domain.model.TranslationSettings
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Page translations share the ViewModel scope and publish into its single state flow. */
internal class DetailTranslationController(
    private val scope: CoroutineScope,
    private val readState: () -> ModuleDetailUiState,
    private val updateState: ((ModuleDetailUiState) -> ModuleDetailUiState) -> Unit,
    private val translate: suspend (String) -> Result<String>,
    private val translateHtml: suspend (String) -> Result<String>,
    private val settings: Flow<TranslationSettings>,
    private val targetLanguage: () -> String,
    private val failureMessage: () -> String,
) {
    private var summaryJob: Job? = null
    private var readmeJob: Job? = null
    private var automaticJob: Job? = null
    private val notesJobs = mutableMapOf<String, Job>()
    private val activeTranslations = mutableSetOf<Long>()
    private var translationSequence = 0L
    private var generation = 0L
    private var autoTranslateSuppressed = false
    private var manualTranslationActive = false

    fun onDetailLoaded() {
        if (readState().showTranslation) translatePage(force = manualTranslationActive)
        else maybeAutoTranslate()
    }

    fun toggleTranslation() {
        if (readState().showTranslation) {
            autoTranslateSuppressed = true
            updateState { it.copy(showTranslation = false) }
            return
        }
        autoTranslateSuppressed = false
        manualTranslationActive = true
        updateState { it.copy(showTranslation = true, translateRequest = it.translateRequest + 1) }
        translatePage(force = true)
    }

    private fun translatePage(force: Boolean) {
        val detail = readState().detail ?: return
        if (!readState().showTranslation) return
        translateSummary(detail, force)
        translateReadme(detail, force)
    }

    private fun translateSummary(detail: ModuleDetail, force: Boolean) {
        val summary = detail.module.summary
        if (summary.isNullOrBlank() || readState().translatedSummary != null) return
        if (!force && ContentLanguage.isLanguage(summary, targetLanguage())) return
        if (summaryJob?.isActive == true) return
        val request = generation
        summaryJob = scope.launch {
            translateText(summary, translate, request) { translated ->
                updateState { it.copy(translatedSummary = translated) }
            }
        }
    }

    private fun translateReadme(detail: ModuleDetail, force: Boolean) {
        val html = detail.readmeHtml
        if (html.isNullOrBlank() || readState().translatedReadmeHtml != null) return
        if (!force && ContentLanguage.isLanguage(ContentLanguage.fromHtml(html), targetLanguage())) return
        if (readmeJob?.isActive == true) return
        val request = generation
        readmeJob = scope.launch {
            translateText(html, translateHtml, request) { translated ->
                updateState { it.copy(translatedReadmeHtml = translated) }
            }
        }
    }

    /** Manual requests ignore the language heuristic; automatic requests retain it. */
    fun translateNotes(tagName: String, text: String, manual: Boolean = false) {
        if (!readState().showTranslation) return
        if (text.isBlank() || readState().translatedNotes.containsKey(tagName)) return
        if (!manual && ContentLanguage.isLanguage(text, targetLanguage())) return
        if (notesJobs[tagName]?.isActive == true) return
        val request = generation
        notesJobs[tagName] = scope.launch {
            translateText(text, translate, request) { translated ->
                updateState { it.copy(translatedNotes = it.translatedNotes + (tagName to translated)) }
            }
        }
    }

    private suspend fun translateText(
        text: String,
        translate: suspend (String) -> Result<String>,
        request: Long,
        onSuccess: (String) -> Unit,
    ) {
        val id = ++translationSequence
        activeTranslations += id
        updateState { it.copy(translationsInFlight = activeTranslations.size) }
        try {
            val result = translate(text)
            currentCoroutineContext().ensureActive()
            if (request != generation || id !in activeTranslations) return
            result.onSuccess(onSuccess).onFailure { failure ->
                if (failure is CancellationException) throw failure
                showTranslationFailure()
            }
        } finally {
            // IDs are never reused: a cancelled request cannot clear a replacement's spinner.
            if (activeTranslations.remove(id)) {
                updateState { it.copy(translationsInFlight = activeTranslations.size) }
            }
        }
    }

    private fun showTranslationFailure() {
        updateState { current ->
            val nothingTranslated = activeTranslations.size == 1 &&
                current.translatedSummary == null &&
                current.translatedReadmeHtml == null && current.translatedNotes.isEmpty()
            current.copy(
                message = current.message ?: failureMessage(),
                showTranslation = if (nothingTranslated) false else current.showTranslation,
            )
        }
    }

    fun cancel() {
        generation++
        summaryJob?.cancel()
        readmeJob?.cancel()
        automaticJob?.cancel()
        summaryJob = null
        readmeJob = null
        automaticJob = null
        notesJobs.values.forEach { it.cancel() }
        notesJobs.clear()
        activeTranslations.clear()
        updateState { it.copy(translationsInFlight = 0) }
    }

    private fun maybeAutoTranslate() {
        if (autoTranslateSuppressed || readState().showTranslation) return
        val detail = readState().detail ?: return
        val needsTranslation = ContentLanguage.needsTranslation(
            targetLanguage(),
            detail.module.summary,
            detail.readmeHtml?.let(ContentLanguage::fromHtml),
            detail.latestReleaseFor(readState().channel)?.descriptionHtml?.let(ContentLanguage::fromHtml),
        )
        if (!needsTranslation || automaticJob?.isActive == true) return
        val request = generation
        automaticJob = scope.launch {
            val preference = settings.first()
            currentCoroutineContext().ensureActive()
            if (request != generation || autoTranslateSuppressed || readState().showTranslation) return@launch
            if (!preference.autoTranslate || !preference.isConfigured) return@launch
            updateState { it.copy(showTranslation = true) }
            translatePage(force = false)
        }
    }
}
