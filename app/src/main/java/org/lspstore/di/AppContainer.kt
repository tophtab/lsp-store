package org.lspstore.di

import android.content.Context
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import org.lspstore.data.install.ApkInstaller
import org.lspstore.data.remote.LsposedRepoApi
import org.lspstore.data.remote.createHttpClient
import org.lspstore.data.remote.createJson
import org.lspstore.data.repository.InstalledModulesRepositoryImpl
import org.lspstore.data.repository.ModuleRepositoryImpl
import org.lspstore.data.repository.SettingsRepositoryImpl

class AppContainer(context: Context) {
    val json: Json = createJson()
    val httpClient: HttpClient = createHttpClient(json)

    val moduleRepository: ModuleRepositoryImpl = ModuleRepositoryImpl(context, LsposedRepoApi(httpClient), json)
    val installedRepository: InstalledModulesRepositoryImpl = InstalledModulesRepositoryImpl(context)
    val settingsRepository: SettingsRepositoryImpl = SettingsRepositoryImpl(context)
    val installer: ApkInstaller = ApkInstaller(context, httpClient)
}
