package com.vuhpquang.zentask.ticktick

import com.vuhpquang.zentask.db.ZenTaskDatabase
import com.vuhpquang.zentask.model.Entity
import com.vuhpquang.zentask.model.EntityType
import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.TaskStatus
import com.vuhpquang.zentask.model.ZenJson
import com.vuhpquang.zentask.model.fromFields
import com.vuhpquang.zentask.model.toFields
import com.vuhpquang.zentask.store.ZenStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock

/** What a TickTick sync does (or would do). */
data class SyncPlan(
    val created: Int,
    val updated: Int,
    val trashed: Int,
    val restored: Int,
    /** Tags, folders, columns gone from TickTick: left alone in ZenTask. */
    val untouchedRemoved: Int,
    /** True when the trash count trips the mass-trash guard: [TickTickSync.apply] needs confirmation. */
    val needsConfirmation: Boolean,
)

class MassTrashException(val plan: SyncPlan) :
    IllegalStateException("TickTick sync would move ${plan.trashed} items to Trash; confirm to continue")

/**
 * One-way TickTick → ZenTask merge against a per-record baseline (spec §10.3):
 * - a field TickTick changed since the baseline overwrites ZenTask; other fields keep ZenTask's value;
 * - new records are created;
 * - records gone from TickTick go to Trash (tasks, lists) or get archived (habits, countdowns) — never a
 *   permanent delete — and come back if they reappear;
 * - records without a TickTick id are never touched.
 * Repeating a sync with unchanged data writes nothing.
 */
class TickTickSync(
    private val store: ZenStore,
    private val wall: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    val lastSyncAt: Long?
        get() = store.inTransaction { it.tickTickQueries.ticktickMeta(KEY_LAST_SYNC).executeAsOneOrNull()?.toLongOrNull() }

    /** True once any TickTick data was imported through the API (the CSV import is then refused). */
    val hasApiData: Boolean
        get() = store.inTransaction { it.tickTickQueries.ticktickMeta(KEY_SOURCE).executeAsOneOrNull() == SOURCE_API }

    fun preview(snapshot: TickTickSnapshot): SyncPlan = store.inTransaction { db -> plan(db, snapshot).summary }

    /** Applies the sync in one transaction. Throws [MassTrashException] when the guard trips unless [confirmMassTrash]. */
    fun apply(snapshot: TickTickSnapshot, confirmMassTrash: Boolean = false): SyncPlan =
        apply(snapshot, confirmMassTrash, SOURCE_API)

    /**
     * One-off import of a CSV backup ([TickTickCsv]). Refused once TickTick data came in through the API:
     * CSV ids never match real TickTick ids, so mixing the two would duplicate tasks. Re-importing the
     * same file is harmless (content-derived ids).
     */
    fun importCsv(snapshot: TickTickSnapshot): SyncPlan {
        check(!hasApiData) { "TickTick data was already synced with the cookie; a CSV import would duplicate tasks" }
        return apply(snapshot, confirmMassTrash = true, source = SOURCE_CSV)
    }

    internal fun apply(snapshot: TickTickSnapshot, confirmMassTrash: Boolean, source: String): SyncPlan = store.inTransaction { db ->
        val plan = plan(db, snapshot)
        if (plan.summary.needsConfirmation && !confirmMassTrash) throw MassTrashException(plan.summary)
        val now = wall()
        for (a in plan.writes) writeFields(a.type, a.id, a.fields, a.isNew)
        for (g in plan.gone) {
            when (g.action) {
                GoneAction.TRASH -> writeFields(g.type, g.id, mapOf("trashedAt" to JsonPrimitive(now)), false)
                GoneAction.ARCHIVE -> writeFields(g.type, g.id, mapOf("archivedAt" to JsonPrimitive(now)), false)
                GoneAction.DELETE -> deleteRecord(g.type, g.id)
                GoneAction.NONE -> Unit
            }
            db.tickTickQueries.baselineMarkGone(g.type.name, g.id)
        }
        for ((key, fields) in plan.baselines) {
            db.tickTickQueries.baselinePut(key.first, key.second, ZenJson.encodeToString(JsonObject.serializer(), JsonObject(fields)), 0)
        }
        db.tickTickQueries.ticktickMetaPut(KEY_LAST_SYNC, now.toString())
        if (source == SOURCE_API || db.tickTickQueries.ticktickMeta(KEY_SOURCE).executeAsOneOrNull() == null) {
            db.tickTickQueries.ticktickMetaPut(KEY_SOURCE, source)
        }
        plan.summary
    }

    // ---- planning ----

    private enum class GoneAction { TRASH, ARCHIVE, DELETE, NONE }

    private class Write(val type: EntityType<*>, val id: String, val fields: Map<String, JsonElement>, val isNew: Boolean)
    private class Gone(val type: EntityType<*>, val id: String, val action: GoneAction)
    private class Plan(
        val writes: List<Write>,
        val gone: List<Gone>,
        val baselines: Map<Pair<String, String>, Map<String, JsonElement>>,
        val summary: SyncPlan,
    )

    private class Baseline(val fields: Map<String, JsonElement>, val gone: Boolean)

    private fun plan(db: ZenTaskDatabase, s: TickTickSnapshot): Plan {
        val baselines = db.tickTickQueries.baselineAll().executeAsList().associate {
            (it.entity to it.id) to Baseline(ZenJson.parseToJsonElement(it.fields).jsonObject, it.gone == 1L)
        }
        val mapped = TickTickMapper.map(s)
        val writes = mutableListOf<Write>()
        val newBaselines = LinkedHashMap<Pair<String, String>, Map<String, JsonElement>>()
        var created = 0
        var updated = 0
        var restored = 0
        for (m in mapped) {
            val key = m.type.name to m.entity.id
            if (key in newBaselines) continue // the same record twice in one snapshot: first wins
            @Suppress("UNCHECKED_CAST")
            val fields = (m.type as EntityType<Entity>).toFields(m.entity) - VOLATILE
            newBaselines[key] = fields
            val base = baselines[key]
            val local = store.rawRecord(m.type.name, m.entity.id)
            if (local == null) {
                writes += Write(m.type, m.entity.id, fields, isNew = true)
                created++
                continue
            }
            val changed = fields.filter { (k, v) -> if (base == null) local.fields[k] != v else base.fields[k] != v }.toMutableMap()
            if (base?.gone == true) {
                restoreField(m.type)?.let { changed[it] = fields[it] ?: JsonPrimitive(null as String?) }
                restored++
            }
            if (changed.any { (k, v) -> local.fields[k] != v }) {
                writes += Write(m.type, m.entity.id, changed, isNew = false)
                if (base?.gone != true) updated++
            }
        }
        val presentTaskIds = mapped.filter { it.type == EntityTypes.task }.map { it.entity.id }.toSet()
        val gone = mutableListOf<Gone>()
        var trashed = 0
        var untouched = 0
        for ((key, base) in baselines) {
            if (key in newBaselines || base.gone) continue
            val type = EntityTypes.byName(key.first) ?: continue
            val action = goneAction(type, base, s, presentTaskIds)
            if (action == null) continue // its source was unavailable this time: no evidence it's gone
            gone += Gone(type, key.second, action)
            when (action) {
                GoneAction.TRASH, GoneAction.ARCHIVE, GoneAction.DELETE -> trashed++
                GoneAction.NONE -> untouched++
            }
        }
        val guarded = baselines.count { (k, b) -> !b.gone && (k.first == "task" || k.first == "project") }
        val trashCount = gone.count { it.action == GoneAction.TRASH }
        val needsConfirmation = trashCount > MASS_TRASH_MIN && trashCount > guarded * MASS_TRASH_SHARE
        return Plan(writes, gone, newBaselines, SyncPlan(created, updated, trashed, restored, untouched, needsConfirmation))
    }

    /** What to do with a record TickTick no longer returns; null = its source wasn't fetched, do nothing. */
    private fun goneAction(type: EntityType<*>, base: Baseline, s: TickTickSnapshot, presentTaskIds: Set<String>): GoneAction? =
        when (type) {
            EntityTypes.task -> {
                val status = (base.fields["status"] as? JsonPrimitive)?.intOrNull
                if (status == TaskStatus.WONT_DO && !s.wontDoAvailable) null else GoneAction.TRASH
            }
            EntityTypes.project -> GoneAction.TRASH
            EntityTypes.checklistItem -> {
                val taskId = (base.fields["taskId"] as? JsonPrimitive)?.content
                // Removed from a task that still exists: follow TickTick. If the whole task went, it is
                // trashed and keeps its items for a restore.
                if (taskId != null && taskId in presentTaskIds) GoneAction.DELETE else null
            }
            EntityTypes.habit -> if (s.habitsAvailable) GoneAction.ARCHIVE else null
            EntityTypes.countdown -> if (s.countdownsAvailable) GoneAction.ARCHIVE else null
            EntityTypes.habitCheckin -> null
            EntityTypes.column -> if (s.columnsAvailable) GoneAction.NONE else null
            else -> GoneAction.NONE // tags, folders: left alone, reported
        }

    private fun restoreField(type: EntityType<*>): String? = when (type) {
        EntityTypes.task, EntityTypes.project -> "trashedAt"
        EntityTypes.habit, EntityTypes.countdown -> "archivedAt"
        else -> null
    }

    // ---- writing through ZenStore (outbox + clocks) ----

    @Suppress("UNCHECKED_CAST")
    private fun writeFields(type: EntityType<*>, id: String, fields: Map<String, JsonElement>, isNew: Boolean) {
        val t = type as EntityType<Entity>
        if (isNew) {
            store.put(t, t.fromFields(fields))
            return
        }
        val current = store.get(t, id) ?: return // undecodable locally: leave it alone
        val merged = t.toFields(current) + fields
        store.put(t, t.fromFields(merged), base = current)
    }

    @Suppress("UNCHECKED_CAST")
    private fun deleteRecord(type: EntityType<*>, id: String) = store.delete(type as EntityType<Entity>, id)

    private companion object {
        const val KEY_LAST_SYNC = "lastSyncAt"
        const val KEY_SOURCE = "source"
        const val SOURCE_API = "api"
        const val SOURCE_CSV = "csv"
        const val MASS_TRASH_MIN = 10
        const val MASS_TRASH_SHARE = 0.2

        /** Fields the store manages itself; never part of a baseline comparison. */
        val VOLATILE = setOf("updatedAt")
    }
}
