package com.vuhpquang.zentask.server

import com.vuhpquang.zentask.model.ZenJson
import com.vuhpquang.zentask.server.sync.MAX_CHANGES
import com.vuhpquang.zentask.server.sync.PULL_PAGE
import com.vuhpquang.zentask.sync.Hlc
import com.vuhpquang.zentask.sync.SyncRecord
import com.vuhpquang.zentask.sync.SyncRequest
import com.vuhpquang.zentask.sync.SyncResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncRouteTest {
    private val now = 1_800_000_000_000L

    private fun clock(millis: Long = now - 1000, node: String = "a") = Hlc(millis, 0, node).encode()

    private fun task(id: String, vararg f: Pair<String, JsonElement>, at: String = clock()) = SyncRecord(
        "task", id,
        mapOf<String, JsonElement>("id" to JsonPrimitive(id)) + f,
        (listOf("id") + f.map { it.first }).associateWith { at },
    )

    private suspend fun HttpClient.sync(token: String?, checkpoint: Long, changes: List<SyncRecord> = emptyList()): HttpResponse =
        post("/v1/sync") {
            token?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(ZenJson.encodeToString(SyncRequest.serializer(), SyncRequest(checkpoint, changes)))
        }

    private suspend fun HttpResponse.body(): SyncResponse {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        return ZenJson.decodeFromString(SyncResponse.serializer(), bodyAsText())
    }

    private fun ApplicationTestBuilder.server() = application { module(testConfig(), now = { now }) }

    private suspend fun HttpClient.pullAll(token: String, from: Long = 0): Pair<List<SyncRecord>, Long> {
        val all = mutableListOf<SyncRecord>()
        var cp = from
        do {
            val page = sync(token, cp).body()
            assertTrue(page.changes.size <= PULL_PAGE)
            all += page.changes
            cp = page.checkpoint
        } while (page.hasMore)
        return all to cp
    }

    @Test
    fun pushThenPullReturnsRecord() = testApplication {
        server()
        val token = client.loginToken()
        val first = client.sync(token, 0, listOf(task("t1", "title" to JsonPrimitive("hello")))).body()
        assertEquals(listOf("t1"), first.changes.map { it.id })
        assertFalse(first.hasMore)
        assertTrue(first.serverId.isNotBlank())
        val again = client.sync(token, first.checkpoint).body()
        assertTrue(again.changes.isEmpty())
        assertEquals(first.checkpoint, again.checkpoint)
        val fresh = client.sync(client.loginToken("other"), 0).body()
        assertEquals(JsonPrimitive("hello"), fresh.changes.single().fields["title"])
    }

    @Test
    fun pullIsPagedAndComplete() {
        for (count in listOf(0, 500, 501, 1200)) testApplication {
            server()
            val token = client.loginToken()
            (0 until count).map { task("t$it") }.chunked(MAX_CHANGES).forEach { client.sync(token, Long.MAX_VALUE / 2, it) }
            val (all, cp) = client.pullAll(token)
            assertEquals(count, all.size, "count $count")
            assertEquals(count, all.map { it.id }.toSet().size)
            assertEquals(count.toLong(), cp)
        }
    }

    @Test
    fun mergeOnServerIsPerField() = testApplication {
        server()
        val token = client.loginToken()
        client.sync(token, 0, listOf(task("t1", "title" to JsonPrimitive("a"), "priority" to JsonPrimitive(0), at = clock(now - 5000))))
        val titleEdit = SyncRecord("task", "t1", mapOf("title" to JsonPrimitive("b")), mapOf("title" to clock(now - 1000, "b")))
        val priorityEdit = SyncRecord("task", "t1", mapOf("priority" to JsonPrimitive(5)), mapOf("priority" to clock(now - 2000, "c")))
        val staleTitle = SyncRecord("task", "t1", mapOf("title" to JsonPrimitive("stale")), mapOf("title" to clock(now - 9000, "d")))
        client.sync(token, 0, listOf(titleEdit))
        client.sync(token, 0, listOf(priorityEdit, staleTitle))
        val rec = client.pullAll(token).first.single()
        assertEquals(JsonPrimitive("b"), rec.fields["title"])
        assertEquals(JsonPrimitive(5), rec.fields["priority"])
    }

    private suspend fun ApplicationTestBuilder.assertRejected(code: String, vararg changes: SyncRecord, checkpoint: Long = 0) {
        val token = client.loginToken()
        val res = client.sync(token, checkpoint, changes.toList())
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("""{"error":"$code"}""", res.bodyAsText())
    }

    @Test
    fun rejectsServerOwnedEntity() = testApplication {
        server()
        assertRejected("bad_request", task("e1").copy(entity = "calendarEvent"))
    }

    @Test
    fun rejectsUnknownEntity() = testApplication {
        server()
        assertRejected("bad_request", task("x").copy(entity = "nope"))
    }

    @Test
    fun rejectsBadIds() = testApplication {
        server()
        assertRejected("bad_request", task("").copy(id = ""))
        assertRejected("bad_request", task("x".repeat(65)))
        assertRejected("bad_request", task("t1").copy(fields = mapOf("id" to JsonPrimitive("t2"))))
        assertRejected("bad_request", task("t1"), checkpoint = -1)
    }

    @Test
    fun rejectsFieldWithoutClock() = testApplication {
        server()
        val noClock = task("t1", "title" to JsonPrimitive("a")).let { it.copy(clocks = it.clocks - "title") }
        assertRejected("bad_request", noClock)
        val badClock = task("t1", "title" to JsonPrimitive("a")).let { it.copy(clocks = it.clocks + ("title" to "yesterday")) }
        assertRejected("bad_request", badClock)
    }

    @Test
    fun rejectsFarFutureClock() = testApplication {
        server()
        assertRejected("clock_skew", task("t1", at = clock(now + 25 * 3_600_000L)))
        val token = client.loginToken()
        assertEquals(HttpStatusCode.OK, client.sync(token, 0, listOf(task("t1", at = clock(now + 23 * 3_600_000L)))).status)
    }

    @Test
    fun rejectsTooManyChanges() = testApplication {
        server()
        assertRejected("bad_request", *Array(MAX_CHANGES + 1) { task("t$it") })
    }

    @Test
    fun rejectedRequestAppliesNothing() = testApplication {
        server()
        val token = client.loginToken()
        val res = client.sync(token, 0, listOf(task("good1"), task("bad").copy(entity = "nope"), task("good2")))
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(client.pullAll(token).first.isEmpty())
    }

    @Test
    fun sameChangeTwiceDoesNotBumpVersion() = testApplication {
        server()
        val token = client.loginToken()
        val change = task("t1", "title" to JsonPrimitive("a"))
        val first = client.sync(token, 0, listOf(change)).body()
        val second = client.sync(token, first.checkpoint, listOf(change)).body()
        assertTrue(second.changes.isEmpty())
        assertEquals(first.checkpoint, second.checkpoint)
    }

    @Test
    fun malformedBodyIs400() = testApplication {
        server()
        val token = client.loginToken()
        val res = client.post("/v1/sync") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"checkpoint": "x"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun syncWithoutTokenIs401() = testApplication {
        server()
        assertEquals(HttpStatusCode.Unauthorized, client.sync(null, 0).status)
        assertEquals(HttpStatusCode.Unauthorized, client.sync("nope", 0).status)
    }
}
