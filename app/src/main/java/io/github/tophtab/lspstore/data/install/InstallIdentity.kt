package io.github.tophtab.lspstore.data.install

import io.github.tophtab.lspstore.data.probe.AxmlParser
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Optional bookkeeping only. An unreadable manifest never prevents installation. */
data class InstallIdentity(val packageName: String?, val versionCode: Long?, val versionName: String?)

suspend fun readInstallIdentity(file: File): InstallIdentity? = withContext(Dispatchers.IO) {
    runCatching {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: return@use null
            val facts = zip.getInputStream(entry).use { AxmlParser.parse(it.readBytes()) }
                ?: return@use null
            InstallIdentity(facts.packageName, facts.versionCode, facts.versionName)
        }
    }.getOrNull()
}
