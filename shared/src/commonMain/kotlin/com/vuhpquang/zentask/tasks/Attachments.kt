package com.vuhpquang.zentask.tasks

import com.vuhpquang.zentask.model.Attachment
import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.store.ZenStore
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Attachment records (the bytes are moved by the platforms: upload to / download from `/v1/files`). */
class AttachmentActions(
    private val store: ZenStore,
    private val wall: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    companion object {
        const val MAX_BYTES = 10L * 1024 * 1024
        private val ID = Regex("^[0-9a-f]{64}$")
    }

    fun of(taskId: String): List<Attachment> =
        store.idsWhere(EntityTypes.attachment, "taskId", taskId).mapNotNull { store.get(EntityTypes.attachment, it) }
            .filter { !it.deleted }.sortedBy { it.createdAt }

    /** Records an attached file whose bytes are already in this device's cache under [fileId] (their SHA-256). */
    fun add(taskId: String, fileId: String, name: String, mime: String, size: Long, width: Int = 0, height: Int = 0): Attachment {
        require(ID.matches(fileId)) { "fileId must be a SHA-256" }
        require(size in 0..MAX_BYTES) { "Files are limited to 10 MB" }
        val a = Attachment(
            id = Uuid.random().toString(), createdAt = wall(), taskId = taskId, fileId = fileId,
            name = name.trim().ifEmpty { "file" }.take(200), mime = mime.ifBlank { "application/octet-stream" }, size = size, width = width, height = height,
        )
        store.put(EntityTypes.attachment, a)
        return a
    }

    fun rename(id: String, name: String) = store.update(EntityTypes.attachment, id) { it.copy(name = name.trim().take(200)) }

    fun remove(id: String) = store.delete(EntityTypes.attachment, id)

    /** File ids every live attachment needs (on tasks not deleted for good): what each device keeps cached. */
    fun neededFileIds(): Set<String> = store.list(EntityTypes.attachment).filter { !it.deleted }.mapTo(HashSet()) { it.fileId }

    /** Which of [needed] are missing from this device's cache [cached]: to download. */
    fun toDownload(cached: Set<String>): List<String> = (neededFileIds() - cached).sorted()
}
