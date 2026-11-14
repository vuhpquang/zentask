package com.vuhpquang.zentask.server.files

import com.vuhpquang.zentask.server.store.ServerDb
import com.vuhpquang.zentask.sync.ApiError
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable

@Serializable
data class MissingFilesRequest(val ids: List<String>)

@Serializable
data class MissingFilesResponse(val missing: List<String>)

/** Limits so attachments can't fill vps1's disk. */
data class FileLimits(val maxTotalBytes: Long = 4L * 1024 * 1024 * 1024, val minFreeBytes: Long = 1L * 1024 * 1024 * 1024)

/** `/v1/files`: upload (PUT, id = SHA-256), download (GET), and which of a list the server lacks (POST missing). */
fun Route.fileRoutes(store: FileStore, limits: FileLimits = FileLimits(), freeBytes: () -> Long = { Long.MAX_VALUE }) {
    post("/files/missing") {
        val req = call.receive<MissingFilesRequest>()
        if (req.ids.size > 1000) return@post call.respond(HttpStatusCode.BadRequest, ApiError("bad_request"))
        call.respond(MissingFilesResponse(req.ids.filter { FileStore.isId(it) && !store.has(it) }.distinct()))
    }
    put("/files/{id}") {
        val id = call.parameters["id"].orEmpty()
        if (!FileStore.isId(id)) return@put call.respond(HttpStatusCode.BadRequest, ApiError("bad_request"))
        if (store.has(id)) return@put call.respond(HttpStatusCode.NoContent)
        val declared = call.request.contentLength()
        if (declared != null && declared > FileStore.MAX_FILE_BYTES) return@put call.respond(HttpStatusCode.PayloadTooLarge, ApiError("too_large"))
        val bytes = call.receiveChannel().readRemaining(FileStore.MAX_FILE_BYTES + 1).readByteArray()
        if (bytes.size > FileStore.MAX_FILE_BYTES) return@put call.respond(HttpStatusCode.PayloadTooLarge, ApiError("too_large"))
        if (store.usedBytes() + bytes.size > limits.maxTotalBytes || freeBytes() - bytes.size < limits.minFreeBytes) {
            return@put call.respond(HttpStatusCode.InsufficientStorage, ApiError("storage_full"))
        }
        if (!store.write(id, bytes)) return@put call.respond(HttpStatusCode.BadRequest, ApiError("hash_mismatch"))
        call.respond(HttpStatusCode.Created)
    }
    get("/files/{id}") {
        val id = call.parameters["id"].orEmpty()
        val bytes = if (FileStore.isId(id)) store.read(id) else null
        if (bytes == null) return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found"))
        call.respondBytes(bytes, ContentType.Application.OctetStream)
    }
}

/**
 * Deletes stored files no live attachment points at (and older than [graceMs], so an upload whose record hasn't
 * synced yet survives). Returns how many were removed.
 */
fun collectGarbage(db: ServerDb, store: DiskFileStore, root: java.nio.file.Path, now: Long, graceMs: Long = 7 * 24 * 3_600_000L): Int {
    val used = db.liveFieldValues("attachment", "fileId")
    var removed = 0
    for (id in store.ids().toList()) {
        if (id in used) continue
        val p = root.resolve(id.take(2)).resolve(id)
        val age = now - java.nio.file.Files.getLastModifiedTime(p).toMillis()
        if (age > graceMs) { store.delete(id); removed++ }
    }
    return removed
}
