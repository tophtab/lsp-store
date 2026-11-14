package io.github.tophtab.lspstore.ui.screens.detail

import java.security.MessageDigest

/** Theme colours do not identify a document; new HTML or a new base URL does. */
internal fun readmeDocumentId(html: String?, baseUrl: String?): String = MessageDigest.getInstance("SHA-256")
    .digest((baseUrl.orEmpty() + "\u0000" + html.orEmpty()).toByteArray())
    .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

internal sealed interface ReadmeLazyItem {
    val key: String
    val depth: Int
    data class Block(val block: ReadmeBlock, override val key: String, override val depth: Int) : ReadmeLazyItem
    data class Details(
        val block: ReadmeBlock.Details,
        val path: String,
        val expanded: Boolean,
        override val key: String,
        override val depth: Int,
    ) : ReadmeLazyItem
    data class TableRow(
        val row: ReadmeTableRow,
        val first: Boolean,
        val last: Boolean,
        override val key: String,
        override val depth: Int,
    ) : ReadmeLazyItem
}

/** Expanded descendants and individual table rows join the page's one LazyColumn. */
internal fun readmeLazyItems(
    blocks: List<ReadmeBlock>,
    documentId: String,
    expandedPaths: Set<String>,
): List<ReadmeLazyItem> = buildList {
    fun append(blocks: List<ReadmeBlock>, parent: String = "", depth: Int = 0) {
        blocks.forEachIndexed { index, block ->
            val path = block.path.ifEmpty { "$parent/$index" }
            val key = "readme:$documentId:$path"
            when (block) {
                is ReadmeBlock.Details -> {
                    val expanded = path in expandedPaths
                    add(ReadmeLazyItem.Details(block, path, expanded, key, depth))
                    if (expanded) append(block.blocks, path, depth + 1)
                }
                is ReadmeBlock.Table -> {
                    val rows = listOfNotNull(block.header) + block.rows
                    rows.forEachIndexed { rowIndex, row ->
                        add(ReadmeLazyItem.TableRow(row, rowIndex == 0, rowIndex == rows.lastIndex,
                            "$key/row:${row.path.ifEmpty { rowIndex.toString() }}", depth))
                    }
                }
                else -> add(ReadmeLazyItem.Block(block, key, depth))
            }
        }
    }
    append(blocks)
}
