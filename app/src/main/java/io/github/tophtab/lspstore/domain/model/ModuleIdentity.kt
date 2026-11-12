package io.github.tophtab.lspstore.domain.model

/**
 * The package an APK is expected to replace: the installed copy when there is
 * one, otherwise the repository listing. Used only to rank automatic choices.
 */
fun expectedPackage(installedPackage: String?, repoPackage: String): String =
    installedPackage?.takeIf { it.isNotBlank() } ?: repoPackage

/** Follow the user's confirmed package, or the newest known copy for legacy data. */
fun installedCopyForRepo(
    repoPackage: String,
    installed: List<InstalledModule>,
    links: Map<String, String>,
    preferredPackage: String? = null,
): InstalledModule? {
    val candidates = installed.filter {
        it.packageName == repoPackage || links[it.packageName] == repoPackage
    }
    return candidates.firstOrNull { it.packageName == preferredPackage }
        ?: candidates.maxWithOrNull(
            compareBy<InstalledModule> { it.versionCode }
                .thenBy { it.lastUpdateTime }.thenBy { it.packageName },
        )
}

/**
 * Whether a repository module tracks pre-releases.
 *
 * Repository package names are the canonical key. Older library data and
 * imported backups may still key the preference by the installed package name,
 * so both identities are honoured when reading.
 */
fun Set<String>.tracksPreReleases(installedPackage: String?, repoPackage: String): Boolean =
    repoPackage in this || (!installedPackage.isNullOrBlank() && installedPackage in this)
