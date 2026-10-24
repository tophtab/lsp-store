package io.github.tophtab.lspstore

import androidx.compose.ui.text.AnnotatedString
import io.github.tophtab.lspstore.ui.screens.detail.ReadmeTableCell
import io.github.tophtab.lspstore.ui.screens.detail.captionText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GitHub tables are a common way to lay out screenshot grids. `fromHtml` leaves
 * an object replacement character for each `<img>`, which used to make the cell
 * look non-blank and render `OBJ` instead of the image.
 */
class ReadmeTableImageTest {

    private fun cell(text: String, imageUrl: String? = "https://example.com/1.png") =
        ReadmeTableCell(
            annotated = AnnotatedString(text),
            imageUrl = imageUrl,
        )

    @Test
    fun `caption of an image-only cell is blank`() {
        assertEquals("", cell("\uFFFC").captionText)
    }

    @Test
    fun `caption keeps text next to the image`() {
        assertEquals("支付宝", cell("支付宝\uFFFC").captionText)
    }
}
