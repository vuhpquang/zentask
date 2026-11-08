package com.vuhpquang.zentask.sync

import com.vuhpquang.zentask.model.ZenJson
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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

    /** Starts Google Calendar sign-in; returns the URL to open in the browser. */
    suspend fun calendarConnectUrl(): String =
        ZenJson.parseToJsonElement(send(HttpMethod.Post, "/v1/calendar/google/start", null)).jsonObject["url"]!!.jsonPrimitive.content

    /** Adds an iCal secret link as a calendar account. */
    suspend fun addIcal(name: String, url: String) {
        send(HttpMethod.Post, "/v1/calendar/ical", buildJsonObject { put("name", name); put("url", url) }.toString())
    }

    suspend fun removeCalendarAccount(id: String) {
        send(HttpMethod.Delete, "/v1/calendar/accounts/$id", null)
    }

    /** Asks the server to refresh calendars now (otherwise every 10 minutes). */
    suspend fun refreshCalendars() {
        send(HttpMethod.Post, "/v1/calendar/sync", null)
    }

    private suspend fun <I, O> post(path: String, body: I, input: KSerializer<I>, output: KSerializer<O>): O {
        val text = send(HttpMethod.Post, path, ZenJson.encodeToString(input, body))
        return try {
            ZenJson.decodeFromString(output, text)
        } catch (e: SerializationException) {
            throw SyncException(SyncErrorKind.SERVER, "unreadable response", e)
        } catch (e: IllegalArgumentException) {
            throw SyncException(SyncErrorKind.SERVER, "unreadable response", e)
        }
    }

    /** One request; returns the body text or throws [SyncException] with the error kind (and server message). */
    private suspend fun send(method: HttpMethod, path: String, json: String?): String {
        val response: HttpResponse
        val text: String
        try {
            response = http.request(base + path) {
                this.method = method
                token()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                setBody(TextContent(json ?: "", ContentType.Application.Json))
            }
            text = response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SyncException(SyncErrorKind.NETWORK, e.message ?: "network error", e)
        }
        val code = response.status.value
        val message = runCatching { ZenJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
        when {
            code == 401 -> throw SyncException(SyncErrorKind.UNAUTHORIZED, "unauthorized")
            code in 400..499 -> throw SyncException(SyncErrorKind.CLIENT, message ?: "HTTP $code")
            code !in 200..299 -> throw SyncException(SyncErrorKind.SERVER, message ?: "HTTP $code")
        }
        return text
    }
}
