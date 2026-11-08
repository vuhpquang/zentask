package com.vuhpquang.zentask.ticktick

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Instant

enum class TickTickErrorKind { COOKIE_EXPIRED, NETWORK, UNEXPECTED }

class TickTickException(val kind: TickTickErrorKind, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Read-only client for TickTick's private web API v2, authenticated with the owner's session cookie
 * (`t`). Endpoints follow the web app (cross-checked with the open-source ticktick-py client).
 * Only GETs, plus the read-only check-in query POST. Never writes to TickTick.
 */
class TickTickApi(
    private val http: HttpClient,
    private val cookie: () -> String,
    base: String = "https://api.ticktick.com",
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val base = base.trimEnd('/')

    /** Everything needed for a sync. Required sources failing → [TickTickException]; optional ones → flag off. */
    suspend fun snapshot(): TickTickSnapshot {
        val batch = getJson("/api/v2/batch/check/0", TtBatch.serializer())
        if (batch.inboxId.isNullOrEmpty()) throw TickTickException(TickTickErrorKind.COOKIE_EXPIRED, "TickTick returned no account data")
        val completed = completedTasks("/api/v2/project/all/completed")
        val wontDo = optional { completedTasks("/api/v2/project/all/closed") { parameter("status", "Abandoned") } }
        val trash = optional { trashedIds() }
        val columns = optional { listOf("/api/v2/column", TtColumn.serializer()) { parameter("from", 0) } }
        val habits = optional { getJson("/api/v2/habits", ListSerializer(TtHabit.serializer())) }
        val checkins = habits?.takeIf { it.isNotEmpty() }?.let { h -> optional { checkins(h.map { it.id }) } }
        val countdowns = optional { listOf("/api/v2/countdown/list", TtCountdown.serializer()) }

        val tasks = LinkedHashMap<String, TtTask>()
        for (t in batch.syncTaskBean?.update.orEmpty() + completed + wontDo.orEmpty()) tasks.getOrPut(t.id) { t }
        return TickTickSnapshot.fromBatch(batch).copy(
            tasks = tasks.values.toList(),
            trashedTaskIds = trash.orEmpty(),
            columns = columns.orEmpty(),
            habits = habits.orEmpty(),
            checkins = checkins.orEmpty(),
            countdowns = countdowns.orEmpty(),
            wontDoAvailable = wontDo != null,
            trashAvailable = trash != null,
            columnsAvailable = columns != null,
            habitsAvailable = habits != null,
            countdownsAvailable = countdowns != null,
        )
    }

    /** Completed (or closed) tasks, 100 per page, walking back in time until a short page. */
    private suspend fun completedTasks(path: String, extra: HttpRequestBuilder.() -> Unit = {}): List<TtTask> {
        val all = LinkedHashMap<String, TtTask>()
        var to = now()
        repeat(MAX_PAGES) {
            val page = getJson(path, ListSerializer(TtTask.serializer())) {
                parameter("from", FROM)
                parameter("to", utc(to))
                parameter("limit", PAGE)
                extra()
            }
            page.forEach { all.getOrPut(it.id) { it } }
            if (page.size < PAGE) return all.values.toList()
            val oldest = page.mapNotNull { TickTickMapper.parseTime(it.completedTime) }.minOrNull() ?: return all.values.toList()
            val next = (oldest / 1000) * 1000 - 1000
            if (next >= to) return all.values.toList() // no progress: stop instead of looping
            to = next
        }
        return all.values.toList()
    }

    private suspend fun trashedIds(): Set<String> {
        val ids = LinkedHashSet<String>()
        var start = 0
        repeat(MAX_PAGES) {
            val root = getJson("/api/v2/project/all/trash/pagination", JsonElement.serializer()) {
                parameter("start", start)
                parameter("limit", PAGE)
            }
            val tasks = (root as? JsonObject)?.get("tasks") as? JsonArray ?: root as? JsonArray ?: JsonArray(emptyList())
            tasks.mapNotNullTo(ids) { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.content }
            if (tasks.size < PAGE) return ids
            start += tasks.size
        }
        return ids
    }

    private suspend fun checkins(habitIds: List<String>): List<TtCheckin> {
        val body = buildJsonObject {
            put("habitIds", buildJsonArray { habitIds.forEach { add(JsonPrimitive(it)) } })
            put("afterStamp", 20000101)
        }
        val root = request("/api/v2/habitCheckins/query", JsonElement.serializer()) {
            http.post(base + "/api/v2/habitCheckins/query") {
                headers()
                setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
        }
        val byHabit = (root as? JsonObject)?.get("checkins") as? JsonObject ?: return emptyList()
        return byHabit.values.flatMap { list ->
            (list as? JsonArray).orEmpty().mapNotNull { runCatching { TickTickJson.decodeFromJsonElement(TtCheckin.serializer(), it) }.getOrNull() }
        }
    }

    /** A list that TickTick returns either bare or wrapped as `{"update": [...]}` / `{"<anything>": [...]}`. */
    private suspend fun <T> listOf(path: String, item: KSerializer<T>, block: HttpRequestBuilder.() -> Unit = {}): List<T> {
        val root = getJson(path, JsonElement.serializer(), block)
        val array = root as? JsonArray
            ?: (root as? JsonObject)?.let { o -> (o["update"] ?: o.values.firstOrNull { it is JsonArray }) as? JsonArray }
            ?: throw TickTickException(TickTickErrorKind.UNEXPECTED, "$path: unexpected shape")
        return array.mapNotNull { runCatching { TickTickJson.decodeFromJsonElement(item, it) }.getOrNull() }
    }

    private suspend fun <T> getJson(path: String, out: KSerializer<T>, block: HttpRequestBuilder.() -> Unit = {}): T =
        request(path, out) {
            http.get(base + path) {
                headers()
                block()
            }
        }

    private suspend fun <T> request(path: String, out: KSerializer<T>, send: suspend () -> HttpResponse): T {
        val response: HttpResponse
        val text: String
        try {
            response = send()
            text = response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TickTickException(TickTickErrorKind.NETWORK, e.message ?: "network error", e)
        }
        val code = response.status.value
        val html = response.headers[HttpHeaders.ContentType].orEmpty().contains("html") || text.trimStart().startsWith("<")
        if (code == 401 || code == 403 || html) throw TickTickException(TickTickErrorKind.COOKIE_EXPIRED, "TickTick sign-in expired")
        if (code !in 200..299) throw TickTickException(TickTickErrorKind.UNEXPECTED, "$path: HTTP $code")
        return try {
            TickTickJson.decodeFromString(out, text)
        } catch (e: SerializationException) {
            throw TickTickException(TickTickErrorKind.UNEXPECTED, "$path: unreadable response", e)
        } catch (e: IllegalArgumentException) {
            throw TickTickException(TickTickErrorKind.UNEXPECTED, "$path: unreadable response", e)
        }
    }

    /** Optional source: null when unavailable. An expired cookie still fails the whole snapshot. */
    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (e: TickTickException) {
        if (e.kind == TickTickErrorKind.COOKIE_EXPIRED) throw e
        null
    }

    private fun HttpRequestBuilder.headers() {
        val value = cookie().trim()
        header(HttpHeaders.Cookie, if (value.contains('=')) value else "t=$value")
        header("x-device", X_DEVICE)
        header(HttpHeaders.UserAgent, USER_AGENT)
        header(HttpHeaders.Accept, "application/json")
    }

    private companion object {
        const val PAGE = 100
        const val MAX_PAGES = 500
        const val FROM = "2000-01-01 00:00:00"
        const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"
        const val X_DEVICE = """{"platform":"web","os":"macOS","device":"Safari 18.0","name":"","version":6070,"id":"6490a1b2c3d4e5f6a7b8c9d0","channel":"website","campaign":"","websocket":""}"""

        /** `yyyy-MM-dd HH:mm:ss` in UTC, the format the completed endpoints take. */
        fun utc(millis: Long): String = Instant.fromEpochMilliseconds(millis).toString().replace('T', ' ').take(19)
    }
}
