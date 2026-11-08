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

    @Test
    fun failedToolCallWritesNothing() = testApplication {
        server()
        val token = client.loginToken()
        val id = obj(client.tool(token, "create_task", """{"title":"Keep","notes":"original notes"}""").first)["id"]!!.jsonPrimitive.content
        assertTrue(client.tool(token, "update_task", """{"id":"$id","title":"RENAMED","priority":"urgent"}""").second)
        assertTrue(client.tool(token, "update_task", """{"id":"$id","notes":"partial","list":"no-such-list"}""").second)
        val t = obj(client.tool(token, "get_task", """{"id":"$id"}""").first)
        assertEquals("Keep", t["title"]!!.jsonPrimitive.content)
        assertEquals("original notes", t["notes"]!!.jsonPrimitive.content)
    }

    @Test
    fun tagToolsAcceptHashAndRejectUnknownTags() = testApplication {
        server()
        val token = client.loginToken()
        client.tool(token, "create_task", """{"title":"x","tags":["work"]}""")
        val (renamed, e1) = client.tool(token, "rename_tag", """{"name":"#work","new_name":"#job"}""")
        assertFalse(e1, renamed)
        assertEquals(listOf("job"), obj("{\"t\":" + client.tool(token, "list_tags").first + "}")["t"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertTrue(client.tool(token, "delete_tag", """{"name":"nope"}""").second)
        assertTrue(client.tool(token, "set_tag_color", """{"name":"nope","color":"#112233"}""").second)
        assertFalse(client.tool(token, "delete_tag", """{"name":"#job"}""").second)
    }

    @Test
    fun overlongTagIsRejectedBeforeItReachesTheServer() = testApplication {
        server()
        val token = client.loginToken()
        val (msg, err) = client.tool(token, "create_task", """{"title":"x","tags":["${"a".repeat(80)}"]}""")
        assertTrue(err, msg)
        assertEquals(0, obj(client.tool(token, "search_tasks", """{"status":"any"}""").first)["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun trashingATrashedTaskIsAnError() = testApplication {
        server()
        val token = client.loginToken()
        val id = obj(client.tool(token, "create_task", """{"title":"x"}""").first)["id"]!!.jsonPrimitive.content
        assertFalse(client.tool(token, "trash_task", """{"id":"$id"}""").second)
        assertTrue(client.tool(token, "trash_task", """{"id":"$id"}""").second)
        assertTrue(client.tool(token, "delete_task", """{"id":"$id"}""").second)
    }

    @Test
    fun mcpScopedTokenCannotUseTheSyncApi() = testApplication {
        server()
        val res = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"${com.vuhpquang.zentask.server.TEST_PASSWORD}","deviceName":"Claude Code","scope":"mcp"}""")
        }
        val token = obj(res.bodyAsText())["token"]!!.jsonPrimitive.content
        assertFalse(client.tool(token, "list_tags").second, "MCP works")
        val sync = client.post("/v1/sync") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"checkpoint":0,"changes":[]}""") }
        assertEquals(HttpStatusCode.Forbidden, sync.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/devices") { bearerAuth(token) }.status)
    }

    @Test
    fun datesWithOffsetAndBadTimezone() = testApplication {
        server()
        val token = client.loginToken()
        val t = obj(client.tool(token, "create_task", """{"title":"x","due":"2026-11-09T09:00+07:00"}""").first)
        assertEquals("2026-11-09T09:00", t["due"]!!.jsonPrimitive.content)
        assertTrue(client.tool(token, "create_task", """{"title":"x","due":"2026-11-09","timezone":"PST"}""").second)
    }

    @Test
    fun listToolsFlow() = testApplication {
        server()
        val token = client.loginToken()
        assertFalse(client.tool(token, "create_folder", """{"name":"Personal"}""").second)
        val list = obj(client.tool(token, "create_list", """{"name":"Trip","color":"#2E9E88","folder":"Personal"}""").first)
        assertEquals("Personal", list["folder"]!!.jsonPrimitive.content)
        assertFalse(client.tool(token, "create_section", """{"list":"Trip","name":"Packing"}""").second)
        val t = obj(client.tool(token, "create_task", """{"title":"Passport","list":"Trip"}""").first)["id"]!!.jsonPrimitive.content
        assertFalse(client.tool(token, "move_task_to_section", """{"task_id":"$t","section":"Packing"}""").second)
        assertTrue(client.tool(token, "move_task_to_section", """{"task_id":"$t","section":"Nope"}""").second)
        val updated = obj(client.tool(token, "update_list", """{"list":"Trip","new_name":"Japan trip","archived":true}""").first)
        assertEquals("Japan trip", updated["name"]!!.jsonPrimitive.content)
        assertTrue(updated["archived"]!!.jsonPrimitive.boolean)
        assertTrue(client.tool(token, "update_list", """{"list":"inbox","new_name":"x"}""").second, "the Inbox can't be renamed")
        assertFalse(client.tool(token, "trash_list", """{"list":"Japan trip"}""").second)
        assertEquals(1, obj(client.tool(token, "search_tasks", """{"status":"trashed"}""").first)["count"]!!.jsonPrimitive.int)
        assertFalse(client.tool(token, "restore_list", """{"list":"Japan trip"}""").second)
        assertEquals(0, obj(client.tool(token, "search_tasks", """{"status":"trashed"}""").first)["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun smartListTools() = testApplication {
        server()
        val token = client.loginToken()
        client.tool(token, "create_task", """{"title":"Hot","priority":"high"}""")
        client.tool(token, "create_task", """{"title":"Cold"}""")
        val (made, err) = client.tool(token, "create_smart_list", """{"name":"Urgent","priorities":["high"]}""")
        assertFalse(err, made)
        val lists = ZenJson.parseToJsonElement(client.tool(token, "list_smart_lists").first).jsonArray
        assertEquals("Urgent", lists.single().jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(1, lists.single().jsonObject["open"]!!.jsonPrimitive.int)
        val found = obj(client.tool(token, "search_tasks", """{"smart_list":"Urgent"}""").first)
        assertEquals(listOf("Hot"), found["tasks"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content })
    }

    @Test
    fun listChangesAreAuditedWithBeforeAndAfter() = testApplication {
        server()
        val token = client.loginToken()
        client.tool(token, "create_list", """{"name":"Trip"}""")
        client.tool(token, "update_list", """{"list":"Trip","new_name":"Japan"}""")
        val db = ServerDb(dir.resolve("zentask.db"))
        val (tool, changes) = db.auditRows().last()
        db.close()
        assertEquals("update_list", tool)
        assertTrue(changes.contains("\"Trip\"") && changes.contains("\"Japan\""), changes)
    }

    @Test
    fun listEventsReadsCalendarRecords() = testApplication {
        server()
        val token = client.loginToken()
        client.tool(token, "list_tags") // start the app (creates the db)
        val db = ServerDb(dir.resolve("zentask.db"))
        db.writeServer("calendarAccount", "acc", mapOf("id" to JsonPrimitive("acc"), "email" to JsonPrimitive("me@x")))
        db.writeServer("calendarSource", "s1", mapOf("id" to JsonPrimitive("s1"), "accountId" to JsonPrimitive("acc"), "name" to JsonPrimitive("Work")))
        db.writeServer(
            "calendarEvent", "e1",
            mapOf(
                "id" to JsonPrimitive("e1"), "sourceId" to JsonPrimitive("s1"), "title" to JsonPrimitive("Standup"),
                "start" to JsonPrimitive(1_794_189_600_000L), "end" to JsonPrimitive(1_794_190_500_000L), "location" to JsonPrimitive("Room 1"),
            ),
        )
        db.close()
        val (text, err) = client.tool(token, "list_events", """{"from":"2026-11-09","to":"2026-11-09"}""")
        assertFalse(err, text)
        val events = obj(text)["events"]!!.jsonArray.map { it.jsonObject }
        assertEquals("Standup", events.single()["title"]!!.jsonPrimitive.content)
        assertEquals("Work", events.single()["calendar"]!!.jsonPrimitive.content)
        assertEquals("2026-11-09T09:00", events.single()["start"]!!.jsonPrimitive.content)
        assertEquals(0, obj(client.tool(token, "list_events", """{"from":"2026-11-10","to":"2026-11-11"}""").first)["events"]!!.jsonArray.size)
        // An all-day event on the 8th ends exactly at the 9th's midnight: not part of the 9th.
        val db2 = ServerDb(dir.resolve("zentask.db"))
        db2.writeServer("calendarEvent", "e2", mapOf("id" to JsonPrimitive("e2"), "sourceId" to JsonPrimitive("s1"), "title" to JsonPrimitive("Yesterday"), "isAllDay" to JsonPrimitive(true), "start" to JsonPrimitive(1_794_070_800_000L), "end" to JsonPrimitive(1_794_157_200_000L)))
        db2.close()
        assertEquals(listOf("Standup"), obj(client.tool(token, "list_events", """{"from":"2026-11-09","to":"2026-11-09"}""").first)["events"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content })
    }

    @Test
    fun habitAndCountdownTools() = testApplication {
        server()
        val token = client.loginToken()
        client.tool(token, "list_tags") // start the app (creates the replica)
        val (made, err) = client.tool(token, "create_countdown", """{"name":"Trip","date":"2027-01-20"}""")
        assertFalse(err, made)
        val (bday, err2) = client.tool(token, "create_countdown", """{"name":"Mom","date":"1960-11-10","type":"birthday"}""")
        assertFalse(err2, bday)
        val cds = obj(client.tool(token, "list_countdowns").first)["countdowns"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Mom", "Trip"), cds.map { it["name"]!!.jsonPrimitive.content }, "soonest first")
        assertEquals(true, cds[0]["yearly"]!!.jsonPrimitive.boolean, "birthdays repeat")
        assertTrue(cds[0]["days_left"]!!.jsonPrimitive.int >= 0)
        assertTrue(client.tool(token, "create_countdown", """{"name":"Bad","date":"soon"}""").second)

        assertTrue(client.tool(token, "check_in_habit", """{"habit":"Nope"}""").second, "unknown habit is an error")
        val (h, herr) = client.tool(token, "create_habit", """{"name":"Water","goal":8,"unit":"cups"}""")
        assertFalse(herr, h)
        client.tool(token, "create_habit", """{"name":"Read","days":["MO","WE"]}""")
        val (c1, e1) = client.tool(token, "check_in_habit", """{"habit":"water","amount":3}""")
        assertFalse(e1, c1)
        assertEquals(3.0, obj(c1)["today_value"]!!.jsonPrimitive.content.toDouble())
        client.tool(token, "check_in_habit", """{"habit":"Read","date":"2026-11-02"}""")
        client.tool(token, "check_in_habit", """{"habit":"Read","date":"2026-11-02"}""") // twice = still done, not undone
        val habits = obj(client.tool(token, "list_habits").first)["habits"]!!.jsonArray.map { it.jsonObject }
        val read = habits.single { it["name"]!!.jsonPrimitive.content == "Read" }
        assertEquals("Mon, Wed", read["schedule"]!!.jsonPrimitive.content)
        assertEquals(1, read["total_days"]!!.jsonPrimitive.int)
        assertTrue(client.tool(token, "check_in_habit", """{"habit":"water","amount":-2}""").second, "negative amount")
        val (half, he) = client.tool(token, "check_in_habit", """{"habit":"water","amount":0.5}""")
        assertFalse(he, half)
        assertEquals(3.5, obj(half)["today_value"]!!.jsonPrimitive.content.toDouble())
        client.tool(token, "create_habit", """{"name":"Read news"}""")
        val (amb, ambErr) = client.tool(token, "check_in_habit", """{"habit":"rea"}""")
        assertTrue(ambErr && amb.contains("several"), amb)
        val (undo, ue) = client.tool(token, "check_in_habit", """{"habit":"Read","date":"2026-11-02","undo":true}""")
        assertFalse(ue, undo)
        assertEquals(0, obj(client.tool(token, "list_habits").first)["habits"]!!.jsonArray.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == "Read" }["total_days"]!!.jsonPrimitive.int)
        val audit = ServerDb(dir.resolve("zentask.db")).let { d -> d.auditRows().also { d.close() } }
        assertTrue(audit.any { (tool, changes) -> tool == "check_in_habit" && changes.contains("habitCheckin") }, audit.toString())
    }
}
