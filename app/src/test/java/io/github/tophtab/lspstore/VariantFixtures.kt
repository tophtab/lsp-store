package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.domain.model.ModuleVersion
import io.github.tophtab.lspstore.domain.model.Release
import io.github.tophtab.lspstore.domain.model.ReleaseAsset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertNotNull

/** Immutable regression corpus loaded once per test process. */
internal object VariantFixtures {
    val loadCount = java.util.concurrent.atomic.AtomicInteger()
    private val modules: List<CorpusModule> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { loadCorpus() }
    private val releases: List<Pair<String, Release>> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        java.util.Collections.unmodifiableList(modules.flatMap { module -> module.releases.map { module.pkg to it } })
    }

    fun corpus(): List<CorpusModule> = modules
    fun everyRelease(): List<Pair<String, Release>> = releases
    fun asset(name: String, downloads: Int): ReleaseAsset = uiAsset(name, downloads, baseUrl = "https://example.invalid")
    fun releaseOf(vararg names: String): Release = uiRelease("1-1.0", names.toList()).copy(
        name = null, descriptionHtml = null, assets = names.map { asset(it, 0) },
    )

    fun latestRelease(pkg: String): Release {
        val entry = corpus().firstOrNull { it.pkg == pkg }
        assertNotNull("missing corpus entry $pkg", entry)
        val module = entry!!
        return module.releases.firstOrNull { it.tagName == module.latestTag }
            ?: module.releases.first()
    }

    private fun loadCorpus(): List<CorpusModule> {
        loadCount.incrementAndGet()
        val text = checkNotNull(VariantFixtures::class.java.classLoader?.getResource("variant-corpus.json")) {
            "variant-corpus.json missing from test resources"
        }.readText()
        val root = Json.parseToJsonElement(text).jsonArray
        return java.util.Collections.unmodifiableList(root.map { module ->
            val obj = module.jsonObject
            val pkg = obj.getValue("pkg").jsonPrimitive.content
            val latestTag = obj["repoLatest"]?.jsonPrimitive?.content
            val releases = obj.getValue("releases").jsonArray.map { element ->
                val release = element.jsonObject
                val tag = release.getValue("tag").jsonPrimitive.content
                val assets = release.getValue("apks").jsonArray.map { apkElement ->
                    val apk = apkElement.jsonArray
                    ReleaseAsset(
                        name = apk[0].jsonPrimitive.content,
                        downloadUrl = "https://example.invalid/${apk[0].jsonPrimitive.content}",
                        fallbackUrl = null,
                        size = apk[1].jsonPrimitive.content.toLong(),
                        downloadCount = apk[2].jsonPrimitive.content.toInt(),
                        contentType = null,
                    )
                }
                Release(
                    tagName = tag,
                    name = null,
                    url = null,
                    isPrerelease = false,
                    publishedAt = null,
                    descriptionHtml = null,
                    version = ModuleVersion.parse(tag),
                    assets = java.util.Collections.unmodifiableList(assets),
                )
            }
            CorpusModule(pkg, latestTag, java.util.Collections.unmodifiableList(releases))
        })
    }

    data class CorpusModule(
        val pkg: String,
        val latestTag: String?,
        val releases: List<Release>,
    )

}
