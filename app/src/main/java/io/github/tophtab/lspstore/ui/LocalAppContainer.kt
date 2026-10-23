package io.github.tophtab.lspstore.ui

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.tophtab.lspstore.di.AppContainer

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer was not provided")
}
