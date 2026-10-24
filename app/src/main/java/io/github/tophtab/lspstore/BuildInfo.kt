package io.github.tophtab.lspstore

/**
 * The version string the UI shows.
 *
 * CI stamps the commit sha it built from into [BuildConfig.BUILD_SHA]. The version
 * name itself is never modified for `dev` builds: a commit's APK is what gets
 * published when that commit is tagged, so it must report the release version.
 * The stamped sha is therefore the only thing that distinguishes a sideloaded dev
 * build from a released one - and it also names the exact commit in bug reports.
 */
val versionLabel: String
    get() = if (BuildConfig.BUILD_SHA.isEmpty()) {
        BuildConfig.VERSION_NAME
    } else {
        "${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_SHA}"
    }
