package com.vuhpquang.zentask.sync

import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.model.ZenJson
import com.vuhpquang.zentask.store.ZenStore
import com.vuhpquang.zentask.store.createSqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.io.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SyncEngineTest {
    private var wall = 1_000L
    private val store = ZenStore(createSqlDriver(null), wall = { wall })
    private val requests = mutableListOf<SyncRequest>()

    private fun engine(handler: suspend MockRequestHandleScope.(SyncRequest) -> HttpResponseData): SyncEngine {
        val http = HttpClient(MockEngine { data: HttpRequestData ->
            val req = ZenJson.decodeFromString(SyncRequest.serializer(), (data.body as TextContent).text)
            requests += req
            assertEquals("Bearer tok", data.headers[HttpHeaders.Authorization])
            handler(req)
        })
        return SyncEngine(store, SyncApi(http, "https://example.test", token = { "tok" }), wall = { wall })
    }

    private fun MockRequestHandleScope.ok(resp: SyncResponse) = respond(
        ZenJson.encodeToString(SyncResponse.serializer(), resp),
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun echo(serverId: String = "srv", checkpoint: Long = 1) = SyncResponse(serverId, checkpoint, emptyList(), false)

    @Test
    fun pushesPendingAndAdvancesCheckpoint() = runTest {
        store.put(EntityTypes.task, Task(id = "t1", title = "a"))
        val remote = SyncRecord("task", "t2", mapOf("id" to kotlinx.serialization.json.JsonPrimitive("t2")), mapOf("id" to Hlc(5, 0, "b").encode()))
        val status = engine { ok(SyncResponse("srv", 9, listOf(remote), false)) }.syncOnce()
        assertIs<SyncStatus.Synced>(status)
        assertEquals(listOf("t1"), requests.single().changes.map { it.id })
        assertEquals(0L, requests.single().checkpoint)
        assertTrue(store.pending(10).isEmpty())
        assertEquals(9L, store.checkpoint)
        assertEquals("srv", store.serverId)
        assertEquals(setOf("t1", "t2"), store.list(EntityTypes.task).map { it.id }.toSet())
    }

    @Test
    fun failedPushKeepsOutboxAndCheckpoint() = runTest {
        store.put(EntityTypes.task, Task(id = "t1"))
        store.checkpoint = 4
        val down = engine { respond("down", HttpStatusCode.ServiceUnavailable) }.syncOnce()
        assertEquals(SyncErrorKind.SERVER, assertIs<SyncStatus.Failed>(down).kind)
        val offline = engine { throw IOException("offline") }.syncOnce()
        assertEquals(SyncErrorKind.NETWORK, assertIs<SyncStatus.Failed>(offline).kind)
        val garbage = engine { respond("not json", HttpStatusCode.OK) }.syncOnce()
        assertEquals(SyncErrorKind.SERVER, assertIs<SyncStatus.Failed>(garbage).kind)
        assertEquals(1, store.pending(10).size)
        assertEquals(4L, store.checkpoint)
    }

    @Test
    fun unauthorizedReportsKind() = runTest {
        store.put(EntityTypes.task, Task(id = "t1"))
        val e = engine { respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized) }
        val status = e.syncOnce()
        assertEquals(SyncErrorKind.UNAUTHORIZED, assertIs<SyncStatus.Failed>(status).kind)
        assertEquals(status, e.status.value)
        assertEquals(1, store.pending(10).size)
    }

    @Test
    fun editDuringInFlightSyncStaysPending() = runTest {
        store.put(EntityTypes.task, Task(id = "t1", title = "a"))
        var first = true
        val status = engine {
            if (first) {
                first = false
                wall += 1
                store.put(EntityTypes.task, store.get(EntityTypes.task, "t1")!!.copy(title = "during"))
            }
            ok(echo())
        }.syncOnce()
        assertIs<SyncStatus.Synced>(status)
        assertEquals(2, requests.size, "the edit made during the first request is pushed by a second one")
        assertEquals(kotlinx.serialization.json.JsonPrimitive("during"), requests[1].changes.single().fields["title"])
        assertTrue(store.pending(10).isEmpty())
    }

    @Test
    fun serverIdChangeReuploadsEverything() = runTest {
        store.put(EntityTypes.task, Task(id = "a"))
        store.put(EntityTypes.task, Task(id = "b"))
        store.markSynced(store.pending(10))
        store.serverId = "old"
        store.checkpoint = 50
        val status = engine { ok(echo(serverId = "new", checkpoint = 2)) }.syncOnce()
        assertIs<SyncStatus.Synced>(status)
        assertEquals(50L, requests[0].checkpoint)
        assertEquals(0L, requests[1].checkpoint)
        assertEquals(setOf("a", "b"), requests[1].changes.map { it.id }.toSet())
        assertEquals("new", store.serverId)
        assertTrue(store.pending(10).isEmpty())
    }

    @Test
    fun loopsUntilNoMore() = runTest {
        repeat(450) { store.put(EntityTypes.task, Task(id = "t$it")) }
        var call = 0
        val status = engine {
            call++
            ok(SyncResponse("srv", call.toLong(), emptyList(), hasMore = call == 3))
        }.syncOnce()
        assertIs<SyncStatus.Synced>(status)
        assertEquals(listOf(200, 200, 50, 0), requests.map { it.changes.size })
        assertEquals(listOf(0L, 1L, 2L, 3L), requests.map { it.checkpoint })
        assertEquals(4L, store.checkpoint)
    }
}
