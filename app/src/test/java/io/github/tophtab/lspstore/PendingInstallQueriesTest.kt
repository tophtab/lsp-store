package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.domain.model.InstalledModule
import io.github.tophtab.lspstore.domain.model.PendingInstall
import io.github.tophtab.lspstore.domain.usecase.PendingSettlement
import io.github.tophtab.lspstore.domain.usecase.checkPendingInstalls
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PendingInstallQueriesTest {
    private fun pending(name: String) = PendingInstall(name, name, name, 2, "2", "app.apk", createdAt = 1000)
    private fun installed(name: String) = InstalledModule(name, 2, "2", name, false, null, lastUpdateTime = 2000)

    @Test
    fun `settlement queries only pending packages and deduplicates their names`() = runBlocking {
        val entries = listOf(pending("renamed"), pending("direct"), pending("renamed"))
        val queried = mutableListOf<String>()
        val checks = checkPendingInstalls(entries, { queried += it; installed(it) }, 3000, 1000, true)
        assertEquals(listOf("renamed", "direct"), queried)
        assertTrue(checks.all { it.readable && it.settlement == PendingSettlement.LANDED })
    }

    @Test
    fun `one unreadable package remains pending while another can settle or be abandoned`() = runBlocking {
        val checks = checkPendingInstalls(listOf(pending("failed"), pending("landed"), pending("missing")), {
            when (it) {
                "failed" -> throw IOException("PackageManager unavailable")
                "missing" -> null
                else -> installed(it)
            }
        }, 10_000, 1000, true)
        assertEquals(PendingSettlement.PENDING, checks[0].settlement)
        assertFalse(checks[0].readable)
        assertEquals(PendingSettlement.LANDED, checks[1].settlement)
        assertEquals(PendingSettlement.ABANDONED, checks[2].settlement)
        assertTrue(checks[2].readable)
    }

    @Test
    fun `package query cancellation propagates`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                checkPendingInstalls(listOf(pending("cancelled")), { throw CancellationException() }, 3000, 1000, true)
            }
        }
    }
}
