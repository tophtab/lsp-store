package io.github.tophtab.lspstore.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.tophtab.lspstore.R

/**
 * Type families. Geist is the UI face and Geist Mono is used for values and
 * other tabular text. Both are licensed under the SIL Open Font License 1.1.
 */
val Geist = FontFamily(
    Font(R.font.geist, FontWeight.Normal),
    Font(R.font.geist, FontWeight.Medium),
    Font(R.font.geist, FontWeight.SemiBold),
    Font(R.font.geist, FontWeight.Bold),
)

val GeistMono = FontFamily(
    Font(R.font.geist_mono, FontWeight.Normal),
    Font(R.font.geist_mono, FontWeight.Medium),
)

enum class KomiTextRole {
    Display,
    Title,
    Stamp,
    Body,
    Label,
    Mono,
}

@Immutable
data class KomiType(
    val display: TextStyle,
    val title: TextStyle,
    val stamp: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val mono: TextStyle,
) {
    fun styleFor(role: KomiTextRole): TextStyle = when (role) {
        KomiTextRole.Display -> display
        KomiTextRole.Title -> title
        KomiTextRole.Stamp -> stamp
        KomiTextRole.Body -> body
        KomiTextRole.Label -> label
        KomiTextRole.Mono -> mono
    }
}

val ClassicType = KomiType(
    display = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.02).em,
    ),
    title = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.01).em,
    ),
    stamp = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    body = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    label = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    mono = TextStyle(
        fontFamily = GeistMono,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

val LocalKomiType = staticCompositionLocalOf { ClassicType }

private val classicBody = ClassicType.body

val Typography = Typography(
    displayLarge = ClassicType.display.copy(fontSize = 34.sp, lineHeight = 40.sp),
    displayMedium = ClassicType.display.copy(fontSize = 28.sp, lineHeight = 34.sp),
    displaySmall = ClassicType.display.copy(fontSize = 24.sp, lineHeight = 30.sp),
    headlineLarge = ClassicType.title.copy(fontSize = 24.sp, lineHeight = 30.sp),
    headlineMedium = ClassicType.title.copy(fontSize = 20.sp, lineHeight = 26.sp),
    headlineSmall = ClassicType.title.copy(fontSize = 18.sp, lineHeight = 24.sp),
    titleLarge = ClassicType.title,
    titleMedium = ClassicType.title.copy(fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = ClassicType.title.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = classicBody.copy(fontSize = 15.sp),
    bodyMedium = classicBody,
    bodySmall = classicBody.copy(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = ClassicType.label.copy(fontSize = 13.sp),
    labelMedium = ClassicType.label,
    labelSmall = ClassicType.label.copy(fontSize = 11.sp, lineHeight = 14.sp),
)
