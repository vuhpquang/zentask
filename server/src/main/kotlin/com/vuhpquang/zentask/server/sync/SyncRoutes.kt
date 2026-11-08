package com.vuhpquang.zentask.server.sync

import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.server.store.ServerDb
import com.vuhpquang.zentask.sync.ApiError
import com.vuhpquang.zentask.sync.Hlc
import com.vuhpquang.zentask.sync.MAX_CLOCK_AHEAD_MS
import com.vuhpquang.zentask.sync.RejectedChange
import com.vuhpquang.zentask.sync.SyncRecord
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

private const val MAX_ID_LENGTH = 64

/**
 * `POST /sync` (mounted under `/v1`, behind device auth): push changes, pull one page after the checkpoint.
 * Problems with the request itself are a 400 that applies nothing; a bad individual change is listed in
 * `rejected` while the others are applied, so one bad record can never block a device's sync.
 */
fun Route.syncRoutes(db: ServerDb, now: () -> Long) {
    post("/sync") {
        val req = call.receive<SyncRequest>()
        if (req.checkpoint < 0 || req.changes.size > MAX_CHANGES) {
            return@post call.respond(HttpStatusCode.BadRequest, ApiError("bad_request"))
        }
        val t = now()
        val rejected = req.changes.mapNotNull { c -> rejection(c, t)?.let { RejectedChange(c.entity, c.id, it) } }
        val refused = rejected.map { it.entity to it.id }.toSet()
        db.apply(req.changes.filter { (it.entity to it.id) !in refused })
        // A checkpoint this database never issued means it went back in time (restored from a backup):
        // a new serverId makes every client re-upload what the backup lost.
        if (req.checkpoint > db.currentVersion()) db.rotateServerId()
        val (rows, hasMore) = db.changesSince(req.checkpoint, PULL_PAGE)
        call.respond(
            SyncResponse(
                serverId = db.serverId,
                checkpoint = rows.lastOrNull()?.first ?: req.checkpoint,
                changes = rows.map { it.second },
                hasMore = hasMore,
                rejected = rejected,
            ),
        )
    }
}

/** Why [c] can't be applied, or null when it can. */
internal fun rejection(c: SyncRecord, now: Long): String? {
    if (EntityTypes.byName(c.entity)?.clientWritable != true) return "bad_request"
    if (c.id.isBlank() || c.id.length > MAX_ID_LENGTH) return "bad_request"
    c.fields["id"]?.let { if (it != JsonPrimitive(c.id)) return "bad_request" }
    var skewed = false
    for (key in c.fields.keys) {
        val clock = c.clocks[key]?.let(Hlc::parse) ?: return "bad_request"
        if (clock.millis > now + MAX_CLOCK_AHEAD_MS) skewed = true
    }
    return if (skewed) "clock_skew" else null
}
