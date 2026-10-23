package io.github.tophtab.lspstore.data.remote

/**
 * The module repository is not one server with backups; it is three endpoint
 * shapes, and each one is served by a different set of hosts:
 *
 *  - [INDEX]  `modules.json`, the whole catalogue in a single file.
 *  - [LIST]   `module-list/<page>.json`, the paginated catalogue.
 *  - [DETAIL] `module/<package>.json`, one module per file.
 */
enum class RepoEndpoint(val path: String, val probePath: String) {
    INDEX("modules.json", "modules.json"),
    LIST("module-list/*.json", "module-list/1.json"),
    DETAIL("module/*.json", ""),
}

data class Mirror(
    val name: String,
    val host: String,
    val endpoints: Set<RepoEndpoint>,
)

/**
 * Measured 2026-10-22, matching how the hosts answer today:
 *
 *  - `modules.lsposed.org` (Tencent EdgeOne) returns `403` for `modules.json`,
 *    but serves `module-list/{page}.json` and `module/<package>.json` normally.
 *  - `backup.modules.lsposed.org` (GitHub Pages, published daily by
 *    Xposed-Modules-Repo/modules) serves `modules.json` and `module/<package>.json`, and
 *    answers 404 for the paginated paths. It deliberately lags production by 7 days.
 *  - `modules-blogcdn.lsposed.org` answers 418 and
 *    `modules-cloudflare.lsposed.org` has no DNS record at all; only the frozen
 *    LSPosed Manager (2024-01) still lists those two.
 */
val MIRRORS: List<Mirror> = listOf(
    Mirror(
        name = "Official",
        host = "https://modules.lsposed.org/",
        endpoints = setOf(RepoEndpoint.LIST, RepoEndpoint.DETAIL),
    ),
    Mirror(
        name = "Backup",
        host = "https://backup.modules.lsposed.org/",
        endpoints = setOf(RepoEndpoint.INDEX, RepoEndpoint.DETAIL),
    ),
)

/** Preferred mirror host; blank means the built-in order. */
class MirrorState {
    @Volatile
    var preferred: String = ""

    /**
     * Hosts serving [endpoint], with the preferred host first when it serves that
     * endpoint too. A preference for a host without the endpoint is ignored rather
     * than turned into a guaranteed failure.
     */
    fun orderedHosts(endpoint: RepoEndpoint): List<String> {
        val serving = MIRRORS.filter { endpoint in it.endpoints }.map { it.host }
        val chosen = preferred
        if (chosen.isBlank() || chosen !in serving) return serving
        return listOf(chosen) + serving.filterNot { it == chosen }
    }

    /**
     * True when the preference names the host of the single-file index but not the host
     * of the paginated catalogue. The backup host is exactly that case: it has
     * `modules.json` and nothing else, so pinning it only means something if the
     * catalogue is fetched as that one file (a few days behind, on purpose).
     */
    fun prefersFullIndex(): Boolean {
        val chosen = preferred
        if (chosen.isBlank()) return false
        return orderedHosts(RepoEndpoint.INDEX).firstOrNull() == chosen &&
            orderedHosts(RepoEndpoint.LIST).firstOrNull() != chosen
    }
}
