package com.vuhpquang.zentask.server.files

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Attachment bytes, content-addressed: a file's id is the lowercase hex SHA-256 of its bytes, so an upload is
 * idempotent and can't be swapped. One storage back end (a folder on vps1's disk, `<dataDir>/files/ab/<sha>`);
 * an R2 bucket can implement the same interface later.
 */
interface FileStore {
    fun has(id: String): Boolean
    fun read(id: String): ByteArray?
    /** Stores [bytes] under [id]; false when [id] isn't their SHA-256 (nothing is written). */
    fun write(id: String, bytes: ByteArray): Boolean
    fun delete(id: String)
    fun ids(): Sequence<String>
    /** Bytes stored in total. */
    fun usedBytes(): Long

    companion object {
        const val MAX_FILE_BYTES = 10L * 1024 * 1024
        private val ID = Regex("^[0-9a-f]{64}$")
        fun isId(id: String) = ID.matches(id)
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** The folder is created with the first file (an empty server has none). */
class DiskFileStore(private val root: Path) : FileStore {
    private fun path(id: String): Path {
        require(FileStore.isId(id)) { "bad file id" }
        return root.resolve(id.take(2)).resolve(id)
    }

    override fun has(id: String) = FileStore.isId(id) && path(id).exists()

    override fun read(id: String): ByteArray? = if (has(id)) Files.readAllBytes(path(id)) else null

    override fun write(id: String, bytes: ByteArray): Boolean {
        if (!FileStore.isId(id) || FileStore.sha256(bytes) != id) return false
        val target = path(id)
        if (target.exists()) return true
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, ".up-", ".tmp")
        Files.write(tmp, bytes)
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return true
    }

    override fun delete(id: String) { if (FileStore.isId(id)) Files.deleteIfExists(path(id)) }

    override fun ids(): Sequence<String> = if (!root.exists()) emptySequence() else
        Files.walk(root).use { s -> s.filter { it.isRegularFile() && FileStore.isId(it.name) }.map { it.name }.toList() }.asSequence()

    override fun usedBytes(): Long = if (!root.exists()) 0L else
        Files.walk(root).use { s -> s.filter { it.isRegularFile() }.mapToLong { it.fileSize() }.sum() }

    /** Free space on the disk holding the files. */
    fun freeBytes(): Long = Files.getFileStore(generateSequence(root) { it.parent }.first { it.exists() }).usableSpace
}
