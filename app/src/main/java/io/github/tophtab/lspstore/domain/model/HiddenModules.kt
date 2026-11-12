package io.github.tophtab.lspstore.domain.model

/**
 * Everything that hides repository modules from browsing lists: explicitly
 * ignored package names plus case-insensitive keyword matches.
 *
 * Keywords are rules, not snapshots of what they matched when added, so a
 * module the index gains later is hidden without touching the list. Installed
 * modules and update checks deliberately stay outside this filter, matching the
 * existing package-level ignore.
 */
data class HiddenModules(
    val packages: Set<String> = emptySet(),
    val keywords: List<String> = emptyList(),
) {
    fun hides(module: Module): Boolean =
        module.packageName in packages || keywords.any(module::matchesText)

    fun filter(modules: List<Module>): List<Module> = modules.filterNot(::hides)

    companion object {
        /** Bounds the per-rebuild matching work and keeps the screen readable. */
        const val MAX_KEYWORDS = 50

        /** Long enough for a package fragment; short enough that pasted text is rejected. */
        const val MAX_KEYWORD_LENGTH = 64
    }
}
