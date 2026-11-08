package com.vuhpquang.zentask.server.mcp

import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.ZenJson
import com.vuhpquang.zentask.server.loginToken
import com.vuhpquang.zentask.server.module
import com.vuhpquang.zentask.server.store.ServerDb
import com.vuhpquang.zentask.server.testConfig
import com.vuhpquang.zentask.store.ZenStore
import com.vuhpquang.zentask.store.createSqlDriver
import com.vuhpquang.zentask.sync.SyncApi
import com.vuhpquang.zentask.sync.SyncEngine
import com.vuhpquang.zentask.sync.SyncStatus
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpToolsTest {
    private var wall = 1_800_000_000_000L
    private var nextId = 1
    private lateinit var dir: Path

    private fun ApplicationTestBuilder.server() {
        dir = Files.createTempDirectory("mcp")
        application { module(testConfig(dir), now = { wall }) }
    }

    private suspend fun HttpClient.raw(token: String?, body: String): HttpResponse = post("/mcp") {
        token?.let { bearerAuth(it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.rpc(token: String, method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
        val body = buildJsonObject { put("jsonrpc", "2.0"); put("id", nextId++); put("method", method); put("params", params) }
        val res = raw(token, body.toString())
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return ZenJson.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    /** Calls a tool; returns (text, isError). */
    private suspend fun HttpClient.tool(token: String, name: String, args: String = "{}"): Pair<String, Boolean> {
        val r = rpc(token, "tools/call", buildJsonObject { put("name", name); put("arguments", ZenJson.parseToJsonElement(args)) })
        val result = r["result"]!!.jsonObject
        return result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content to result["isError"]!!.jsonPrimitive.boolean
    }

    private fun obj(text: String) = ZenJson.parseToJsonElement(text).jsonObject

    @Test
    fun initializeReturnsCapabilitiesAndSession() = testApplication {
        server()
        val token = client.loginToken("Claude Code")
        val res = client.raw(token, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""")
        assertEquals(HttpStatusCode.OK, res.status)
        assertNotNull(res.headers["Mcp-Session-Id"])
        val result = obj(res.bodyAsText())["result"]!!.jsonObject
        assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("zentask", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertNotNull(result["capabilities"]!!.jsonObject["tools"])
        val odd = client.raw(token, """{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}""")
        assertEquals("2025-06-18", obj(odd.bodyAsText())["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolsListHasSchemas() = testApplication {
        server()
        val token = client.loginToken()
        val tools = client.rpc(token, "tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject }
        val names = tools.map { it["name"]!!.jsonPrimitive.content }
        assertTrue(listOf("search_tasks", "create_task", "update_task", "complete_task", "delete_task", "list_lists", "rename_tag").all { it in names })
        tools.forEach { assertEquals("object", it["inputSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content) }
        val search = tools.single { it["name"]!!.jsonPrimitive.content == "search_tasks" }
        assertTrue(search["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun createSearchUpdateCompleteFlow() = testApplication {
        server()
        val token = client.loginToken()
        val (created, err) = client.tool(token, "create_task", """{"title":"Write MCP docs","due":"2026-11-09","priority":"high","tags":["work"],"notes":"for Claude"}""")
        assertFalse(err, created)
        val task = obj(created)
        val id = task["id"]!!.jsonPrimitive.content
        assertEquals("2026-11-09", task["due"]!!.jsonPrimitive.content)
        assertEquals("high", task["priority"]!!.jsonPrimitive.content)

        val found = obj(client.tool(token, "search_tasks", """{"query":"mcp DOCS"}""").first)
        assertEquals(1, found["count"]!!.jsonPrimitive.int)
        assertEquals(0, obj(client.tool(token, "search_tasks", """{"tag":"home"}""").first)["count"]!!.jsonPrimitive.int)

        val updated = obj(client.tool(token, "update_task", """{"id":"$id","title":"Write MCP guide","due":"2026-11-10T09:30","add_tags":["docs"],"remove_tags":["work"]}""").first)
        assertEquals("Write MCP guide", updated["title"]!!.jsonPrimitive.content)
        assertEquals("2026-11-10T09:30", updated["due"]!!.jsonPrimitive.content)
        assertEquals(listOf("docs"), updated["tags"]!!.jsonArray.map { it.jsonPrimitive.content })

        val full = obj(client.tool(token, "get_task", """{"id":"$id"}""").first)
        assertEquals("for Claude", full["notes"]!!.jsonPrimitive.content)

        assertEquals("completed", obj(client.tool(token, "complete_task", """{"id":"$id"}""").first)["status"]!!.jsonPrimitive.content)
        assertEquals("open", obj(client.tool(token, "reopen_task", """{"id":"$id"}""").first)["status"]!!.jsonPrimitive.content)
        val cleared = obj(client.tool(token, "update_task", """{"id":"$id","due":null}""").first)
        assertTrue("due" !in cleared)
    }

    @Test
    fun quickAddCreate() = testApplication {
        server()
        val token = client.loginToken()
        val t = obj(client.tool(token, "create_task", """{"quick_add":"Gọi mẹ ngày mai 8h tối #family !1"}""").first)
        assertEquals("Gọi mẹ", t["title"]!!.jsonPrimitive.content)
        assertEquals("high", t["priority"]!!.jsonPrimitive.content)
        assertEquals(listOf("family"), t["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(t["due"]!!.jsonPrimitive.content.endsWith("T20:00"))
    }

    @Test
    fun deleteTrashesUnlessPermanentAndPermanentNeedsTrash() = testApplication {
        server()
        val token = client.loginToken()
        val id = obj(client.tool(token, "create_task", """{"title":"Temp"}""").first)["id"]!!.jsonPrimitive.content
        val (refused, isErr) = client.tool(token, "delete_task", """{"id":"$id","permanent":true}""")
        assertTrue(isErr, refused)
        assertTrue(refused.contains("not in Trash"))
        val (trashed, _) = client.tool(token, "delete_task", """{"id":"$id"}""")
        assertTrue(trashed.startsWith("Moved to Trash"))
        assertEquals(1, obj(client.tool(token, "search_tasks", """{"status":"trashed"}""").first)["count"]!!.jsonPrimitive.int)
        val (gone, err2) = client.tool(token, "delete_task", """{"id":"$id","permanent":true}""")
        assertFalse(err2, gone)
        assertEquals(0, obj(client.tool(token, "search_tasks", """{"status":"any"}""").first)["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun badInputsAreToolErrors() = testApplication {
        server()
        val token = client.loginToken()
        val (unknown, e1) = client.tool(token, "get_task", """{"id":"nope"}""")
        assertTrue(e1); assertTrue(unknown.contains("No task with id nope"))
        val (date, e2) = client.tool(token, "create_task", """{"title":"x","due":"next tuesday"}""")
        assertTrue(e2); assertTrue(date.contains("Can't read the date"))
        val (prio, e3) = client.tool(token, "create_task", """{"title":"x","priority":"urgent"}""")
        assertTrue(e3, prio)
        val (type, e4) = client.tool(token, "update_task", """{"id":5}""")
        assertTrue(e4, type)
        val (list, e5) = client.tool(token, "create_task", """{"title":"x","list":"Nowhere"}""")
        assertTrue(e5); assertTrue(list.contains("list_lists"))
    }

    @Test
    fun protocolErrors() = testApplication {
        server()
        val token = client.loginToken()
        assertEquals(-32700, obj(client.raw(token, "{not json").bodyAsText())["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(-32600, obj(client.raw(token, "[1,2]").bodyAsText())["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(-32600, obj(client.raw(token, """{"id":1,"method":"ping"}""").bodyAsText())["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(-32601, client.rpc(token, "resources/list")["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        val unknownTool = client.rpc(token, "tools/call", buildJsonObject { put("name", "rm_rf") })
        assertEquals(-32602, unknownTool["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        val notification = client.raw(token, """{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(HttpStatusCode.Accepted, notification.status)
        assertEquals(HttpStatusCode.MethodNotAllowed, client.get("/mcp") { bearerAuth(token) }.status)
        assertIs<JsonObject>(client.rpc(token, "ping")["result"])
    }

    @Test
    fun noTokenIs401AndRevokedTokenToo() = testApplication {
        server()
        assertEquals(HttpStatusCode.Unauthorized, client.raw(null, """{"jsonrpc":"2.0","id":1,"method":"ping"}""").status)
        assertEquals(HttpStatusCode.Unauthorized, client.raw("garbage", """{"jsonrpc":"2.0","id":1,"method":"ping"}""").status)
    }

    @Test
    fun rateLimitAfter120CallsPerMinute() = testApplication {
        server()
        val token = client.loginToken()
        repeat(120) { assertFalse(client.tool(token, "list_tags").second) }
        val limited = client.rpc(token, "tools/call", buildJsonObject { put("name", "list_tags") })
        assertEquals(-32029, limited["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        wall += 60_000
        assertFalse(client.tool(token, "list_tags").second)
    }

    @Test
    fun auditOnlyForWrites() = testApplication {
        server()
        val token = client.loginToken()
        val id = obj(client.tool(token, "create_task", """{"title":"Audit me"}""").first)["id"]!!.jsonPrimitive.content
        client.tool(token, "search_tasks")
        client.tool(token, "get_task", """{"id":"$id"}""")
        client.tool(token, "complete_task", """{"id":"$id"}""")
        client.tool(token, "get_task", """{"id":"missing"}""")
        val db = ServerDb(dir.resolve("zentask.db"))
        assertEquals(listOf("create_task", "complete_task"), db.auditTools())
        db.close()
    }

    @Test
    fun deviceSeesMcpChangeAfterSync() = testApplication {
        server()
        val ai = client.loginToken("Claude Code")
        val macToken = client.loginToken("Mac")
        val mac = ZenStore(createSqlDriver(null), wall = { wall })
        val engine = SyncEngine(mac, SyncApi(client, "", token = { macToken }), wall = { wall })
        val id = obj(client.tool(ai, "create_task", """{"title":"Made by the AI","list":"inbox"}""").first)["id"]!!.jsonPrimitive.content
        assertIs<SyncStatus.Synced>(engine.syncOnce())
        assertEquals("Made by the AI", mac.get(EntityTypes.task, id)?.title)
        mac.update(EntityTypes.task, id) { it.copy(title = "Renamed on the Mac") }
        assertIs<SyncStatus.Synced>(engine.syncOnce())
        assertEquals("Renamed on the Mac", obj(client.tool(ai, "get_task", """{"id":"$id"}""").first)["title"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive("Made by the AI"), JsonPrimitive("Made by the AI"))
    }
}
