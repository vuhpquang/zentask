package com.vuhpquang.zentask.server

import com.vuhpquang.zentask.model.ZenJson
import com.vuhpquang.zentask.sync.LoginRequest
import com.vuhpquang.zentask.sync.LoginResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.nio.file.Files

const val TEST_PASSWORD = "correct horse battery staple"

fun testConfig(dataDir: java.nio.file.Path = Files.createTempDirectory("zentask-server")) = ServerConfig(
    host = "127.0.0.1",
    port = 0,
    dataDir = dataDir,
    password = TEST_PASSWORD,
    publicUrl = "http://localhost",
)

suspend fun HttpClient.login(password: String = TEST_PASSWORD, deviceName: String = "test-mac") =
    post("/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(ZenJson.encodeToString(LoginRequest.serializer(), LoginRequest(password, deviceName)))
    }

suspend fun HttpClient.loginToken(deviceName: String = "test-mac"): String =
    ZenJson.decodeFromString(LoginResponse.serializer(), login(deviceName = deviceName).bodyAsText()).token
