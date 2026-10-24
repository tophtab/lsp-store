package io.github.tophtab.lspstore.domain.model

/**
 * Alphabetical order for the installed rows that have no update. PackageManager
 * returns packages in an order it does not document, so the UI has to sort the
 * "up to date" and "unknown" lists itself; the update list keeps its
 * versionCode order because that is what the section is about.
 *
 * Case is ignored so `alpha` and `Beta` interleave the way a user reads them,
 * and the package name breaks ties so equal labels never shuffle between
 * refreshes.
 */
fun List<InstalledModule>.sortedByLabel(): List<InstalledModule> =
    sortedWith(
        Comparator { left, right ->
            val byLabel = String.CASE_INSENSITIVE_ORDER.compare(left.label, right.label)
            if (byLabel != 0) byLabel else left.packageName.compareTo(right.packageName)
        },
    )
