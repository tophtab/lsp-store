package io.github.tophtab.lspstore.data.repository

import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.installedCopyForRepo

/** Query only the direct package and its known renamed copies, in real time. */
internal suspend fun InstalledModulesRepository.findInstalledCopy(
    repoPackage: String,
    links: Map<String, String>,
    preferredPackage: String?,
): InstalledModule? {
    val names = setOf(repoPackage) + links.filterValues { it == repoPackage }.keys
    val installed = names.mapNotNull { getInstalledModule(it) }
    return installedCopyForRepo(repoPackage, installed, links, preferredPackage)
}
