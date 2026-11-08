package com.vuhpquang.zentask.ticktick

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TickTickApiTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val now = 1_800_000_000_000L // 2027-01-15T08:00:00Z

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun completedTask(id: String, completed: String) =
        """{"id":"$id","projectId":"p1","title":"done $id","status":2,"completedTime":"$completed"}"""

    /** Default fake TickTick: answers every endpoint; [override] can replace any path's answer. */
    private fun api(
        cookie: String = "abc123",
        override: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData? = { null },
    ): TickTickApi {
        val http = HttpClient(MockEngine { req ->
            requests += req
            override(req) ?: when (req.url.encodedPath) {
                "/api/v2/batch/check/0" -> json(BATCH_JSON)
                "/api/v2/project/all/completed" -> json("[${completedTask("c1", "2026-11-01T01:00:00.000+0000")}]")
                "/api/v2/project/all/closed" -> json("""[{"id":"w1","projectId":"p1","title":"nope","status":-1}]""")
                "/api/v2/project/all/trash/pagination" -> json("""{"tasks":[{"id":"x1"}],"next":-1}""")
                "/api/v2/column" -> json("""{"update":[{"id":"c1","projectId":"p1","name":"Doing","sortOrder":1}]}""")
                "/api/v2/habits" -> json("""[{"id":"h1","name":"Run","type":"Boolean"}]""")
                "/api/v2/habitCheckins/query" -> json("""{"checkins":{"h1":[{"id":"k1","habitId":"h1","checkinStamp":20261101,"status":2}]}}""")
                "/api/v2/countdown/list" -> json("""[{"id":"cd1","name":"Tết","date":20270206}]""")
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        })
        return TickTickApi(http, cookie = { cookie }, now = { now })
    }

    @Test
    fun buildsSnapshotFromAllEndpoints() = runTest {
        val s = api().snapshot()
        assertEquals("inbox115781412", s.inboxId)
        assertEquals(setOf("t1", "t2", "c1", "w1"), s.tasks.map { it.id }.toSet())
        assertEquals(setOf("x1"), s.trashedTaskIds)
        assertEquals(listOf("Doing"), s.columns.map { it.name })
        assertEquals(listOf("Run"), s.habits.map { it.name })
        assertEquals(listOf(20261101), s.checkins.map { it.checkinStamp })
        assertEquals(listOf("Tết"), s.countdowns.map { it.name })
        assertTrue(s.wontDoAvailable && s.trashAvailable && s.columnsAvailable && s.habitsAvailable && s.countdownsAvailable)
    }

    @Test
    fun sendsCookieAndDeviceHeaderAndOnlyReads() = runTest {
        api(cookie = "abc123").snapshot()
        for (r in requests) {
            assertEquals("t=abc123", r.headers[HttpHeaders.Cookie])
            assertTrue(r.headers["x-device"].orEmpty().contains("\"platform\":\"web\""))
            if (r.url.encodedPath == "/api/v2/habitCheckins/query") {
                assertEquals(HttpMethod.Post, r.method)
            } else {
                assertEquals(HttpMethod.Get, r.method, r.url.toString())
            }
        }
        requests.clear()
        api(cookie = "t=xyz; other=1").snapshot()
        assertEquals("t=xyz; other=1", requests.first().headers[HttpHeaders.Cookie])
    }

    @Test
    fun completedTasksArePagedUntilShortPage() = runTest {
        val pages = mutableListOf<String>()
        val s = api { req ->
            if (req.url.encodedPath != "/api/v2/project/all/completed") return@api null
            val to = req.url.parameters["to"]!!
            pages += to
            when (pages.size) {
                1 -> json("[" + (0 until 100).joinToString(",") { completedTask("a$it", "2026-11-0${1 + it % 5}T01:00:00.000+0000") } + "]")
                else -> json("[" + completedTask("old", "2025-01-01T00:00:00.000+0000") + "]")
            }
        }.snapshot()
        assertEquals(2, pages.size)
        assertEquals("2027-01-15 08:00:00", pages[0])
        assertEquals("2026-11-01 01:00:00", pages[1], "next page includes the oldest second (ties) — duplicates are dropped by id")
        assertEquals(101, s.tasks.count { it.status == 2 })
        assertEquals("2000-01-01 00:00:00", requests.first { it.url.encodedPath.endsWith("completed") }.url.parameters["from"])
    }

    @Test
    fun htmlLoginPageMeansCookieExpired() = runTest {
        val e = assertFailsWith<TickTickException> {
            api { req ->
                if (req.url.encodedPath == "/api/v2/batch/check/0") {
                    respond("<!DOCTYPE html><html>sign in</html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                } else null
            }.snapshot()
        }
        assertEquals(TickTickErrorKind.COOKIE_EXPIRED, e.kind)
        val unauthorized = assertFailsWith<TickTickException> {
            api { req -> if (req.url.encodedPath == "/api/v2/batch/check/0") json("{}", HttpStatusCode.Unauthorized) else null }.snapshot()
        }
        assertEquals(TickTickErrorKind.COOKIE_EXPIRED, unauthorized.kind)
        val noInbox = assertFailsWith<TickTickException> {
            api { req -> if (req.url.encodedPath == "/api/v2/batch/check/0") json("""{"projectProfiles":[]}""") else null }.snapshot()
        }
        assertEquals(TickTickErrorKind.COOKIE_EXPIRED, noInbox.kind)
    }

    @Test
    fun failingCompletedEndpointFailsTheSnapshot() = runTest {
        val e = assertFailsWith<TickTickException> {
            api { req -> if (req.url.encodedPath == "/api/v2/project/all/completed") json("oops", HttpStatusCode.InternalServerError) else null }.snapshot()
        }
        assertEquals(TickTickErrorKind.UNEXPECTED, e.kind)
    }

    @Test
    fun optionalEndpointsAreTolerated() = runTest {
        val optional = setOf("/api/v2/countdown/list", "/api/v2/habits", "/api/v2/column", "/api/v2/project/all/closed", "/api/v2/project/all/trash/pagination")
        val s = api { req -> if (req.url.encodedPath in optional) respond("gone", HttpStatusCode.NotFound) else null }.snapshot()
        assertFalse(s.countdownsAvailable || s.habitsAvailable || s.columnsAvailable || s.wontDoAvailable || s.trashAvailable)
        assertEquals(setOf("t1", "t2", "c1"), s.tasks.map { it.id }.toSet())
    }

    @Test
    fun openTaskWinsOverItsCompletedDuplicate() = runTest {
        val s = api { req ->
            if (req.url.encodedPath == "/api/v2/project/all/completed") json("[${completedTask("t1", "2026-11-01T01:00:00.000+0000")}]") else null
        }.snapshot()
        assertEquals(1, s.tasks.count { it.id == "t1" })
        assertEquals(0, s.tasks.single { it.id == "t1" }.status)
    }

    @Test
    fun fullPageWithNothingNewMarksCompletedAsTruncated() = runTest {
        val sameSecond = "[" + (0 until 100).joinToString(",") { completedTask("s$it", "2026-11-01T01:00:00.000+0000") } + "]"
        val s = api { req -> if (req.url.encodedPath == "/api/v2/project/all/completed") json(sameSecond) else null }.snapshot()
        assertTrue(s.completedTruncated)
        assertEquals(100, s.tasks.count { it.status == 2 })
        assertFalse(api().snapshot().completedTruncated)
    }

    @Test
    fun completedOnlyTasksAreMarkedThin() = runTest {
        val s = api().snapshot()
        assertEquals(setOf("c1", "w1"), s.thinTaskIds)
    }
}
