package org.lspstore

import android.app.Application
import org.lspstore.di.AppContainer

class LspStoreApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
