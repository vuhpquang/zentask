package com.vuhpquang.zentask.server.calendar

import com.vuhpquang.zentask.model.ZenJson
import com.vuhpquang.zentask.server.loginToken
import com.vuhpquang.zentask.server.module
import com.vuhpquang.zentask.server.testConfig
import com.vuhpquang.zentask.sync.SyncRecord
import com.vuhpquang.zentask.sync.SyncRequest
import com.vuhpquang.zentask.sync.SyncResponse
import com.vuhpquang.zentask.sync.isDeleted
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalendarTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    private val refreshToken = "1//refresh-SECRET-value"
    private var wall = 1_793_000_000_000L // 2026-10-26

    /** A fake Google: one account with two calendars; tests mutate [events] and [grantRevoked]. */
    private var events = mutableMapOf(
        "primary" to """[{"id":"e1","summary":"Standup","start":{"dateTime":"2026-11-09T09:00:00+07:00"},"end":{"dateTime":"2026-11-09T09:15:00+07:00"}},
                         {"id":"e2","summary":"Trip","start":{"date":"2026-11-10"},"end":{"date":"2026-11-13"}}]""",
        "work@group.calendar.google.com" to """[{"id":"w1","summary":"Review","start":{"dateTime":"2026-11-09T02:00:00Z"},"end":{"dateTime":"2026-11-09T03:00:00Z"}}]""",
    )
    private var grantRevoked = false

    private val google = HttpClient(MockEngine { req ->
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val path = req.url.encodedPath
        when {
            path.endsWith("/token") -> {
                val body = (req.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
                when {
                    body.contains("grant_type=authorization_code") -> respond("""{"access_token":"access-1","refresh_token":"$refreshToken","expires_in":3600}""", headers = json)
                    grantRevoked -> respond("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest, json)
                    else -> respond("""{"access_token":"access-2","expires_in":3600}""", headers = json)
                }
            }
            path.endsWith("/userinfo") -> respond("""{"email":"owner@example.com"}""", headers = json)
            path.endsWith("/calendarList") -> respond(
                """{"items":[{"id":"primary","summary":"Owner","backgroundColor":"#2E9E88","primary":true,"timeZone":"Asia/Ho_Chi_Minh"},
                             {"id":"work@group.calendar.google.com","summary":"Work","backgroundColor":"#3B82F6","timeZone":"Asia/Ho_Chi_Minh"}]}""",
                headers = json,
            )
            path.contains("/calendars/") && path.endsWith("/events") -> {
                val id = java.net.URLDecoder.decode(path.removeSuffix("/events").substringAfterLast("/calendars/"), Charsets.UTF_8)
                respond("""{"items":${events[id] ?: "[]"}}""", headers = json)
            }
            else -> respond("not found", HttpStatusCode.NotFound)
        }
    })

    private lateinit var dir: Path

    private fun ApplicationTestBuilder.server(configured: Boolean = true) {
        dir = Files.createTempDirectory("cal")
        val base = testConfig(dir)
        val config = if (configured) base.copy(googleClientId = "cid", googleClientSecret = "csecret", encryptionKey = key, publicUrl = "https://zentask.test") else base
        application { module(config, now = { wall }, http = google) }
    }

    private suspend fun ApplicationTestBuilder.connect(token: String): io.ktor.client.statement.HttpResponse {
        val start = client.post("/v1/calendar/google/start") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, start.status, start.bodyAsText())
        val url = Url(ZenJson.parseToJsonElement(start.bodyAsText()).jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("https://zentask.test/v1/calendar/google/callback", url.parameters["redirect_uri"])
        assertTrue(url.parameters["scope"]!!.contains("calendar.readonly"))
        return client.get("/v1/calendar/google/callback?code=abc&state=${url.parameters["state"]}")
    }

    private suspend fun ApplicationTestBuilder.pull(token: String): List<SyncRecord> {
        val res = client.post("/v1/sync") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(ZenJson.encodeToString(SyncRequest.serializer(), SyncRequest(0, emptyList())))
        }
        return ZenJson.decodeFromString(SyncResponse.serializer(), res.bodyAsText()).changes
    }

    private suspend fun ApplicationTestBuilder.checkpoint(token: String): Long {
        val res = client.post("/v1/sync") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(ZenJson.encodeToString(SyncRequest.serializer(), SyncRequest(0, emptyList())))
        }
        return ZenJson.decodeFromString(SyncResponse.serializer(), res.bodyAsText()).checkpoint
    }

    private fun List<SyncRecord>.live(entity: String) = filter { it.entity == entity && !it.isDeleted }
    private fun SyncRecord.str(k: String) = (fields[k] as? JsonPrimitive)?.content
    private fun SyncRecord.long(k: String) = (fields[k] as? JsonPrimitive)?.longOrNull

    @Test
    fun secretBoxRoundTripAndWrongKeyFails() {
        val box = SecretBox(key)
        val sealed = box.encrypt("hello")
        assertEquals("hello", box.decrypt(sealed))
        assertTrue(sealed != box.encrypt("hello"), "random nonce")
        val other = SecretBox(Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
        assertFailsWith<Exception> { other.decrypt(sealed) }
        assertFailsWith<IllegalArgumentException> { SecretBox("short") }
    }

    @Test
    fun connectStoresSealedTokenAndSyncsCalendars() = testApplication {
        server()
        val token = client.loginToken()
        val page = connect(token)
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("owner@example.com is connected"))
        val records = pull(token)
        assertEquals(listOf("owner@example.com"), records.live("calendarAccount").map { it.str("email") })
        assertEquals(setOf("Owner", "Work"), records.live("calendarSource").map { it.str("name") }.toSet())
        assertEquals(setOf("Standup", "Trip", "Review"), records.live("calendarEvent").map { it.str("title") }.toSet())
        val files = Files.list(dir).toList().flatMap { it.readBytes().toList() }.toByteArray().toString(Charsets.ISO_8859_1)
        assertFalse(files.contains(refreshToken), "the refresh token is never stored in clear text")
        assertFalse(records.joinToString().contains(refreshToken))
    }

    @Test
    fun allDayMultiDayEventLandsOnLocalDays() = testApplication {
        server()
        val token = client.loginToken()
        connect(token)
        val trip = pull(token).live("calendarEvent").single { it.str("title") == "Trip" }
        assertEquals("true", trip.str("isAllDay"))
        assertEquals(1_794_243_600_000L, trip.long("start"), "2026-11-10 00:00 in UTC+7")
        assertEquals(1_794_502_800_000L, trip.long("end"), "2026-11-13 00:00 (exclusive)")
    }

    @Test
    fun forgedOrReusedStateCreatesNothing() = testApplication {
        server()
        val token = client.loginToken()
        val forged = client.get("/v1/calendar/google/callback?code=abc&state=forged")
        assertEquals(HttpStatusCode.BadRequest, forged.status)
        assertTrue(pull(token).none { it.entity.startsWith("calendar") })
        val start = client.post("/v1/calendar/google/start") { bearerAuth(token) }
        val state = Url(ZenJson.parseToJsonElement(start.bodyAsText()).jsonObject["url"]!!.jsonPrimitive.content).parameters["state"]
        wall += 11 * 60_000 // expired
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/calendar/google/callback?code=abc&state=$state").status)
        assertTrue(pull(token).none { it.entity.startsWith("calendar") })
    }

    @Test
    fun notConfiguredIs503() = testApplication {
        server(configured = false)
        val token = client.loginToken()
        val res = client.post("/v1/calendar/google/start") { bearerAuth(token) }
        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
        assertEquals("""{"error":"google_not_configured"}""", res.bodyAsText())
    }

    @Test
    fun unchangedSyncWritesNothingAndChangesAreApplied() = testApplication {
        server()
        val token = client.loginToken()
        connect(token)
        val before = checkpoint(token)
        client.post("/v1/calendar/sync") { bearerAuth(token) }
        assertEquals(before, checkpoint(token), "an identical refresh writes no record")
        events["primary"] = """[{"id":"e1","summary":"Standup (moved)","start":{"dateTime":"2026-11-09T10:00:00+07:00"},"end":{"dateTime":"2026-11-09T10:15:00+07:00"}}]"""
        client.post("/v1/calendar/sync") { bearerAuth(token) }
        val live = pull(token).live("calendarEvent")
        assertEquals(setOf("Standup (moved)", "Review"), live.map { it.str("title") }.toSet(), "the trip was deleted in Google")
    }

    @Test
    fun revokedGrantKeepsEventsAndAsksToReconnect() = testApplication {
        server()
        val token = client.loginToken()
        connect(token)
        grantRevoked = true
        client.post("/v1/calendar/sync") { bearerAuth(token) }
        val records = pull(token)
        assertEquals("needs_reconnect", records.live("calendarAccount").single().str("status"))
        assertEquals(3, records.live("calendarEvent").size)
    }

    @Test
    fun removingAnAccountTombstonesEverything() = testApplication {
        server()
        val token = client.loginToken()
        connect(token)
        val account = pull(token).live("calendarAccount").single().id
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/calendar/accounts/$account") { bearerAuth(token) }.status)
        assertTrue(pull(token).none { it.entity.startsWith("calendar") && !it.isDeleted })
        assertEquals(HttpStatusCode.NotFound, client.delete("/v1/calendar/accounts/$account") { bearerAuth(token) }.status)
    }

    @Test
    fun calendarRoutesNeedAFullToken() = testApplication {
        server()
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/calendar/google/start").status)
    }
}
