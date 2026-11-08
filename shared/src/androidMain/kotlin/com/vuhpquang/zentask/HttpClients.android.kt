package com.vuhpquang.zentask

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

actual fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) { zenDefaults() }
