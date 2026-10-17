package org.lspstore.ui

import androidx.annotation.StringRes
import org.lspstore.R
import org.lspstore.domain.model.SortOrder
import org.lspstore.domain.model.ThemeMode
import org.lspstore.domain.model.UpdateChannel

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
fun UpdateChannel.labelRes(): Int = when (this) {
    UpdateChannel.STABLE -> R.string.channel_stable
    UpdateChannel.BETA -> R.string.channel_beta
    UpdateChannel.NIGHTLY -> R.string.channel_nightly
}
