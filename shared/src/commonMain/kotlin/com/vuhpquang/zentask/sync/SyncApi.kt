package com.vuhpquang.zentask.sync

import com.vuhpquang.zentask.model.ZenJson
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

enum class SyncErrorKind { UNAUTHORIZED, NETWORK, SERVER, CLIENT }

class SyncException(val kind: SyncErrorKind, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * HTTP client for the ZenTask server. Encodes and decodes with [ZenJson] itself, so the [HttpClient]
 * needs no plugins. Every failure is a [SyncException] with a [SyncErrorKind].
 */
class SyncApi(private val http: HttpClient, baseUrl: String, private val token: () -> String?) {
    private val base = baseUrl.trimEnd('/')

    suspend fun login(password: String, deviceName: String): LoginResponse =
        post("/v1/auth/login", LoginRequest(password, deviceName), LoginRequest.serializer(), LoginResponse.serializer())

    suspend fun sync(request: SyncRequest): SyncResponse =
        post("/v1/sync", request, SyncRequest.serializer(), SyncResponse.serializer())

    private suspend fun <I, O> post(path: String, body: I, input: KSerializer<I>, output: KSerializer<O>): O {
        val response: HttpResponse
        val text: String
        try {
            response = http.post(base + path) {
                token()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                setBody(TextContent(ZenJson.encodeToString(input, body), ContentType.Application.Json))
            }
            text = response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SyncException(SyncErrorKind.NETWORK, e.message ?: "network error", e)
        }
        val code = response.status.value
        when {
            code == 401 -> throw SyncException(SyncErrorKind.UNAUTHORIZED, "unauthorized")
            code in 400..499 -> throw SyncException(SyncErrorKind.CLIENT, "HTTP $code: ${text.take(200)}")
            code !in 200..299 -> throw SyncException(SyncErrorKind.SERVER, "HTTP $code")
        }
        return try {
            ZenJson.decodeFromString(output, text)
        } catch (e: SerializationException) {
            throw SyncException(SyncErrorKind.SERVER, "unreadable response", e)
        } catch (e: IllegalArgumentException) {
            throw SyncException(SyncErrorKind.SERVER, "unreadable response", e)
        }
    }
}
