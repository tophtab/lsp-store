package io.github.tophtab.lspstore

import io.github.tophtab.lspstore.data.translation.HtmlTranslation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The README arrives as rendered GitHub HTML. Translating it must keep the
 * markup and every piece of code or URL exactly where the author put it.
 */
class HtmlTranslationTest {

    @Test
    fun `markup round-trips unchanged`() {
        val html = "<h1>标题</h1>\n<p>正文 <a href=\"https://example.com/x\">链接</a></p>"

        val protected = HtmlTranslation.protect(html)

        assertFalse(protected.text.contains('<'))
        assertEquals(html, HtmlTranslation.restore(protected.text, protected.tokens))
    }

    @Test
    fun `code and urls are not offered to the translator`() {
        val html = "<p>Run <code>adb shell</code> at https://example.com/docs</p>" +
            "<pre><code>val x = 1</code></pre>"

        val protected = HtmlTranslation.protect(html)

        assertTrue(protected.text.contains("Run"))
        assertFalse(protected.text.contains("adb shell"))
        assertFalse(protected.text.contains("example.com"))
        assertFalse(protected.text.contains("val x"))
        assertEquals(html, HtmlTranslation.restore(protected.text, protected.tokens))
    }

    @Test
    fun `comments are opaque`() {
        val html = "<p>Hello</p><!-- hidden note -->"

        val protected = HtmlTranslation.protect(html)

        assertFalse(protected.text.contains("hidden"))
        assertEquals(html, HtmlTranslation.restore(protected.text, protected.tokens))
    }

    @Test
    fun `a lost token rejects the translation`() {
        val protected = HtmlTranslation.protect("<p>Hello</p>")

        assertNull(HtmlTranslation.restore("Hello", protected.tokens))
    }

    @Test
    fun `reordered tokens keep the markup they stand for`() {
        val html = "<li>Download <code>app.apk</code> from the " +
            "<a href=\"https://example.com\">latest release</a> and install it.</li>"
        val protected = HtmlTranslation.protect(html)

        // Providers reorder words while translating and each token travels with
        // its word, so the link can come back before the file name.
        val reordered = "⟦0⟧从⟦2⟧最新版本⟦3⟧下载⟦1⟧并安装。⟦4⟧"

        assertEquals(
            "<li>从<a href=\"https://example.com\">最新版本</a>下载" +
                "<code>app.apk</code>并安装。</li>",
            HtmlTranslation.restore(reordered, protected.tokens),
        )
    }

    @Test
    fun `a duplicated token rejects the translation`() {
        val protected = HtmlTranslation.protect("<p>Hello</p>")
        val duplicated = "⟦0⟧Hello⟦1⟧⟦1⟧"

        assertNull(HtmlTranslation.restore(duplicated, protected.tokens))
    }

    @Test
    fun `a token outside the protected list rejects the translation`() {
        val protected = HtmlTranslation.protect("<p>Hello</p>")

        assertNull(HtmlTranslation.restore("⟦0⟧Hello⟦7⟧", protected.tokens))
    }

    @Test
    fun `chunks never cut a token`() {
        val html = buildString {
            repeat(20) { append("<p>paragraph $it</p>\n") }
        }
        val protected = HtmlTranslation.protect(html)

        val chunks = HtmlTranslation.chunks(protected.text, maxChars = 40)

        assertTrue(chunks.size > 1)
        assertEquals(protected.text, chunks.joinToString(""))
        chunks.forEach { chunk ->
            assertEquals(chunk.count { it == '⟦' }, chunk.count { it == '⟧' })
        }
        assertEquals(html, HtmlTranslation.restore(chunks.joinToString(""), protected.tokens))
    }

    @Test
    fun `cjk text without spaces still chunks`() {
        val text = "更".repeat(200)

        val chunks = HtmlTranslation.chunks(text, maxChars = 30)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 30 })
        assertEquals(text, chunks.joinToString(""))
    }
}
