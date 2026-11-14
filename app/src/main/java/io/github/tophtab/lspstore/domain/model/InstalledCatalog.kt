package io.github.tophtab.lspstore.domain.model

import io.github.tophtab.lspstore.domain.variant.ModuleVariantState

/** Repository metadata that only needs rebuilding when the catalog changes. */
class RepositoryIndex private constructor(
    val byPackage: Map<String, Module>,
    val preReleaseCapable: Set<String>,
    val refreshTick: Int,
) {
    companion object {
        fun build(modules: List<Module>, refreshTick: Int = 0): RepositoryIndex {
            val byPackage = mutableMapOf<String, Module>()
            val preReleaseCapable = mutableSetOf<String>()
            modules.forEach { module ->
                byPackage[module.packageName] = module
                if (module.hasPreReleases) preReleaseCapable += module.packageName
            }
            return RepositoryIndex(byPackage, preReleaseCapable, refreshTick)
        }
    }
}

/** Groups direct and renamed copies once, including non-module replacement APKs. */
class InstalledCopies private constructor(
    val modules: List<InstalledModule>,
    val byPackage: Map<String, InstalledModule>,
    val links: Map<String, String>,
    private val newestByRepo: Map<String, InstalledModule>,
) {
    fun forRepo(repoPackage: String, preferredPackage: String? = null): InstalledModule? =
        byPackage[preferredPackage]?.takeIf {
            it.packageName == repoPackage || links[it.packageName] == repoPackage
        } ?: newestByRepo[repoPackage]

    companion object {
        private val copyOrder = compareBy<InstalledModule> { it.versionCode }
            .thenBy { it.lastUpdateTime }.thenBy { it.packageName }

        fun build(installed: List<InstalledModule>, links: Map<String, String>): InstalledCopies {
            val modules = ArrayList<InstalledModule>(installed.size)
            val byPackage = mutableMapOf<String, InstalledModule>()
            val newest = mutableMapOf<String, InstalledModule>()
            fun add(repo: String, module: InstalledModule) {
                val previous = newest[repo]
                if (previous == null || copyOrder.compare(module, previous) > 0) newest[repo] = module
            }
            installed.forEach { module ->
                modules += module
                byPackage[module.packageName] = module
                add(module.packageName, module)
                links[module.packageName]?.takeIf { it != module.packageName }?.let { add(it, module) }
            }
            return InstalledCopies(modules, byPackage, links, newest)
        }
    }
}

/** The same followed copies drive the installed page and every update check. */
class InstalledCatalog private constructor(
    val repository: RepositoryIndex,
    val copies: InstalledCopies,
    val variantStates: Map<String, ModuleVariantState>,
    val modules: List<InstalledModule>,
    val repoByInstalled: Map<String, Module>,
    private val statesByRepo: Map<String, ModuleVariantState>,
) {
    fun variantState(module: Module): ModuleVariantState? = statesByRepo[module.packageName]

    companion object {
        fun build(
            repository: RepositoryIndex,
            copies: InstalledCopies,
            variantStates: Map<String, ModuleVariantState> = emptyMap(),
        ): InstalledCatalog {
            val chosenPackages = variantStates.values.mapNotNull { it.activePackage }.toSet()
            val statesByRepo = mutableMapOf<String, ModuleVariantState>()
            variantStates.forEach { (key, state) ->
                val repo = repoModuleFor(key, repository.byPackage, copies.links)?.packageName ?: return@forEach
                // Canonical entries win. Multiple legacy aliases follow the newest
                // recorded preference, so one repository still has one visible row.
                val previous = statesByRepo[repo]
                if (key == repo || repo !in variantStates && (previous == null || state.updatedAt > previous.updatedAt)) {
                    statesByRepo[repo] = state
                }
            }
            val modules = mutableListOf<InstalledModule>()
            val repoByInstalled = mutableMapOf<String, Module>()
            copies.modules.forEach { installed ->
                if (!installed.isXposedModule && installed.packageName !in copies.links &&
                    installed.packageName !in chosenPackages
                ) return@forEach
                val module = repoModuleFor(installed.packageName, repository.byPackage, copies.links)
                if (module != null) {
                    val preferred = statesByRepo[module.packageName]?.activePackage
                    if (copies.forRepo(module.packageName, preferred)?.packageName != installed.packageName) return@forEach
                    repoByInstalled[installed.packageName] = module
                }
                modules += installed
            }
            return InstalledCatalog(repository, copies, variantStates, modules, repoByInstalled, statesByRepo)
        }
    }
}
