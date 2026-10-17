package org.lspstore.di

import android.content.Context
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import org.lspstore.data.install.ApkInstaller
import org.lspstore.data.remote.LsposedRepoApi
import org.lspstore.data.remote.ProxyState
import org.lspstore.data.remote.createHttpClient
import org.lspstore.data.remote.createJson
import org.lspstore.data.remote.ProxyTester
import org.lspstore.data.repository.InstalledModulesRepositoryImpl
import org.lspstore.data.repository.LibraryRepositoryImpl
import org.lspstore.data.repository.ModuleRepositoryImpl
import org.lspstore.data.repository.SettingsRepositoryImpl
import org.lspstore.data.translation.TranslationRepository

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val json: Json = createJson()
    val proxyState: ProxyState = ProxyState()
    val httpClient: HttpClient = createHttpClient(json, proxyState)

    val moduleRepository: ModuleRepositoryImpl = ModuleRepositoryImpl(context, LsposedRepoApi(httpClient), json)
    val installedRepository: InstalledModulesRepositoryImpl = InstalledModulesRepositoryImpl(context)
    val settingsRepository: SettingsRepositoryImpl = SettingsRepositoryImpl(context)
    val libraryRepository: LibraryRepositoryImpl = LibraryRepositoryImpl(context)
    val installer: ApkInstaller = ApkInstaller(context, httpClient)
    val translationRepository: TranslationRepository = TranslationRepository(httpClient, settingsRepository)
    val proxyTester: ProxyTester = ProxyTester(httpClient)
}
