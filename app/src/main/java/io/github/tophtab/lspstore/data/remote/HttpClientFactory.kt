package io.github.tophtab.lspstore.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.headers
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

fun createJson(): Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

fun createHttpClient(json: Json): HttpClient = HttpClient(OkHttp) {
    expectSuccess = true

    install(ContentNegotiation) {
        json(json)
    }

    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 30_000
        socketTimeoutMillis = 30_000
    }

    defaultRequest {
        headers {
            append(HttpHeaders.UserAgent, USER_AGENT)
            append(HttpHeaders.Accept, "application/json")
        }
    }
}

/**
 * Translation endpoints are not the module repository: no JSON Accept, no app
 * User-Agent. komi gives translation its own client for the same reason, so the
 * public Google endpoint sees a plain form request.
 */
fun createTranslationHttpClient(): HttpClient = HttpClient(OkHttp) {
    expectSuccess = true

    // The other providers still decode JSON through ContentNegotiation; Google
    // reads its own body as text so the form request stays untouched.
    install(ContentNegotiation) {
        json(createJson())
    }

    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 60_000
        socketTimeoutMillis = 60_000
    }
}

/** Ranged APK probing: never throws on 4xx/5xx, short timeouts, no JSON. */
fun createProbeHttpClient(): HttpClient = HttpClient(OkHttp) {
    expectSuccess = false

    install(HttpTimeout) {
        connectTimeoutMillis = 8_000
        requestTimeoutMillis = 15_000
        socketTimeoutMillis = 15_000
    }

    defaultRequest {
        headers {
            append(HttpHeaders.UserAgent, USER_AGENT)
            append(HttpHeaders.Accept, "*/*")
        }
    }
}

private const val USER_AGENT = "LSPStore/0.1 (+https://modules.lsposed.org/)"
