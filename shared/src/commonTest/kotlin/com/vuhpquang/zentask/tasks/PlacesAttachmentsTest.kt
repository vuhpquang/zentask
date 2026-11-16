package com.vuhpquang.zentask.tasks

import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.Place
import com.vuhpquang.zentask.store.ZenStore
import com.vuhpquang.zentask.store.createSqlDriver
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlacesAttachmentsTest {
    private val wall = 1_800_000_000_000L
    private val store = ZenStore(createSqlDriver(null), wall = { wall })
    private val actions = TaskActions(store, wall = { wall }, zone = { TimeZone.of("Asia/Ho_Chi_Minh") })
    private val places = PlaceActions(store, wall = { wall })
    private val files = AttachmentActions(store, wall = { wall })
    private val sha = "a".repeat(64)

    @Test
    fun locationRemindersAndWatches() {
        val home = places.create("", Place.KIND_HOME, 10.77, 106.70, wifi = listOf(" MyWifi ", "MyWifi"))
        val work = places.create("Office", Place.KIND_WORK, 10.80, 106.66, radius = 10.0)
        assertEquals("Home", home.name)
        assertEquals(listOf("MyWifi"), home.wifi)
        assertEquals(50.0, work.radius, "radius is clamped")
        val milk = actions.create("Buy milk", filter = null)
        val laptop = actions.create("Take laptop", filter = null)
        actions.setLocationReminder(milk.id, home.id, Place.ARRIVE)
        actions.setLocationReminder(laptop.id, home.id, Place.LEAVE)
        val w = LocationPlanner.watches(store.list(EntityTypes.task), places.list()).single()
        assertEquals(home.id, w.place.id); assertTrue(w.arrive && w.leave)
        assertEquals(listOf("Buy milk"), LocationPlanner.due(store.list(EntityTypes.task), home.id, Place.ARRIVE).map { it.title })
        assertEquals("At Home" to "Buy milk", LocationPlanner.message(home, Place.ARRIVE, listOf(store.get(EntityTypes.task, milk.id)!!)))
        assertEquals(home.id, LocationPlanner.placeForWifi(places.list(), "MyWifi")?.id)
        // Completing the task stops watching for it; deleting a place clears its tasks.
        actions.complete(laptop.id)
        assertTrue(!LocationPlanner.watches(store.list(EntityTypes.task), places.list()).single().leave)
        places.delete(home.id)
        assertNull(store.get(EntityTypes.task, milk.id)!!.placeId)
        assertEquals(listOf("Office"), places.list().map { it.name })
        val d = LocationPlanner.distance(10.77, 106.70, 10.78, 106.70)
        assertTrue(d in 1000.0..1300.0, "$d")
    }

    @Test
    fun habitLocationReminders() {
        val zone = TimeZone.of("Asia/Ho_Chi_Minh")
        val habits = com.vuhpquang.zentask.habits.HabitActions(store, wall = { wall })
        val gym = places.create("Gym", Place.KIND_CUSTOM, 10.77, 106.70, wifi = listOf("GymWifi"))
        val h = habits.create("Stretch", null, null, com.vuhpquang.zentask.model.Habit.TYPE_BOOLEAN, 1.0, 1.0, "", "RRULE:FREQ=DAILY;INTERVAL=1")
        habits.setPlace(h.id, gym.id, Place.ARRIVE)
        val w = LocationPlanner.watches(store.list(EntityTypes.task), places.list(), habits = store.list(EntityTypes.habit)).single()
        assertEquals(gym.id, w.place.id); assertTrue(w.arrive && !w.leave)
        val today = kotlinx.datetime.LocalDate(2027, 1, 15)
        fun due() = LocationPlanner.dueHabits(store.list(EntityTypes.habit), store.list(EntityTypes.habitCheckin), gym.id, Place.ARRIVE, today, zone).map { it.name }
        assertEquals(listOf("Stretch"), due())
        assertEquals("At Gym" to "Stretch", LocationPlanner.messageFor(gym, Place.ARRIVE, due()))
        // Done today = no reminder; archived = not watched; deleting the place clears it.
        habits.checkIn(h.id, today, null)
        assertEquals(emptyList(), due())
        places.delete(gym.id)
        assertNull(store.get(EntityTypes.habit, h.id)!!.placeId)
        assertNull(store.get(EntityTypes.habit, h.id)!!.placeTrigger)
    }

    @Test
    fun insideSetChangesBecomeArrivalsAndDepartures() {
        assertEquals(listOf("work" to Place.ARRIVE, "home" to Place.LEAVE), LocationPlanner.changes(setOf("home"), setOf("work")))
        assertEquals(emptyList(), LocationPlanner.changes(setOf("home"), setOf("home")))
    }

    @Test
    fun attachmentsAndDownloads() {
        val t = actions.create("Receipt", filter = null)
        val a = files.add(t.id, sha, "receipt.jpg", "image/jpeg", 1234, 800, 600)
        assertTrue(a.isImage)
        assertEquals(listOf(a.id), files.of(t.id).map { it.id })
        assertEquals(listOf(sha), files.toDownload(emptySet()))
        assertEquals(emptyList(), files.toDownload(setOf(sha)))
        assertFailsWith<IllegalArgumentException> { files.add(t.id, "nope", "x", "text/plain", 1) }
        assertFailsWith<IllegalArgumentException> { files.add(t.id, sha, "x", "text/plain", AttachmentActions.MAX_BYTES + 1) }
        actions.trash(t.id)
        actions.deleteForever(t.id)
        assertEquals(emptyList(), files.of(t.id))
        assertEquals(emptySet(), files.neededFileIds())
    }
}
