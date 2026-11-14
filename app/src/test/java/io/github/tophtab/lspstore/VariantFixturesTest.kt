package io.github.tophtab.lspstore

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class VariantFixturesTest {
    @Test
    fun concurrentTestsShareOneImmutableCorpus() {
        val executor = Executors.newFixedThreadPool(8)
        try {
            val copies = executor.invokeAll(List(32) { Callable { VariantFixtures.corpus() } }).map { it.get() }
            assertTrue(copies.all { it === copies.first() })
            assertEquals(1, VariantFixtures.loadCount.get())
            assertSame(VariantFixtures.everyRelease(), VariantFixtures.everyRelease())
            try {
                (copies.first() as MutableList<*>).clear()
                fail("shared corpus must be immutable")
            } catch (_: UnsupportedOperationException) { }
            try {
                (copies.first().first().releases.first().assets as MutableList<*>).clear()
                fail("shared assets must be immutable")
            } catch (_: UnsupportedOperationException) { }
        } finally { executor.shutdownNow() }
    }
}
