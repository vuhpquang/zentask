package com.vuhpquang.zentask.server.store

import com.vuhpquang.zentask.model.ZenJson
import com.vuhpquang.zentask.sync.Hlc
import com.vuhpquang.zentask.sync.HlcClock
import com.vuhpquang.zentask.sync.SyncRecord
import kotlinx.serialization.json.JsonElement
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

data class DeviceRow(val id: String, val name: String, val createdAt: Long, val lastSeenAt: Long, val revokedAt: Long?, val scope: String = "full")

/**
 * The server's SQLite database (`<dataDir>/zentask.db`): synced records, devices and a little metadata.
 * One connection, every method synchronized — a single user's traffic doesn't need more.
 */
class ServerDb(file: Path, private val now: () -> Long = System::currentTimeMillis) : AutoCloseable {
    private val conn: Connection

    /** Random id of this database; clients re-upload everything when it changes (restored/recreated DB). */
    @Volatile
    var serverId: String
        private set

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
                """CREATE TABLE IF NOT EXISTS calendar_credentials (
                    account_id TEXT PRIMARY KEY, provider TEXT NOT NULL, email TEXT NOT NULL,
                    secret_enc TEXT NOT NULL, created_at INTEGER NOT NULL, status TEXT NOT NULL)""",
            )
            st.execute("CREATE TABLE IF NOT EXISTS oauth_states (state TEXT PRIMARY KEY, device_id TEXT NOT NULL, created_at INTEGER NOT NULL)")
            st.execute(
                """CREATE TABLE IF NOT EXISTS mcp_audit (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, device_id TEXT NOT NULL,
                    tool TEXT NOT NULL, args TEXT NOT NULL, changes TEXT NOT NULL)""",
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS devices (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE,
                    created_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL, revoked_at INTEGER)""",
            )
        }
        // Migration (P3b): device scope; existing devices are full app devices.
        val hasScope = conn.createStatement().use { st -> st.executeQuery("PRAGMA table_info(devices)").use { rs -> generateSequence { if (rs.next()) rs.getString("name") else null }.toList() } }.contains("scope")
        if (!hasScope) conn.createStatement().use { it.execute("ALTER TABLE devices ADD COLUMN scope TEXT NOT NULL DEFAULT 'full'") }
        serverId = meta("serverId") ?: UUID.randomUUID().toString().also { setMeta("serverId", it) }
    }

    // ---- calendar (server-only secrets and server-written records) ----

    /** The server's own HLC node for records it writes (calendar data). */
    private val serverClock by lazy { HlcClock("server", now, meta("hlc")?.let(Hlc::parse)) }

    /**
     * Writes a server-owned record: only fields that differ from what is stored get a new clock, so an
     * unchanged calendar event costs nothing (no new version). Returns whether anything changed.
     */
    @Synchronized
    fun writeServer(entity: String, id: String, fields: Map<String, JsonElement>): Boolean {
        val existing = load(entity, id)
        val changed = fields.filter { (k, v) -> existing?.fields?.get(k) != v }
        if (changed.isEmpty()) return false
        val clock = serverClock.tick().encode()
        setMeta("hlc", clock)
        apply(listOf(SyncRecord(entity, id, changed, changed.mapValues { clock })))
        return true
    }

    /** Live server records of [entity] whose JSON [field] equals [value]. */
    @Synchronized
    fun recordsWhere(entity: String, field: String, value: String): List<SyncRecord> =
        conn.prepareStatement("SELECT * FROM records WHERE entity = ? AND json_extract(fields, ?) = ? AND coalesce(json_extract(fields, '$.deleted'), 0) = 0").use { st ->
            st.setString(1, entity); st.setString(2, "$.$field"); st.setString(3, value)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toRecord()) } }
        }

    data class Credential(val accountId: String, val provider: String, val email: String, val secretEnc: String, val status: String)

    @Synchronized
    fun saveCredential(c: Credential) {
        conn.prepareStatement("INSERT OR REPLACE INTO calendar_credentials(account_id, provider, email, secret_enc, created_at, status) VALUES (?, ?, ?, ?, ?, ?)").use {
            it.setString(1, c.accountId); it.setString(2, c.provider); it.setString(3, c.email); it.setString(4, c.secretEnc)
            it.setLong(5, now()); it.setString(6, c.status); it.executeUpdate()
        }
    }

    @Synchronized
    fun credentials(): List<Credential> =
        conn.prepareStatement("SELECT * FROM calendar_credentials ORDER BY created_at").use { st ->
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(Credential(rs.getString("account_id"), rs.getString("provider"), rs.getString("email"), rs.getString("secret_enc"), rs.getString("status")))
                }
            }
        }

    @Synchronized
    fun setCredentialStatus(accountId: String, status: String) {
        conn.prepareStatement("UPDATE calendar_credentials SET status = ? WHERE account_id = ?").use { it.setString(1, status); it.setString(2, accountId); it.executeUpdate() }
    }

    @Synchronized
    fun deleteCredential(accountId: String) {
        conn.prepareStatement("DELETE FROM calendar_credentials WHERE account_id = ?").use { it.setString(1, accountId); it.executeUpdate() }
    }

    @Synchronized
    fun putOAuthState(state: String, deviceId: String) {
        conn.prepareStatement("INSERT INTO oauth_states(state, device_id, created_at) VALUES (?, ?, ?)").use {
            it.setString(1, state); it.setString(2, deviceId); it.setLong(3, now()); it.executeUpdate()
        }
    }

    /** One-time use: returns the device that started the flow, or null if unknown or older than [maxAgeMs]. */
    @Synchronized
    fun takeOAuthState(state: String, maxAgeMs: Long): String? {
        val row = conn.prepareStatement("SELECT device_id, created_at FROM oauth_states WHERE state = ?").use { st ->
            st.setString(1, state)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to rs.getLong(2) else null }
        }
        conn.prepareStatement("DELETE FROM oauth_states WHERE state = ? OR created_at < ?").use {
            it.setString(1, state); it.setLong(2, now() - maxAgeMs); it.executeUpdate()
        }
        return row?.takeIf { now() - it.second <= maxAgeMs }?.first
    }

    /** Gives this database a new id (it was restored or recreated); clients then re-upload everything. */
    @Synchronized
    fun rotateServerId() {
        serverId = UUID.randomUUID().toString().also { setMeta("serverId", it) }
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
            runCatching { conn.rollback() }.exceptionOrNull()?.let(e::addSuppressed)
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

    /** Records one MCP write: which device, which tool, its arguments and the before/after of changed tasks. */
    @Synchronized
    fun audit(deviceId: String, tool: String, args: String, changes: String) {
        conn.prepareStatement("INSERT INTO mcp_audit(at, device_id, tool, args, changes) VALUES (?, ?, ?, ?, ?)").use {
            it.setLong(1, now()); it.setString(2, deviceId); it.setString(3, tool); it.setString(4, args); it.setString(5, changes)
            it.executeUpdate()
        }
    }

    /** (tool, changes JSON) in insertion order (tests, support). */
    @Synchronized
    fun auditRows(): List<Pair<String, String>> =
        conn.prepareStatement("SELECT tool, changes FROM mcp_audit ORDER BY id").use { st ->
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } }
        }

    /** Audit tools in insertion order (tests, support). */
    @Synchronized
    fun auditTools(): List<String> =
        conn.prepareStatement("SELECT tool FROM mcp_audit ORDER BY id").use { st ->
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

    @Synchronized
    fun createDevice(name: String, tokenHash: String, scope: String = "full"): String {
        val id = UUID.randomUUID().toString()
        val t = now()
        conn.prepareStatement("INSERT INTO devices(id, name, token_hash, created_at, last_seen_at, scope) VALUES (?, ?, ?, ?, ?, ?)").use {
            it.setString(1, id); it.setString(2, name); it.setString(3, tokenHash); it.setLong(4, t); it.setLong(5, t); it.setString(6, scope)
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
        scope = getString("scope") ?: "full",
    )
}
