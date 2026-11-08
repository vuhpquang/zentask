package com.vuhpquang.zentask.server.store

import com.vuhpquang.zentask.model.ZenJson
import com.vuhpquang.zentask.sync.SyncRecord
import com.vuhpquang.zentask.sync.merge
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

data class DeviceRow(val id: String, val name: String, val createdAt: Long, val lastSeenAt: Long, val revokedAt: Long?)

/**
 * The server's SQLite database (`<dataDir>/zentask.db`): synced records, devices and a little metadata.
 * One connection, every method synchronized — a single user's traffic doesn't need more.
 */
class ServerDb(file: Path, private val now: () -> Long = System::currentTimeMillis) : AutoCloseable {
    private val conn: Connection

    /** Random id of this database; clients re-upload everything when it changes (restored/recreated DB). */
    val serverId: String

    init {
        file.parent?.let { Files.createDirectories(it) }
        conn = DriverManager.getConnection("jdbc:sqlite:$file")
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA busy_timeout=5000")
            st.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            st.execute(
                """CREATE TABLE IF NOT EXISTS records (
                    entity TEXT NOT NULL, id TEXT NOT NULL, fields TEXT NOT NULL, clocks TEXT NOT NULL,
                    version INTEGER NOT NULL, PRIMARY KEY (entity, id))""",
            )
            st.execute("CREATE INDEX IF NOT EXISTS records_version ON records(version)")
            st.execute(
                """CREATE TABLE IF NOT EXISTS devices (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE,
                    created_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL, revoked_at INTEGER)""",
            )
        }
        serverId = meta("serverId") ?: UUID.randomUUID().toString().also { setMeta("serverId", it) }
    }

    /** The version of the last record write (0 for an empty database). */
    @Synchronized
    fun currentVersion(): Long = meta("version")?.toLong() ?: 0L

    /**
     * Merges [changes] into the stored records in one transaction. A record whose merged value differs
     * from what was stored gets the next version; an unchanged one keeps its version.
     */
    @Synchronized
    fun apply(changes: List<SyncRecord>) {
        if (changes.isEmpty()) return
        conn.autoCommit = false
        try {
            var version = currentVersion()
            for (change in changes) {
                val existing = load(change.entity, change.id)
                val merged = if (existing == null) merge(change, change) else merge(existing, change)
                if (merged == existing) continue
                version += 1
                conn.prepareStatement("INSERT OR REPLACE INTO records(entity, id, fields, clocks, version) VALUES (?, ?, ?, ?, ?)").use {
                    it.setString(1, merged.entity)
                    it.setString(2, merged.id)
                    it.setString(3, ZenJson.encodeToString(JsonObject.serializer(), JsonObject(merged.fields)))
                    it.setString(4, ZenJson.encodeToString(CLOCKS, merged.clocks))
                    it.setLong(5, version)
                    it.executeUpdate()
                }
            }
            setMeta("version", version.toString())
            conn.commit()
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    /** Records written after [checkpoint], oldest first, at most [limit]; second = more remain. */
    @Synchronized
    fun changesSince(checkpoint: Long, limit: Int): Pair<List<Pair<Long, SyncRecord>>, Boolean> =
        conn.prepareStatement("SELECT * FROM records WHERE version > ? ORDER BY version LIMIT ?").use { st ->
            st.setLong(1, checkpoint)
            st.setInt(2, limit + 1)
            val rows = st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong("version") to rs.toRecord()) } }
            rows.take(limit) to (rows.size > limit)
        }

    private fun load(entity: String, id: String): SyncRecord? =
        conn.prepareStatement("SELECT * FROM records WHERE entity = ? AND id = ?").use { st ->
            st.setString(1, entity); st.setString(2, id)
            st.executeQuery().use { rs -> if (rs.next()) rs.toRecord() else null }
        }

    private fun java.sql.ResultSet.toRecord() = SyncRecord(
        entity = getString("entity"),
        id = getString("id"),
        fields = ZenJson.parseToJsonElement(getString("fields")).jsonObject,
        clocks = ZenJson.decodeFromString(CLOCKS, getString("clocks")),
    )

    @Synchronized
    fun createDevice(name: String, tokenHash: String): String {
        val id = UUID.randomUUID().toString()
        val t = now()
        conn.prepareStatement("INSERT INTO devices(id, name, token_hash, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?)").use {
            it.setString(1, id); it.setString(2, name); it.setString(3, tokenHash); it.setLong(4, t); it.setLong(5, t)
            it.executeUpdate()
        }
        return id
    }

    @Synchronized
    fun deviceByTokenHash(hash: String): DeviceRow? =
        conn.prepareStatement("SELECT * FROM devices WHERE token_hash = ?").use { st ->
            st.setString(1, hash)
            st.executeQuery().use { rs -> if (rs.next()) rs.toDevice() else null }
        }

    @Synchronized
    fun touch(id: String) {
        conn.prepareStatement("UPDATE devices SET last_seen_at = ? WHERE id = ?").use {
            it.setLong(1, now()); it.setString(2, id); it.executeUpdate()
        }
    }

    /** Devices that are not revoked, oldest first. */
    @Synchronized
    fun listDevices(): List<DeviceRow> =
        conn.prepareStatement("SELECT * FROM devices WHERE revoked_at IS NULL ORDER BY created_at, id").use { st ->
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toDevice()) } }
        }

    /** False when there is no such active device. */
    @Synchronized
    fun revoke(id: String): Boolean =
        conn.prepareStatement("UPDATE devices SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL").use {
            it.setLong(1, now()); it.setString(2, id); it.executeUpdate() == 1
        }

    @Synchronized
    override fun close() = conn.close()

    private fun meta(key: String): String? =
        conn.prepareStatement("SELECT value FROM meta WHERE key = ?").use { st ->
            st.setString(1, key)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    private fun setMeta(key: String, value: String) {
        conn.prepareStatement("INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)").use {
            it.setString(1, key); it.setString(2, value); it.executeUpdate()
        }
    }

    private companion object {
        val CLOCKS = MapSerializer(String.serializer(), String.serializer())
    }

    private fun java.sql.ResultSet.toDevice() = DeviceRow(
        id = getString("id"),
        name = getString("name"),
        createdAt = getLong("created_at"),
        lastSeenAt = getLong("last_seen_at"),
        revokedAt = getLong("revoked_at").takeIf { !wasNull() },
    )
}
