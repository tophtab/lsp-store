package io.github.tophtab.lspstore.data.translation

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import io.github.tophtab.lspstore.data.repository.SettingsRepository
import io.github.tophtab.lspstore.domain.model.TranslationProvider
import io.github.tophtab.lspstore.domain.model.TranslationSettings
import java.net.URLEncoder

class TranslationRepository(
    private val client: HttpClient,
    private val settingsRepository: SettingsRepository,
) {
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    suspend fun translate(text: String): Result<String> {
        if (text.isBlank()) return Result.success(text)
        val settings = settingsRepository.translationSettings.first()
        if (settings.provider == TranslationProvider.DISABLED) {
            return Result.failure(IllegalStateException("Translation is disabled"))
        }
        if (!settings.isConfigured) {
            return Result.failure(IllegalStateException("Translation provider is not configured"))
        }

        val cacheKey = "${settings.provider.name}|${settings.targetLang}|$text"
        synchronized(cache) { cache[cacheKey] }?.let { return Result.success(it) }

        return runCatching { translateWith(settings, text) }
            .onSuccess { result -> synchronized(cache) { cache[cacheKey] = result } }
    }

    private suspend fun translateWith(settings: TranslationSettings, text: String): String =
        when (settings.provider) {
            TranslationProvider.DISABLED -> text
            TranslationProvider.GOOGLE -> google(text, settings.targetLang)
            TranslationProvider.LIBRE_TRANSLATE -> libreTranslate(text, settings)
            TranslationProvider.DEEPL -> deepl(text, settings)
            TranslationProvider.MICROSOFT -> microsoft(text, settings)
        }

    private suspend fun google(text: String, target: String): String {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val url = "https://translate.googleapis.com/translate_a/single" +
            "?client=gtx&sl=auto&tl=$target&dt=t&q=$encoded"
        val root = client.get(url).body<JsonElement>()
        val builder = StringBuilder()
        root.arrayOrEmpty().firstOrNull()?.arrayOrEmpty()?.forEach { part ->
            part.arrayOrEmpty().firstOrNull()?.stringOrNull()?.let { builder.append(it) }
        }
        return builder.toString().ifBlank { error("Empty translation") }
    }

    private suspend fun libreTranslate(text: String, settings: TranslationSettings): String {
        val base = settings.libreUrl.ifBlank { DEFAULT_LIBRE_URL }.trimEnd('/')
        val payload = buildJsonObject {
            put("q", text)
            put("source", "auto")
            put("target", settings.targetLang)
            put("format", "text")
            if (settings.libreKey.isNotBlank()) put("api_key", settings.libreKey)
        }
        val response = client.post("$base/translate") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }.body<JsonElement>()
        return response.objectOrNull()?.get("translatedText")?.stringOrNull()
            ?: error("Empty translation")
    }

    private suspend fun deepl(text: String, settings: TranslationSettings): String {
        val endpoint = if (settings.deeplKey.endsWith(":fx")) {
            "https://api-free.deepl.com/v2/translate"
        } else {
            "https://api.deepl.com/v2/translate"
        }
        val payload = buildJsonObject {
            putJsonArray("text") { add(text) }
            put("target_lang", settings.targetLang.uppercase())
        }
        val response = client.post(endpoint) {
            header(HttpHeaders.Authorization, "DeepL-Auth-Key ${settings.deeplKey}")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }.body<JsonElement>()
        return response.objectOrNull()
            ?.get("translations")
            ?.arrayOrEmpty()
            ?.firstOrNull()
            ?.objectOrNull()
            ?.get("text")
            ?.stringOrNull()
            ?: error("Empty translation")
    }

    private suspend fun microsoft(text: String, settings: TranslationSettings): String {
        val endpoint =
            "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&to=${settings.targetLang}"
        val payload = buildJsonArray {
            add(buildJsonObject { put("Text", text) })
        }
        val response = client.post(endpoint) {
            header("Ocp-Apim-Subscription-Key", settings.microsoftKey)
            if (settings.microsoftRegion.isNotBlank()) {
                header("Ocp-Apim-Subscription-Region", settings.microsoftRegion)
            }
            contentType(ContentType.Application.Json)
            setBody(payload)
        }.body<JsonElement>()
        return response.arrayOrEmpty()
            .firstOrNull()
            ?.objectOrNull()
            ?.get("translations")
            ?.arrayOrEmpty()
            ?.firstOrNull()
            ?.objectOrNull()
            ?.get("text")
            ?.stringOrNull()
            ?: error("Empty translation")
    }

    private fun JsonElement.arrayOrEmpty(): List<JsonElement> =
        runCatching { jsonArray }.getOrNull().orEmpty()

    private fun JsonElement.objectOrNull(): Map<String, JsonElement>? =
        runCatching { jsonObject }.getOrNull()

    private fun JsonElement.stringOrNull(): String? =
        runCatching { jsonPrimitive }.getOrNull()?.let { primitive ->
            runCatching { primitive.content }.getOrNull()
        }

    private companion object {
        const val MAX_CACHE_ENTRIES = 256
        const val DEFAULT_LIBRE_URL = "https://translate.disroot.org"
    }
}
