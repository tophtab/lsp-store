package io.github.tophtab.lspstore.domain.model

/**
 * The package an APK is expected to replace: the installed copy when there is
 * one, otherwise the repository listing. Candidate selection and the local
 * install gate must use the same value, or a linked/renamed module can look
 * usable on one screen and unusable on the next.
 */
fun expectedPackage(installedPackage: String?, repoPackage: String): String =
    installedPackage?.takeIf { it.isNotBlank() } ?: repoPackage

/**
 * Whether a repository module tracks pre-releases.
 *
 * Repository package names are the canonical key. Older library data and
 * imported backups may still key the preference by the installed package name,
 * so both identities are honoured when reading.
 */
fun Set<String>.tracksPreReleases(installedPackage: String?, repoPackage: String): Boolean =
    repoPackage in this || (!installedPackage.isNullOrBlank() && installedPackage in this)
