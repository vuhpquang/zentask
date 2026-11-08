package com.vuhpquang.zentask.server.store

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

    private fun java.sql.ResultSet.toDevice() = DeviceRow(
        id = getString("id"),
        name = getString("name"),
        createdAt = getLong("created_at"),
        lastSeenAt = getLong("last_seen_at"),
        revokedAt = getLong("revoked_at").takeIf { !wasNull() },
    )
}
