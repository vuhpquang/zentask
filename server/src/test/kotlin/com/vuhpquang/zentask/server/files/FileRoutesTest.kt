package com.vuhpquang.zentask.server.files

import com.vuhpquang.zentask.server.loginToken
import com.vuhpquang.zentask.server.module
import com.vuhpquang.zentask.server.store.ServerDb
import com.vuhpquang.zentask.server.testConfig
import com.vuhpquang.zentask.sync.SyncRecord
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileRoutesTest {
    @Test
    fun uploadDownloadAndMissing() = testApplication {
        val dir = Files.createTempDirectory("files")
        application { module(testConfig(dir)) }
        val token = client.loginToken()
        val bytes = "hello attachment".encodeToByteArray()
        val id = FileStore.sha256(bytes)
        assertEquals(HttpStatusCode.Unauthorized, client.put("/v1/files/$id") { setBody(bytes) }.status)
        val missing = client.post("/v1/files/missing") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"ids":["$id"]}""") }.bodyAsText()
        assertTrue(missing.contains(id), missing)
        assertEquals(HttpStatusCode.Created, client.put("/v1/files/$id") { bearerAuth(token); setBody(bytes) }.status)
        assertEquals(HttpStatusCode.NoContent, client.put("/v1/files/$id") { bearerAuth(token); setBody(bytes) }.status, "idempotent")
        assertContentEquals(bytes, client.get("/v1/files/$id") { bearerAuth(token) }.bodyAsBytes())
        // Wrong hash and oversize are refused.
        val other = FileStore.sha256("x".encodeToByteArray())
        assertEquals(HttpStatusCode.BadRequest, client.put("/v1/files/$other") { bearerAuth(token); setBody(bytes) }.status)
        val big = ByteArray((FileStore.MAX_FILE_BYTES + 1).toInt())
        assertEquals(HttpStatusCode.PayloadTooLarge, client.put("/v1/files/${FileStore.sha256(big)}") { bearerAuth(token); setBody(big) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/files/$other") { bearerAuth(token) }.status)
    }

    @Test
    fun garbageCollectionKeepsReferencedAndRecentFiles() {
        val dir = Files.createTempDirectory("gc")
        val db = ServerDb(dir.resolve("zentask.db"))
        val root = dir.resolve("files")
        val store = DiskFileStore(root)
        val kept = "kept".encodeToByteArray().let { b -> FileStore.sha256(b).also { store.write(it, b) } }
        val orphan = "orphan".encodeToByteArray().let { b -> FileStore.sha256(b).also { store.write(it, b) } }
        val fresh = "fresh".encodeToByteArray().let { b -> FileStore.sha256(b).also { store.write(it, b) } }
        db.apply(listOf(SyncRecord("attachment", "a1", mapOf("id" to JsonPrimitive("a1"), "fileId" to JsonPrimitive(kept)), mapOf("fileId" to "0001-0000-n"))))
        val old = FileTime.fromMillis(System.currentTimeMillis() - 30L * 24 * 3_600_000)
        for (id in listOf(kept, orphan)) Files.setLastModifiedTime(root.resolve(id.take(2)).resolve(id), old)
        assertEquals(1, collectGarbage(db, store, root, System.currentTimeMillis()))
        assertTrue(store.has(kept)); assertTrue(store.has(fresh)); assertTrue(!store.has(orphan))
        db.close()
    }
}
