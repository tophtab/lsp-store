package io.github.tophtab.lspstore.domain.model

/**
 * The repository entry an installed package belongs to.
 *
 * A module installed under its repository package resolves to itself; a renamed
 * module (`dev.wtonec.final`) or an imported one resolves through the user's
 * manual link (`dev.wtonec`). Browsing shelves, the installed tab, the library
 * lists and the import screen all ask the same question - "which repository
 * module is this installed app?" - so the answer lives here instead of being
 * re-implemented (with subtly different fallbacks) in each view model.
 *
 * [byPackage] is the repository index keyed by [Module.packageName]; [links]
 * maps installed package name to repository package name.
 */
fun repoModuleFor(
    installedPackage: String,
    byPackage: Map<String, Module>,
    links: Map<String, String>,
): Module? = byPackage[installedPackage] ?: links[installedPackage]?.let(byPackage::get)

/**
 * Repository package names of the installed Xposed modules that the repository
 * actually knows about. Modules that are installed but absent from the index
 * (and not linked to one) are deliberately left out.
 */
fun repoPackagesOf(
    installed: List<InstalledModule>,
    byPackage: Map<String, Module>,
    links: Map<String, String>,
): Set<String> = installed.asSequence()
    .filter { it.isXposedModule }
    .mapNotNull { repoModuleFor(it.packageName, byPackage, links)?.packageName }
    .toSet()
