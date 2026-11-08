package com.vuhpquang.zentask.server.sync

import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.server.store.ServerDb
import com.vuhpquang.zentask.sync.ApiError
import com.vuhpquang.zentask.sync.Hlc
import com.vuhpquang.zentask.sync.SyncRequest
import com.vuhpquang.zentask.sync.SyncResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonPrimitive

/** Records per pull page. */
const val PULL_PAGE = 500

/** Most changes accepted in one request (clients push in batches of 200). */
const val MAX_CHANGES = 1000

/** A clock further ahead of server time than this is a broken device clock (P1 plan, clock-skew guard). */
const val MAX_CLOCK_AHEAD_MS = 24 * 60 * 60 * 1000L

private const val MAX_ID_LENGTH = 64

/** `POST /sync` (mounted under `/v1`, behind device auth): push changes, pull one page after the checkpoint. */
fun Route.syncRoutes(db: ServerDb, now: () -> Long) {
    post("/sync") {
        val req = call.receive<SyncRequest>()
        validate(req, now())?.let { return@post call.respond(HttpStatusCode.BadRequest, ApiError(it)) }
        db.apply(req.changes)
        val (rows, hasMore) = db.changesSince(req.checkpoint, PULL_PAGE)
        call.respond(
            SyncResponse(
                serverId = db.serverId,
                checkpoint = rows.lastOrNull()?.first ?: req.checkpoint,
                changes = rows.map { it.second },
                hasMore = hasMore,
            ),
        )
    }
}

/** The error code that rejects the whole request, or null when every change is acceptable. */
internal fun validate(req: SyncRequest, now: Long): String? {
    if (req.checkpoint < 0 || req.changes.size > MAX_CHANGES) return "bad_request"
    var skewed = false
    for (c in req.changes) {
        if (EntityTypes.byName(c.entity)?.clientWritable != true) return "bad_request"
        if (c.id.isBlank() || c.id.length > MAX_ID_LENGTH) return "bad_request"
        c.fields["id"]?.let { if (it != JsonPrimitive(c.id)) return "bad_request" }
        for (key in c.fields.keys) {
            val clock = c.clocks[key]?.let(Hlc::parse) ?: return "bad_request"
            if (clock.millis > now + MAX_CLOCK_AHEAD_MS) skewed = true
        }
    }
    return if (skewed) "clock_skew" else null
}
