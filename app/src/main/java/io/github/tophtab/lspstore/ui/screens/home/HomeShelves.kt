package io.github.tophtab.lspstore.ui.screens.home

import io.github.tophtab.lspstore.domain.model.Module
import java.time.Instant

/** Sorted once per catalogue/visibility change; typing only filters this order. */
internal data class HomeShelves(
    val recentlyUpdated: List<Module> = emptyList(),
    val mostStarred: List<Module> = emptyList(),
) {
    fun search(query: String): List<Module> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return recentlyUpdated.filter { module ->
            module.title.contains(trimmed, ignoreCase = true) ||
                module.packageName.contains(trimmed, ignoreCase = true) ||
                module.summary?.contains(trimmed, ignoreCase = true) == true
        }
    }

    companion object {
        fun build(index: List<Module>, ignored: Set<String>): HomeShelves {
            val visible = index.filterNot { it.packageName in ignored }
            return HomeShelves(
                recentlyUpdated = visible.sortedByDescending { it.lastPublishedAt ?: Instant.EPOCH },
                mostStarred = visible.sortedWith(
                    compareByDescending<Module> { it.stars }.thenBy { it.title },
                ),
            )
        }
    }
}
