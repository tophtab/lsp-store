package io.github.tophtab.lspstore.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import io.github.tophtab.lspstore.ui.theme.KomiTextRole
import io.github.tophtab.lspstore.ui.theme.LocalKomiType

/**
 * Text primitive with komi's type roles. Prefer this over raw Material [Text]
 * so headings, labels and mono values keep the same scale everywhere.
 */
@Composable
fun KomiText(
    text: String,
    modifier: Modifier = Modifier,
    role: KomiTextRole = KomiTextRole.Body,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    uppercase: Boolean? = null,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    val type = LocalKomiType.current
    var style = type.styleFor(role)
    if (fontSize.isSpecified) style = style.copy(fontSize = fontSize)
    if (fontWeight != null) style = style.copy(fontWeight = fontWeight)
    if (letterSpacing.isSpecified) style = style.copy(letterSpacing = letterSpacing)
    if (lineHeight.isSpecified) style = style.copy(lineHeight = lineHeight)
    Text(
        text = if (uppercase == true) text.uppercase() else text,
        modifier = modifier,
        color = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurface,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign,
        style = style,
    )
}
