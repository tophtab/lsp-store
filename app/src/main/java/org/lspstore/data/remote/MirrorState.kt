package org.lspstore.data.remote

data class Mirror(
    val name: String,
    val host: String,
)

val MIRRORS: List<Mirror> = listOf(
    Mirror("Official", "https://modules.lsposed.org/"),
    Mirror("BlogCDN", "https://modules-blogcdn.lsposed.org/"),
    Mirror("Cloudflare", "https://modules-cloudflare.lsposed.org/"),
)

/** Preferred mirror host; blank means automatic rotation. */
class MirrorState {
    @Volatile
    var preferred: String = ""

    fun orderedHosts(): List<String> {
        val all = MIRRORS.map { it.host }
        val chosen = preferred
        if (chosen.isBlank() || chosen !in all) return all
        return listOf(chosen) + all.filterNot { it == chosen }
    }
}
