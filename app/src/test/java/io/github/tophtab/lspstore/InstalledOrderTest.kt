package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.sortedByLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PackageManager's package order is not documented, so the installed screen has
 * to order the rows itself. The update section keeps versionCode order; this
 * covers the two alphabetical sections.
 */
class InstalledOrderTest {

    private fun module(packageName: String, label: String) = InstalledModule(
        packageName = packageName,
        versionCode = 1,
        versionName = "1.0",
        label = label,
        isXposedModule = true,
        description = null,
    )

    @Test
    fun `labels sort alphabetically ignoring case`() {
        val sorted = listOf(
            module("p.zeta", "zeta"),
            module("p.alpha", "Alpha"),
            module("p.beta", "beta"),
        ).sortedByLabel()

        assertEquals(listOf("Alpha", "beta", "zeta"), sorted.map { it.label })
    }

    @Test
    fun `equal labels fall back to package name`() {
        val sorted = listOf(
            module("p.b", "Same"),
            module("p.a", "same"),
        ).sortedByLabel()

        assertEquals(listOf("p.a", "p.b"), sorted.map { it.packageName })
    }
}
