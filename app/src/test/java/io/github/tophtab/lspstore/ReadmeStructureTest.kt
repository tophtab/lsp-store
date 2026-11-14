package io.github.tophtab.lspstore

import androidx.compose.ui.text.AnnotatedString
import io.github.tophtab.lspstore.ui.screens.detail.*
import org.junit.Assert.*
import org.junit.Test

class ReadmeStructureTest {
    private val base = "https://github.com/example/module/blob/main"
    private fun blocks(html: String, dark: Boolean = false): List<ReadmeBlock> = parseReadme(
        scanReadmeStructure(html), base, null, dark,
        annotate = { AnnotatedString(decodeHtmlEntities(it.replace(Regex("<[^>]*>"), "")).trim()) },
        plain = ::decodeHtmlEntities,
    )

    @Test
    fun nestedDetailsOnlyUseTheirOwnDirectSummaryAndKeepTrailingBody() {
        val parsed = blocks("""<details><summary>Outer</summary><p>before</p><details><summary>Inner</summary><p>inside</p></details><p>after</p></details><p>tail</p>""")
        val outer = parsed[0] as ReadmeBlock.Details
        val inner = outer.blocks[1] as ReadmeBlock.Details
        assertEquals("Outer", outer.title)
        assertEquals("Inner", inner.title)
        assertEquals("before", (outer.blocks[0] as ReadmeBlock.Text).annotated.text)
        assertEquals("after", (outer.blocks[2] as ReadmeBlock.Text).annotated.text)
        assertEquals("tail", (parsed[1] as ReadmeBlock.Text).annotated.text)
        val noSummary = blocks("<details><details><summary>Nested</summary>body</details>end</details>").single() as ReadmeBlock.Details
        assertEquals("", noSummary.title)
        assertEquals("Nested", (noSummary.blocks[0] as ReadmeBlock.Details).title)
    }

    @Test
    fun identicalFoldTitlesHaveIndependentStablePaths() {
        val html = "<details><summary>Same</summary>one</details><details><summary>Same</summary>two</details>"
        val parsed = blocks(html)
        assertNotEquals(parsed[0].path, parsed[1].path)
        val items = readmeLazyItems(parsed, "doc", setOf(parsed[1].path))
        assertEquals(3, items.size)
        assertFalse((items[0] as ReadmeLazyItem.Details).expanded)
        assertTrue((items[1] as ReadmeLazyItem.Details).expanded)
        assertEquals("two", ((items[2] as ReadmeLazyItem.Block).block as ReadmeBlock.Text).annotated.text)
        assertEquals(items.size, items.map { it.key }.toSet().size)
    }

    @Test
    fun collapsingAParentPreservesTheSavedDescendantChoiceAndExpansionOrder() {
        val parsed = blocks("<details><summary>A</summary>first<details><summary>B</summary>second</details>third</details>tail")
        val outer = parsed[0] as ReadmeBlock.Details
        val inner = outer.blocks[1] as ReadmeBlock.Details
        val saved = setOf(outer.path, inner.path)
        val expanded = readmeLazyItems(parsed, "doc", saved)
        assertEquals(listOf("A", "first", "B", "second", "third", "tail"), expanded.map {
            when (it) {
                is ReadmeLazyItem.Details -> it.block.title
                is ReadmeLazyItem.Block -> (it.block as ReadmeBlock.Text).annotated.text
                else -> error("unexpected table")
            }
        })
        assertEquals(2, readmeLazyItems(parsed, "doc", saved - outer.path).size)
        assertEquals(expanded, readmeLazyItems(parsed, "doc", saved))
    }

    @Test
    fun tablesUseLazyRowsAndKeepImagesCaptionsAndNestedTablesSeparate() {
        val html = """<table><thead><tr><th>Title</th></tr></thead><tbody><tr><td><img src='images/a.png'>caption</td></tr><tr><td>last<table><tr><td>nested</td></tr></table></td></tr></tbody></table>"""
        val table = blocks(html).single() as ReadmeBlock.Table
        assertEquals(2, table.rows.size)
        assertEquals("https://raw.githubusercontent.com/example/module/main/images/a.png", table.rows[0].cells[0].imageUrl)
        assertEquals("caption", table.rows[0].cells[0].captionText)
        val items = readmeLazyItems(listOf(table), "doc", emptySet()).map { it as ReadmeLazyItem.TableRow }
        assertEquals(3, items.size)
        assertTrue(items.first().first)
        assertTrue(items.last().last)
        assertEquals(3, items.map { it.key }.toSet().size)
    }

    @Test
    fun aLargeTableDoesNotBecomeOneEagerItem() {
        val html = buildString { append("<table>"); repeat(1_000) { append("<tr><td>$it</td></tr>") }; append("</table>") }
        val scanned = scanReadmeStructure(html)
        assertEquals(4_002, scanned.scannedTags)
        val items = readmeLazyItems(blocks(html), "doc", emptySet())
        assertEquals(1_000, items.size)
        assertTrue(items.all { it is ReadmeLazyItem.TableRow })
    }

    @Test
    fun tagsInsidePreAreOpaqueAndEscapedCodeIsDisplayedLiterally() {
        val html = "<pre><code><details><summary>code</summary></details> &lt;img src=&#39;x&#39;&gt;</code></pre><p>tail</p>"
        val scanned = scanReadmeStructure(html)
        assertEquals(4, scanned.scannedTags)
        assertTrue(scanned.nodes[0].children.isEmpty())
        val parsed = blocks(html)
        assertEquals("<details><summary>code</summary></details> <img src='x'>", (parsed[0] as ReadmeBlock.Code).text)
        assertEquals("tail", (parsed[1] as ReadmeBlock.Text).annotated.text)
    }

    @Test
    fun nestedListsKeepNumberingDepthAndTextAfterTheNestedList() {
        val parsed = blocks("<ol start=3><li>outer<ul><li>inner</li></ul></li><li value=8>next</li></ol><p>tail</p>")
        assertEquals(3, (parsed[0] as ReadmeBlock.Ordered).number)
        assertEquals("outer", (parsed[0] as ReadmeBlock.Ordered).annotated.text)
        assertEquals(1, (parsed[1] as ReadmeBlock.Bullet).depth)
        assertEquals("inner", (parsed[1] as ReadmeBlock.Bullet).annotated.text)
        assertEquals(8, (parsed[2] as ReadmeBlock.Ordered).number)
        assertEquals("tail", (parsed[3] as ReadmeBlock.Text).annotated.text)
    }

    @Test
    fun relativeLinksAndQuotedDelimitersUseTheScannedAttributes() {
        val structure = scanReadmeStructure("<p><a title='x > y' href='docs/help?x=1&amp;y=2'>help</a></p><img src=images/p.png>")
        val rendered = mutableListOf<String>()
        parseReadme(structure, base, null, false, annotate = { rendered += it; AnnotatedString("text") }, plain = { it })
        assertTrue(rendered.single().contains("href=\"$base/docs/help?x=1&amp;y=2\""))
        assertEquals("images/p.png", structure.nodes[1].attributes["src"])
        assertEquals("mailto:a@example.test", resolveReadmeLink("mailto:a@example.test", base))
        assertNull(resolveReadmeLink("javascript:alert(1)", base))
    }

    @Test
    fun themeImagesShareDocumentIdentityWhileHtmlChangesResetIt() {
        val html = "<details><summary>fold</summary><img src='light.png#gh-light-mode-only'><img src='dark.png#gh-dark-mode-only'><p>tail</p></details>"
        val light = blocks(html)
        val dark = blocks(html, dark = true)
        val path = light.single().path
        val document = readmeDocumentId(html, base)
        val lightItems = readmeLazyItems(light, document, setOf(path))
        val darkItems = readmeLazyItems(dark, document, setOf(path))
        assertEquals(lightItems[0].key, darkItems[0].key)
        assertEquals(lightItems.last().key, darkItems.last().key)
        assertTrue((darkItems[0] as ReadmeLazyItem.Details).expanded)
        assertNotEquals(document, readmeDocumentId(html + "new", base))
        assertNotEquals(document, readmeDocumentId(html, "https://other.example/"))
    }

    @Test
    fun incompleteHtmlClosesAncestorsAndImpliedTableAndListSiblings() {
        val parsed = blocks("<details><summary>fold</summary><table><tr><td>one<td>two<tr><td>three</table><ul><li>a<li>b</ul>tail")
        val details = parsed.single() as ReadmeBlock.Details
        val table = details.blocks[0] as ReadmeBlock.Table
        assertEquals(listOf(2, 1), table.rows.map { it.cells.size })
        assertEquals(listOf("a", "b"), details.blocks.filterIsInstance<ReadmeBlock.Bullet>().map { it.annotated.text })
        assertEquals("tail", (details.blocks.last() as ReadmeBlock.Text).annotated.text)
    }

    @Test
    fun injectedScriptsCommentsSvgAndAdsDoNotRemoveCodeOrTheFollowingText() {
        val parsed = blocks("<script>if (a < b) {}</script><!-- hidden --><svg><path/></svg><aside class='ad-slot'>ad</aside><p>body</p><pre>&lt;script&gt;sample&lt;/script&gt;</pre>")
        assertEquals(2, parsed.size)
        assertEquals("body", (parsed[0] as ReadmeBlock.Text).annotated.text)
        assertEquals("<script>sample</script>", (parsed[1] as ReadmeBlock.Code).text)
    }
}
