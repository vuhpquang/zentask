package com.vuhpquang.zentask.store

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.SqlDriver
import com.vuhpquang.zentask.db.Record
import com.vuhpquang.zentask.db.ZenTaskDatabase
import com.vuhpquang.zentask.model.Entity
import com.vuhpquang.zentask.model.EntityType
import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.ZenJson
import com.vuhpquang.zentask.model.fromFields
import com.vuhpquang.zentask.model.toFields
import com.vuhpquang.zentask.sync.CLOCK_CLAMP_MS
import com.vuhpquang.zentask.sync.Hlc
import com.vuhpquang.zentask.sync.HlcClock
import com.vuhpquang.zentask.sync.SyncRecord
import com.vuhpquang.zentask.sync.isDeleted
import com.vuhpquang.zentask.sync.maxClock
import com.vuhpquang.zentask.sync.merge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The client's local database. Every local write lands in the record table and the outbox in one
 * transaction (offline first); the sync engine drains the outbox and feeds server changes back
 * through [applyRemote]. Thread-safe: calls are serialised by one re-entrant lock.
 */
class ZenStore(
    private val driver: SqlDriver,
    private val wall: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /** A pending local change as handed to the sync engine; [seq] identifies this version of it. */
    data class Outgoing(val record: SyncRecord, val seq: Long)

    private val db = ZenTaskDatabase(driver)
    private val lock = StoreLock()
    private val localChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Ticks after every local write that changed something (not after [applyRemote]). */
    val changes: SharedFlow<Unit> = localChanges.asSharedFlow()

    /** This device's HLC node id, generated once per database. */
    val nodeId: String
    private val hlc: HlcClock

    init {
        nodeId = meta(KEY_NODE) ?: Uuid.random().toHexString().take(12).also { setMeta(KEY_NODE, it) }
        hlc = HlcClock(nodeId, wall, meta(KEY_HLC)?.let(Hlc::parse))
    }

    // ---- Typed API (UI) ----

    /**
     * Saves [value]. Fields that differ from the stored record get a new clock; `updatedAt` is set by the
     * store (and `createdAt` on a new record left at 0). Saving an unchanged value is a no-op. Fields this
     * client doesn't know (written by a newer client) are kept.
     *
     * Pass [base] — the value the edit started from — whenever [value] is an edited copy: only the fields
     * the edit changed are written, so a stale copy can't revert newer edits from other devices. Without
     * [base] every differing field is written (creating, importing). Prefer [update] for edits.
     */
    fun <T : Entity> put(type: EntityType<T>, value: T, base: T? = null): Unit = lock.withLock {
        require(type.clientWritable) { "${type.name} is server-owned" }
        var changed = false
        db.transaction {
            val existing = rawRecord(type.name, value.id)
            val incoming = type.toFields(value)
            val baseFields = base?.let { type.toFields(it) }
            val changedKeys = incoming.keys.filter {
                it != UPDATED_AT && existing?.fields?.get(it) != incoming[it] &&
                    (baseFields == null || baseFields[it] != incoming[it])
            }
            if (changedKeys.isEmpty()) return@transaction
            val now = wall()
            val clock = hlc.tick().encode()
            val fields = LinkedHashMap(existing?.fields.orEmpty())
            val clocks = LinkedHashMap(existing?.clocks.orEmpty())
            for (key in changedKeys) {
                fields[key] = incoming.getValue(key)
                clocks[key] = clock
            }
            if (existing == null && (incoming[CREATED_AT] as? JsonPrimitive)?.content == "0") {
                fields[CREATED_AT] = JsonPrimitive(now)
            }
            fields[UPDATED_AT] = JsonPrimitive(now)
            clocks[UPDATED_AT] = clock
            write(SyncRecord(type.name, value.id, fields, clocks))
            markPending(type.name, value.id)
            saveClock()
            changed = true
        }
        if (changed) localChanges.tryEmit(Unit)
    }

    /** Applies [transform] to the current value atomically; null when the record is missing or undecodable. */
    fun <T : Entity> update(type: EntityType<T>, id: String, transform: (T) -> T): T? = lock.withLock {
        val current = get(type, id) ?: return@withLock null
        put(type, transform(current), base = current)
        get(type, id)
    }

    /** Permanently deletes (tombstone). Moving to Trash is a normal [put] of `trashedAt`. */
    fun <T : Entity> delete(type: EntityType<T>, id: String): Unit = lock.withLock {
        val current = get(type, id) ?: return@withLock
        val fields = type.toFields(current).toMutableMap()
        fields[DELETED] = JsonPrimitive(true)
        put(type, type.fromFields(fields), base = current)
    }

    /** The record, including a tombstone; null when missing or not decodable by this client. */
    fun <T : Entity> get(type: EntityType<T>, id: String): T? =
        rawRecord(type.name, id)?.let { decode(type, it.fields) }

    /** Live (not deleted) records; rows this client can't decode are skipped, never dropped. */
    fun <T : Entity> list(type: EntityType<T>): List<T> =
        db.recordQueries.live(type.name).executeAsList().mapNotNull { decode(type, parseFields(it.fields)) }

    fun <T : Entity> observe(type: EntityType<T>): Flow<List<T>> =
        db.recordQueries.live(type.name).asFlow().mapToList(Dispatchers.Default)
            .map { rows -> rows.mapNotNull { decode(type, parseFields(it.fields)) } }

    /** Emits after any change to any record, local or from sync (drives UI reloads). */
    fun recordChanges(): Flow<Unit> = db.recordQueries.live("").asFlow().map { }

    // ---- Sync-facing API ----

    var checkpoint: Long
        get() = meta(KEY_CHECKPOINT)?.toLongOrNull() ?: 0L
        set(value) = setMeta(KEY_CHECKPOINT, value.toString())

    var serverId: String?
        get() = meta(KEY_SERVER_ID)
        set(value) = setMeta(KEY_SERVER_ID, value.orEmpty())

    fun rawRecord(entity: String, id: String): SyncRecord? =
        db.recordQueries.byId(entity, id).executeAsOneOrNull()?.toSyncRecord()

    fun pending(limit: Int): List<Outgoing> = lock.withLock {
        db.outboxQueries.pending(limit.toLong()).executeAsList().map {
            Outgoing(SyncRecord(it.entity, it.id, parseFields(it.fields), parseClocks(it.clocks)), it.seq)
        }
    }

    /** Clears outbox entries the server accepted, unless the record changed again since ([Outgoing.seq]). */
    fun markSynced(items: List<Outgoing>): Unit = lock.withLock {
        db.transaction { items.forEach { db.outboxQueries.clear(it.record.entity, it.record.id, it.seq) } }
    }

    /** Merges server records into the local database. Never touches the outbox. */
    fun applyRemote(records: List<SyncRecord>): Unit = lock.withLock {
        db.transaction {
            for (remote in records) {
                val existing = rawRecord(remote.entity, remote.id)
                val merged = if (existing == null) merge(remote, remote) else merge(existing, remote)
                if (merged != existing) write(merged)
                remote.maxClock()?.let(hlc::observe)
            }
            saveClock()
        }
    }

    /** Queues every client-writable record for upload. */
    fun markAllPending() {
        lock.withLock { db.transaction { db.outboxQueries.markAll(nextSeq(), WRITABLE) } }
    }

    /**
     * The server's database was replaced (new [newServerId]): queue everything for upload and pull from
     * the start — in one transaction, so a crash can't leave the new id stored without the re-upload.
     */
    fun resetForNewServer(newServerId: String) {
        lock.withLock {
            db.transaction {
                db.outboxQueries.markAll(nextSeq(), WRITABLE)
                setMeta(KEY_CHECKPOINT, "0")
                setMeta(KEY_SERVER_ID, newServerId)
            }
        }
    }

    /** The server refused this change: keep it, stop sending it until the record is edited again. */
    fun park(item: Outgoing, reason: String) {
        lock.withLock { db.outboxQueries.park(reason, item.record.entity, item.record.id, item.seq) }
    }

    /** Refused changes with the server's reason. */
    fun parked(): List<Pair<SyncRecord, String>> = lock.withLock {
        db.outboxQueries.parked().executeAsList().map {
            SyncRecord(it.entity, it.id, parseFields(it.fields), parseClocks(it.clocks)) to it.parked.orEmpty()
        }
    }

    /**
     * Gives fields whose clock is far in the future (a device clock that was wrong) a fresh clock, keeping
     * their values, and queues the record — the answer to a `clock_skew` rejection. False if nothing to do.
     */
    fun restampFuture(entity: String, id: String): Boolean = lock.withLock {
        var done = false
        db.transaction {
            val rec = rawRecord(entity, id) ?: return@transaction
            val limit = wall() + CLOCK_CLAMP_MS
            val future = rec.clocks.filterValues { (Hlc.parse(it)?.millis ?: 0) > limit }.keys
            if (future.isEmpty()) return@transaction
            val clock = hlc.tick().encode()
            write(rec.copy(clocks = rec.clocks + future.associateWith { clock }))
            markPending(entity, id)
            saveClock()
            done = true
        }
        done
    }

    fun close() = driver.close()

    /** Runs [block] under the store lock inside one transaction (for other modules of `shared`, e.g. TickTick sync). */
    internal fun <R> inTransaction(block: (ZenTaskDatabase) -> R): R = lock.withLock {
        db.transactionWithResult { block(db) }
    }

    // ---- Internals ----

    private fun write(r: SyncRecord) {
        db.recordQueries.upsert(
            r.entity, r.id,
            ZenJson.encodeToString(JsonObject.serializer(), JsonObject(r.fields)),
            ZenJson.encodeToString(CLOCKS, r.clocks),
            if (r.isDeleted) 1L else 0L,
        )
    }

    private fun markPending(entity: String, id: String) {
        db.outboxQueries.mark(entity, id, nextSeq())
    }

    private fun nextSeq(): Long {
        val next = (meta(KEY_OUTBOX_SEQ)?.toLongOrNull() ?: 0L) + 1
        setMeta(KEY_OUTBOX_SEQ, next.toString())
        return next
    }

    private fun saveClock() {
        hlc.last?.let { setMeta(KEY_HLC, it.encode()) }
    }

    private fun meta(key: String): String? = db.syncMetaQueries.valueOf(key).executeAsOneOrNull()?.takeIf { it.isNotEmpty() }

    private fun setMeta(key: String, value: String) {
        db.syncMetaQueries.put(key, value)
    }

    private fun <T : Entity> decode(type: EntityType<T>, fields: Map<String, JsonElement>): T? =
        try {
            type.fromFields(fields)
        } catch (_: IllegalArgumentException) {
            null // SerializationException is an IllegalArgumentException: a newer/odd record, keep the row.
        }

    private fun Record.toSyncRecord() = SyncRecord(entity, id, parseFields(fields), parseClocks(clocks))

    private companion object {
        const val KEY_NODE = "nodeId"
        const val KEY_HLC = "hlc"
        const val KEY_CHECKPOINT = "checkpoint"
        const val KEY_SERVER_ID = "serverId"
        const val KEY_OUTBOX_SEQ = "outboxSeq"
        const val CREATED_AT = "createdAt"
        const val UPDATED_AT = "updatedAt"
        const val DELETED = "deleted"
        val CLOCKS = MapSerializer(String.serializer(), String.serializer())
        val WRITABLE = EntityTypes.all.filter { it.clientWritable }.map { it.name }

        fun parseFields(text: String): Map<String, JsonElement> = ZenJson.parseToJsonElement(text).jsonObject
        fun parseClocks(text: String): Map<String, String> = ZenJson.decodeFromString(CLOCKS, text)
    }
}
