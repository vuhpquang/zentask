package com.vuhpquang.zentask.tasks

import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.Place
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.store.ZenStore
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Saved places for location reminders. */
class PlaceActions(
    private val store: ZenStore,
    private val wall: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    fun list(): List<Place> = store.list(EntityTypes.place).filter { !it.deleted }
        .sortedWith(compareBy({ kindOrder(it.kind) }, { it.sortOrder }, { it.name.lowercase() }))

    private fun kindOrder(kind: String) = when (kind) { Place.KIND_HOME -> 0; Place.KIND_WORK -> 1; else -> 2 }

    fun create(name: String, kind: String, latitude: Double, longitude: Double, radius: Double = 150.0, wifi: List<String> = emptyList()): Place {
        val p = Place(
            id = Uuid.random().toString(), createdAt = wall(), name = name.trim().ifEmpty { defaultName(kind) }, kind = kind,
            latitude = latitude, longitude = longitude, radius = radius.coerceIn(50.0, 5000.0), wifi = cleanWifi(wifi),
            sortOrder = (list().maxOfOrNull { it.sortOrder } ?: 0) + (1L shl 32),
        )
        store.put(EntityTypes.place, p)
        return p
    }

    fun update(original: Place, name: String, kind: String, latitude: Double, longitude: Double, radius: Double, wifi: List<String>) {
        val edited = original.copy(name = name.trim().ifEmpty { defaultName(kind) }, kind = kind, latitude = latitude, longitude = longitude,
            radius = radius.coerceIn(50.0, 5000.0), wifi = cleanWifi(wifi))
        if (store.get(EntityTypes.place, original.id)?.deleted == false) store.put(EntityTypes.place, edited, base = original)
    }

    /** Deletes the place and clears it from the tasks that reminded there. */
    fun delete(id: String) {
        store.inTransaction {
            store.idsWhere(EntityTypes.task, "placeId", id).forEach { tid -> store.update(EntityTypes.task, tid) { it.copy(placeId = null, placeTrigger = null) } }
            store.delete(EntityTypes.place, id)
        }
    }

    private fun cleanWifi(names: List<String>) = names.map(String::trim).filter(String::isNotEmpty).distinct().take(10)

    companion object {
        fun defaultName(kind: String) = when (kind) { Place.KIND_HOME -> "Home"; Place.KIND_WORK -> "Work"; else -> "Place" }
    }
}

/** What to watch and what to say when a place is reached or left. Pure. */
object LocationPlanner {
    /** One region to watch: a place and which transitions have open tasks waiting on them. */
    data class Watch(val place: Place, val arrive: Boolean, val leave: Boolean)

    private fun waiting(t: Task) = !t.deleted && t.trashedAt == null && t.status == com.vuhpquang.zentask.model.TaskStatus.OPEN && t.placeId != null

    /** Places with open tasks reminding on arrival and/or on leaving (Android watches at most 100 regions). */
    fun watches(tasks: List<Task>, places: List<Place>, max: Int = 100): List<Watch> {
        val byPlace = tasks.filter(::waiting).groupBy { it.placeId!! }
        return places.filter { !it.deleted && it.id in byPlace }.map { p ->
            val ts = byPlace.getValue(p.id)
            Watch(p, ts.any { it.placeTrigger == Place.ARRIVE }, ts.any { it.placeTrigger == Place.LEAVE })
        }.take(max)
    }

    /** Open tasks waiting on [trigger] at [placeId]. */
    fun due(tasks: List<Task>, placeId: String, trigger: String): List<Task> =
        tasks.filter { waiting(it) && it.placeId == placeId && it.placeTrigger == trigger }

    /** Notification text for those tasks at [place]. */
    fun message(place: Place, trigger: String, tasks: List<Task>): Pair<String, String> {
        val title = (if (trigger == Place.ARRIVE) "At " else "Leaving ") + place.name
        val names = tasks.map { it.title.ifBlank { "Untitled" } }
        val body = if (names.size <= 3) names.joinToString(", ") else names.take(3).joinToString(", ") + " and ${names.size - 3} more"
        return title to body
    }

    /**
     * Arrivals and departures between two "inside" sets of place ids (from geofences or Wi‑Fi): every source updates
     * one set, so a place reached by both GPS and Wi‑Fi still reminds once.
     */
    fun changes(before: Set<String>, after: Set<String>): List<Pair<String, String>> =
        (after - before).map { it to Place.ARRIVE } + (before - after).map { it to Place.LEAVE }

    /** The place whose Wi‑Fi list has [ssid], if any (Wi‑Fi = "here" without GPS). */
    fun placeForWifi(places: List<Place>, ssid: String?): Place? =
        ssid?.trim()?.takeIf { it.isNotEmpty() }?.let { s -> places.firstOrNull { p -> !p.deleted && p.wifi.any { it.equals(s, ignoreCase = false) } } }

    /** Metres between two points (haversine). */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        fun rad(d: Double) = d * kotlin.math.PI / 180
        val dLat = rad(lat2 - lat1)
        val dLon = rad(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(rad(lat1)) * cos(rad(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
