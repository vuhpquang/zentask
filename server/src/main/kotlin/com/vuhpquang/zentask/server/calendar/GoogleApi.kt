package com.vuhpquang.zentask.server.calendar

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.parameters
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Google said the grant is gone (revoked, expired): the account must be reconnected. */
class InvalidGrantException : Exception("invalid_grant")

class GoogleApiException(message: String) : Exception(message)

@Serializable
data class GoogleTokens(
    val access_token: String = "",
    val refresh_token: String? = null,
    val expires_in: Long = 0,
)

@Serializable
data class GoogleUser(val email: String = "")

@Serializable
data class GoogleCalendar(
    val id: String,
    val summary: String = "",
    val summaryOverride: String? = null,
    val backgroundColor: String? = null,
    val primary: Boolean = false,
    val timeZone: String? = null,
    val deleted: Boolean = false,
)

@Serializable
data class GoogleTime(val date: String? = null, val dateTime: String? = null, val timeZone: String? = null)

@Serializable
data class GoogleEvent(
    val id: String,
    val status: String = "confirmed",
    val summary: String = "",
    val location: String = "",
    val description: String = "",
    val start: GoogleTime = GoogleTime(),
    val end: GoogleTime = GoogleTime(),
    val recurringEventId: String? = null,
)

@Serializable
private data class Page<T>(val items: List<T> = emptyList(), val nextPageToken: String? = null)

/**
 * The few Google endpoints ZenTask needs, read-only (`calendar.readonly`). Base URLs are parameters so
 * tests can point them at a fake.
 */
class GoogleApi(
    private val http: HttpClient,
    private val clientId: String,
    private val clientSecret: String,
    private val authBase: String = "https://accounts.google.com/o/oauth2/v2/auth",
    private val tokenUrl: String = "https://oauth2.googleapis.com/token",
    private val apiBase: String = "https://www.googleapis.com",
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    val configured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    fun authUrl(state: String, redirectUri: String): String = URLBuilder(authBase).apply {
        parameters.append("client_id", clientId)
        parameters.append("redirect_uri", redirectUri)
        parameters.append("response_type", "code")
        parameters.append("scope", "openid email https://www.googleapis.com/auth/calendar.readonly")
        parameters.append("access_type", "offline")
        parameters.append("prompt", "consent")
        parameters.append("include_granted_scopes", "true")
        parameters.append("state", state)
    }.buildString()

    suspend fun exchange(code: String, redirectUri: String): GoogleTokens = token(
        parameters {
            append("code", code); append("client_id", clientId); append("client_secret", clientSecret)
            append("redirect_uri", redirectUri); append("grant_type", "authorization_code")
        },
    )

    /** A fresh access token; [InvalidGrantException] when the refresh token no longer works. */
    suspend fun refresh(refreshToken: String): String = token(
        parameters {
            append("refresh_token", refreshToken); append("client_id", clientId); append("client_secret", clientSecret)
            append("grant_type", "refresh_token")
        },
    ).access_token

    suspend fun userEmail(access: String): String =
        decode(get(access, "https://openidconnect.googleapis.com/v1/userinfo") {}, GoogleUser.serializer()).email

    suspend fun calendars(access: String): List<GoogleCalendar> = paged(GoogleCalendar.serializer()) { token ->
        get(access, "$apiBase/calendar/v3/users/me/calendarList") { token?.let { parameter("pageToken", it) } }
    }

    /** Events in the window, recurring ones expanded (`singleEvents`), cancelled ones included. */
    suspend fun events(access: String, calendarId: String, timeMin: String, timeMax: String): List<GoogleEvent> =
        paged(GoogleEvent.serializer()) { token ->
            get(access, "$apiBase/calendar/v3/calendars/${encode(calendarId)}/events") {
                parameter("timeMin", timeMin); parameter("timeMax", timeMax)
                parameter("singleEvents", "true"); parameter("showDeleted", "false"); parameter("maxResults", 2500)
                token?.let { parameter("pageToken", it) }
            }
        }

    private suspend fun token(form: io.ktor.http.Parameters): GoogleTokens {
        val res = http.submitForm(tokenUrl, form)
        val text = res.bodyAsText()
        if (res.status.value == 400 && text.contains("invalid_grant")) throw InvalidGrantException()
        if (res.status.value !in 200..299) throw GoogleApiException("token endpoint: HTTP ${res.status.value}")
        return json.decodeFromString(GoogleTokens.serializer(), text)
    }

    private suspend fun get(access: String, url: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit): HttpResponse =
        http.get(url) { header(HttpHeaders.Authorization, "Bearer $access"); block() }

    private suspend fun <T> decode(res: HttpResponse, s: KSerializer<T>): T {
        val text = res.bodyAsText()
        if (res.status.value == 401) throw InvalidGrantException()
        if (res.status.value !in 200..299) throw GoogleApiException("${res.call.request.url.encodedPath}: HTTP ${res.status.value}")
        return json.decodeFromString(s, text)
    }

    private suspend fun <T> paged(item: KSerializer<T>, fetch: suspend (String?) -> HttpResponse): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        repeat(50) {
            val page = decode(fetch(token), Page.serializer(item))
            out += page.items
            token = page.nextPageToken ?: return out
        }
        return out
    }

    private fun encode(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
}
