package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.data.install.UninstallOutcome
import io.github.tophtab.lspstore.ui.reportUninstallOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class UninstallResultTest {
    @Test
    fun onlyACompletedUninstallRefreshesBeforeReportingItsOutcome() = runTest {
        val outcomes = listOf(
            UninstallOutcome.Completed to listOf("refresh", "removed"),
            UninstallOutcome.DelegatedToSystem to emptyList(),
            UninstallOutcome.Failure("refused") to listOf("refused"),
        )
        for ((outcome, expected) in outcomes) {
            val events = mutableListOf<String>()
            reportUninstallOutcome(outcome, "removed", { events += "refresh" }, { events += it })
            assertEquals(expected, events)
        }
    }
}
