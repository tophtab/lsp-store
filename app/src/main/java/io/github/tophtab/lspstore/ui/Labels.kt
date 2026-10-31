package io.github.tophtab.lspstore.ui

import androidx.annotation.StringRes
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.model.SortOrder
import io.github.tophtab.lspstore.domain.model.ThemeMode
import io.github.tophtab.lspstore.domain.model.TranslationProvider

@StringRes
fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

@StringRes
fun SortOrder.labelRes(): Int = when (this) {
    SortOrder.UPDATED -> R.string.sort_updated
    SortOrder.STARS -> R.string.sort_stars
    SortOrder.NAME -> R.string.sort_name
}

@StringRes
fun TranslationProvider.labelRes(): Int = when (this) {
    TranslationProvider.GOOGLE -> R.string.translation_google
    TranslationProvider.LIBRE_TRANSLATE -> R.string.translation_libre
    TranslationProvider.DEEPL -> R.string.translation_deepl
    TranslationProvider.MICROSOFT -> R.string.translation_microsoft
}
