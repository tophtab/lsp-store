package io.github.tophtab.lspstore.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.di.AppContainer
import io.github.tophtab.lspstore.ui.toUserMessage
import io.github.tophtab.lspstore.domain.model.HiddenModules
import io.github.tophtab.lspstore.domain.model.Module
import kotlin.coroutines.cancellation.CancellationException

/** One row in the "Ignored repository modules" screen. */
data class IgnoredModuleItem(
    val packageName: String,
    val title: String,
)

/** One keyword rule, with how many modules it currently matches. */
data class IgnoredKeywordItem(
    val keyword: String,
    /** Matches in the loaded index; null while the index is unavailable. */
    val matchCount: Int?,
)

data class IgnoredModulesUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val items: List<IgnoredModuleItem> = emptyList(),
    val keywords: List<IgnoredKeywordItem> = emptyList(),
    val isAddingKeyword: Boolean = false,
    val keywordDraft: String = "",
    val keywordError: String? = null,
    /** Modules the draft would hide, so an over-broad keyword is visible before it is added. */
    val keywordMatchCount: Int? = null,
)

class IgnoredModulesViewModel(private val container: AppContainer) : ViewModel() {

    private val moduleRepository = container.moduleRepository
    private val libraryRepository = container.libraryRepository

    private val _state = MutableStateFlow(IgnoredModulesUiState())
    val state = _state.asStateFlow()

    /** Null until the index loads; keyword editing must not depend on it. */
    private var index: List<Module>? = null
    private var ignored: Set<String> = emptySet()
    private var keywords: List<String> = emptyList()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            libraryRepository.hiddenModules.collect { value ->
                ignored = value.packages
                keywords = value.keywords
                rebuild()
            }
        }
        load()
    }

    fun restore(packageName: String) {
        viewModelScope.launch { libraryRepository.setModuleIgnored(packageName, ignored = false) }
    }

    fun openAddKeyword() {
        _state.update {
            it.copy(
                isAddingKeyword = true,
                keywordDraft = "",
                keywordError = null,
                keywordMatchCount = null,
            )
        }
    }

    fun dismissAddKeyword() {
        _state.update { it.copy(isAddingKeyword = false, keywordError = null) }
    }

    fun onKeywordDraftChange(draft: String) {
        _state.update {
            it.copy(
                keywordDraft = draft,
                keywordError = null,
                keywordMatchCount = draft.trim().takeIf(String::isNotEmpty)?.let(::matchCount),
            )
        }
    }

    fun addKeyword() {
        val draft = _state.value.keywordDraft.trim()
        val message = validate(draft)
        if (message != null) {
            _state.update { it.copy(keywordError = message) }
            return
        }
        viewModelScope.launch {
            libraryRepository.addIgnoredKeyword(draft)
            _state.update {
                it.copy(
                    isAddingKeyword = false,
                    keywordDraft = "",
                    keywordError = null,
                    keywordMatchCount = null,
                )
            }
        }
    }

    fun removeKeyword(keyword: String) {
        viewModelScope.launch { libraryRepository.removeIgnoredKeyword(keyword) }
    }

    fun refresh() = load()

    private fun validate(draft: String): String? {
        val context = container.appContext
        return when {
            draft.isEmpty() -> context.getString(R.string.library_ignore_keyword_blank)
            draft.length > HiddenModules.MAX_KEYWORD_LENGTH ->
                context.resources.getQuantityString(
                    R.plurals.library_ignore_keyword_too_long,
                    HiddenModules.MAX_KEYWORD_LENGTH,
                    HiddenModules.MAX_KEYWORD_LENGTH,
                )

            keywords.any { it.equals(draft, ignoreCase = true) } ->
                context.getString(R.string.library_ignore_keyword_duplicate)

            keywords.size >= HiddenModules.MAX_KEYWORDS ->
                context.resources.getQuantityString(
                    R.plurals.library_ignore_keyword_limit,
                    HiddenModules.MAX_KEYWORDS,
                    HiddenModules.MAX_KEYWORDS,
                )

            else -> null
        }
    }

    private fun matchCount(draft: String): Int? {
        val modules = index ?: return null
        val trimmed = draft.trim()
        if (trimmed.isEmpty()) return 0
        return modules.count { it.matchesText(trimmed) }
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = index == null, error = null) }
            runCatching { moduleRepository.getIndex(force = false) }
                .onSuccess { modules ->
                    index = modules
                    _state.update { it.copy(isLoading = false) }
                    rebuild()
                }
                .onFailure { error ->
                    // A cancelled load was replaced by the one that cancelled it.
                    if (error is CancellationException) return@onFailure
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = error.toUserMessage(
                                container.appContext,
                                R.string.library_load_failed,
                            ),
                        )
                    }
                }
        }
    }

    private fun rebuild() {
        // The index supplies the display title; a module that left the
        // repository still has an entry, so the row falls back to its package
        // name and stays restorable.
        val byPackage = index?.associateBy { it.packageName }.orEmpty()
        val items = ignored.map { name ->
            IgnoredModuleItem(
                packageName = name,
                title = byPackage[name]?.title ?: name,
            )
        }
        val keywordItems = keywords.map { keyword ->
            IgnoredKeywordItem(keyword = keyword, matchCount = matchCount(keyword))
        }
        _state.update {
            it.copy(
                items = items,
                keywords = keywordItems,
                // The draft often still holds a value when the index arrives.
                keywordMatchCount = it.keywordDraft.trim()
                    .takeIf(String::isNotEmpty)
                    ?.let(::matchCount),
            )
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { IgnoredModulesViewModel(container) }
        }
    }
}
