package org.lspstore.ui

import androidx.compose.runtime.staticCompositionLocalOf
import org.lspstore.di.AppContainer

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer was not provided")
}
