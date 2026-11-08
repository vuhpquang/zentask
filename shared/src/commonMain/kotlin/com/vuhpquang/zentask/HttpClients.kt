package com.vuhpquang.zentask

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout

/** The platform HTTP client (Darwin on Apple, CIO on the JVM) with timeouts, so a hung request can't stall sync. */
expect fun defaultHttpClient(): HttpClient

internal fun HttpClientConfig<*>.zenDefaults() {
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 60_000
        socketTimeoutMillis = 30_000
    }
    expectSuccess = false
}
