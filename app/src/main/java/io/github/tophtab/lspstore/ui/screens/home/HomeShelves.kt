package io.github.tophtab.lspstore.ui.screens.home

import io.github.tophtab.lspstore.domain.model.Module
import io.github.tophtab.lspstore.domain.model.HiddenModules
import java.time.Instant

/** Sorted once per catalogue/visibility change; typing only filters this order. */
internal data class HomeShelves(
    val recentlyUpdated: List<Module> = emptyList(),
    val mostStarred: List<Module> = emptyList(),
) {
    fun search(query: String): List<Module> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return recentlyUpdated.filter { module -> module.matchesText(trimmed) }
    }

    companion object {
        fun build(index: List<Module>, hidden: HiddenModules): HomeShelves {
            val visible = hidden.filter(index)
            return HomeShelves(
                recentlyUpdated = visible.sortedByDescending { it.lastPublishedAt ?: Instant.EPOCH },
                mostStarred = visible.sortedWith(
                    compareByDescending<Module> { it.stars }.thenBy { it.title },
                ),
            )
        }
    }
}
