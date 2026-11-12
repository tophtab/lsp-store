package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.data.cache.writeCacheAtomically
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicCacheFileTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `replace failure keeps the old cache and cleans the staged file`() {
        val file = directory.newFile("cache.json").apply { writeText("old cache") }
        assertThrows(IOException::class.java) {
            writeCacheAtomically(file, "new cache") { staged, target ->
                assertEquals(file.toPath(), target)
                assertEquals(file.parentFile!!.toPath(), staged.parent)
                assertEquals("new cache", staged.toFile().readText())
                assertEquals("old cache", file.readText())
                throw IOException("atomic rename failed")
            }
        }
        assertEquals("old cache", file.readText())
        assertEquals(listOf("cache.json"), directory.root.listFiles()!!.map { it.name })
    }

    @Test
    fun `atomic writes replace the complete payload and support a new cache directory`() {
        val file = File(directory.root, "nested/cache.json")
        writeCacheAtomically(file, "旧缓存")
        writeCacheAtomically(file, "new complete cache")
        assertEquals("new complete cache", file.readText())
        assertEquals(listOf("cache.json"), file.parentFile!!.listFiles()!!.map { it.name })
    }
}
