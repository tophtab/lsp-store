package org.lspstore

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.lspstore.data.remote.LsposedRepoApi
import org.lspstore.data.remote.createHttpClient
import org.lspstore.data.remote.createJson
import org.lspstore.data.remote.toDetail

/**
 * Hits the live modules.lsposed.org repository. Kept separate from the pure unit tests
 * because it requires network access.
 */
class LiveRepoTest {

    @Test
    fun fetchesIndexAndModuleDetail() = runBlocking {
        // Network tests are opt-in so the normal unit-test suite works offline/CI.
        assumeTrue("set LIVE_REPO_TEST=1 to run", System.getenv("LIVE_REPO_TEST") == "1")
        val client = createHttpClient(createJson())
        try {
            val api = LsposedRepoApi(client)

            val page = api.moduleList(1)
            assertTrue("pageCount=${page.pageCount}", page.pageCount >= 30)
            assertTrue(page.modules.isNotEmpty())
            assertTrue(page.modules.any { it.name.isNotBlank() })

            val detail = api.moduleDetail("io.github.guys222.processkeepalive").toDetail()
            assertEquals("io.github.guys222.processkeepalive", detail.module.packageName)
            assertNotNull(detail.module.latestRelease)
            assertTrue(detail.releases.isNotEmpty())
            assertTrue(detail.releases.any { it.apkAssets.isNotEmpty() })
        } finally {
            client.close()
        }
    }
}
