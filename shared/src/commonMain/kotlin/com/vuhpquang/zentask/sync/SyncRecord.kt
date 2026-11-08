package com.vuhpquang.zentask.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * A record as it travels and is stored: its fields plus one encoded [Hlc] per field.
 * A field with no clock, or an unparsable one, counts as older than any valid clock.
 */
@Serializable
data class SyncRecord(
    val entity: String,
    val id: String,
    val fields: Map<String, JsonElement>,
    val clocks: Map<String, String>,
)

/** True when the tombstone field is set. */
val SyncRecord.isDeleted: Boolean
    get() = (fields["deleted"] as? JsonPrimitive)?.booleanOrNull == true

fun SyncRecord.maxClock(): Hlc? = clocks.values.mapNotNull(Hlc::parse).maxOrNull()

/**
 * Per-field last-writer-wins. For each field the value with the greater clock wins; on an equal clock
 * the greater JSON text wins, so the result is the same whatever the order (commutative, associative,
 * idempotent).
 */
fun merge(a: SyncRecord, b: SyncRecord): SyncRecord {
    require(a.entity == b.entity && a.id == b.id) { "merge of different records: ${a.entity}/${a.id} vs ${b.entity}/${b.id}" }
    val fields = LinkedHashMap<String, JsonElement>()
    val clocks = LinkedHashMap<String, String>()
    for (key in a.fields.keys + b.fields.keys) {
        val takeA = when {
            key !in b.fields -> true
            key !in a.fields -> false
            else -> wins(a, b, key)
        }
        val src = if (takeA) a else b
        fields[key] = src.fields.getValue(key)
        src.clocks[key]?.takeIf { Hlc.parse(it) != null }?.let { clocks[key] = it }
    }
    return SyncRecord(a.entity, a.id, fields, clocks)
}

private fun wins(a: SyncRecord, b: SyncRecord, key: String): Boolean {
    val ca = a.clocks[key]?.let(Hlc::parse)
    val cb = b.clocks[key]?.let(Hlc::parse)
    val byClock = when {
        ca == null && cb == null -> 0
        ca == null -> -1
        cb == null -> 1
        else -> ca.compareTo(cb)
    }
    if (byClock != 0) return byClock > 0
    return a.fields.getValue(key).toString() >= b.fields.getValue(key).toString()
}
