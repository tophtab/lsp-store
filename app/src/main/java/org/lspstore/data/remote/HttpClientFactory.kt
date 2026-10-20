package org.lspstore.data.remote

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

private const val USER_AGENT = "LSPosedStore/0.1 (+https://modules.lsposed.org/)"
