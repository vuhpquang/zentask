package com.vuhpquang.zentask

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

actual fun defaultHttpClient(): HttpClient = HttpClient(CIO) { zenDefaults() }
