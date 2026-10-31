package io.github.tophtab.lspstore.data.translation

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.contentType
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
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
import java.util.Locale

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
        if (!settings.isConfigured) {
            return Result.failure(IllegalStateException("Translation provider is not configured"))
        }

        val target = translationTarget(settings.provider)
        val cacheKey = "${settings.provider.name}|$target|$text"
        synchronized(cache) { cache[cacheKey] }?.let { return Result.success(it) }

        return runCatching { translateWith(settings, text, target) }
            .onSuccess { result -> synchronized(cache) { cache[cacheKey] = result } }
    }

    private suspend fun translateWith(
        settings: TranslationSettings,
        text: String,
        target: String,
    ): String =
        when (settings.provider) {
            TranslationProvider.GOOGLE -> google(text, target)
            TranslationProvider.LIBRE_TRANSLATE -> libreTranslate(text, settings, target)
            TranslationProvider.DEEPL -> deepl(text, settings, target)
            TranslationProvider.MICROSOFT -> microsoft(text, settings, target)
        }

    /**
     * komi posts the public endpoint as a form instead of putting the text in a
     * query string: same service, but long READMEs no longer hit URL limits and
     * the request leaves the app without the store's JSON headers.
     */
    private suspend fun google(text: String, target: String): String {
        val body = client.submitForm(
            url = "https://translate.googleapis.com/translate_a/single",
            formParameters = Parameters.build {
                append("client", "gtx")
                append("sl", "auto")
                append("tl", target)
                append("dt", "t")
                append("q", text)
            },
        ).bodyAsText()
        val root = Json.parseToJsonElement(body)
        val builder = StringBuilder()
        root.arrayOrEmpty().firstOrNull()?.arrayOrEmpty()?.forEach { part ->
            part.arrayOrEmpty().firstOrNull()?.stringOrNull()?.let { builder.append(it) }
        }
        return builder.toString().ifBlank { error("Empty translation") }
    }

    private suspend fun libreTranslate(
        text: String,
        settings: TranslationSettings,
        target: String,
    ): String {
        val base = settings.libreUrl.ifBlank { DEFAULT_LIBRE_URL }.trimEnd('/')
        val payload = buildJsonObject {
            put("q", text)
            put("source", "auto")
            put("target", target)
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

    private suspend fun deepl(
        text: String,
        settings: TranslationSettings,
        target: String,
    ): String {
        val endpoint = if (settings.deeplKey.endsWith(":fx")) {
            "https://api-free.deepl.com/v2/translate"
        } else {
            "https://api.deepl.com/v2/translate"
        }
        val payload = buildJsonObject {
            putJsonArray("text") { add(text) }
            put("target_lang", target)
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

    private suspend fun microsoft(
        text: String,
        settings: TranslationSettings,
        target: String,
    ): String {
        val endpoint =
            "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&to=$target"
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

/**
 * Target language for [provider], following the app language the way Komi does.
 * LSP Store has no in-app language picker, so the app language is the system
 * language.
 *
 * Providers disagree on the spelling of the same language: DeepL wants `PT-BR`
 * and `ZH-HANS`, Microsoft wants `zh-Hant`, and LibreTranslate only knows
 * two-letter codes. Resolving that here is what lets the settings drop the
 * hand-typed language code.
 */
internal fun translationTarget(
    provider: TranslationProvider,
    locale: Locale = Locale.getDefault(),
): String {
    val language = locale.language.takeIf { it.isNotBlank() && it != "und" } ?: "en"
    val traditionalChinese = locale.country in setOf("TW", "HK", "MO")
    return when (provider) {
        TranslationProvider.DEEPL -> when (language) {
            "en" -> if (locale.country == "GB") "EN-GB" else "EN-US"
            "pt" -> if (locale.country == "BR") "PT-BR" else "PT-PT"
            "zh" -> if (traditionalChinese) "ZH-HANT" else "ZH-HANS"
            "no", "nb" -> "NB"
            else -> language.uppercase()
        }
        TranslationProvider.GOOGLE -> when (language) {
            "zh" -> if (traditionalChinese) "zh-TW" else "zh-CN"
            else -> language
        }
        TranslationProvider.MICROSOFT -> when (language) {
            "zh" -> if (traditionalChinese) "zh-Hant" else "zh-Hans"
            else -> language
        }
        TranslationProvider.LIBRE_TRANSLATE -> language
    }
}
