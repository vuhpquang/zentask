package com.vuhpquang.zentask.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MergeTest {
    private fun c(millis: Long, node: String = "a") = Hlc(millis, 0, node).encode()

    private fun rec(vararg f: Pair<String, Pair<JsonElement, String>>) = SyncRecord(
        entity = "task", id = "t1",
        fields = f.associate { it.first to it.second.first },
        clocks = f.associate { it.first to it.second.second },
    )

    @Test
    fun laterClockWinsPerField() {
        val a = rec("title" to (JsonPrimitive("new") to c(20)), "priority" to (JsonPrimitive(0) to c(10)))
        val b = rec("title" to (JsonPrimitive("old") to c(10)), "priority" to (JsonPrimitive(5) to c(30)))
        val m = merge(a, b)
        assertEquals(JsonPrimitive("new"), m.fields["title"])
        assertEquals(JsonPrimitive(5), m.fields["priority"])
        assertEquals(c(30), m.clocks["priority"])
    }

    @Test
    fun mergeIsCommutativeAssociativeIdempotent() {
        val r = Random(7)
        val fields = listOf("title", "priority", "deleted")
        val nodes = listOf("a", "b", "c")
        fun random(): SyncRecord {
            val present = fields.filter { r.nextBoolean() }
            return SyncRecord(
                "task", "t1",
                present.associateWith { JsonPrimitive(r.nextInt(0, 3)) },
                present.associateWith { Hlc(r.nextLong(0, 4), r.nextInt(0, 2), nodes.random(r)).encode() },
            )
        }
        repeat(200) {
            val (x, y, z) = Triple(random(), random(), random())
            assertEquals(merge(x, y), merge(y, x))
            assertEquals(merge(merge(x, y), z), merge(x, merge(y, z)))
            assertEquals(merge(x, x), merge(merge(x, x), x))
            assertEquals(x, merge(x, x))
        }
    }

    @Test
    fun equalClockTieIsDeterministic() {
        val a = rec("title" to (JsonPrimitive("a") to c(5)))
        val b = rec("title" to (JsonPrimitive("b") to c(5)))
        assertEquals(merge(a, b), merge(b, a))
    }

    @Test
    fun fieldMissingOnOneSideIsTaken() {
        val a = rec("title" to (JsonPrimitive("x") to c(1)))
        val b = rec("priority" to (JsonPrimitive(3) to c(1)))
        val m = merge(a, b)
        assertEquals(setOf("title", "priority"), m.fields.keys)
        assertEquals(setOf("title", "priority"), m.clocks.keys)
    }

    @Test
    fun tombstoneWinsOverOlderAndLaterEditsOfOtherFields() {
        val local = rec(
            "deleted" to (JsonPrimitive(true) to c(20)),
            "title" to (JsonPrimitive("t") to c(10)),
        )
        val remote = rec(
            "deleted" to (JsonPrimitive(false) to c(10)),
            "title" to (JsonPrimitive("later") to c(30)),
        )
        val m = merge(local, remote)
        assertTrue(m.isDeleted)
        assertEquals(JsonPrimitive("later"), m.fields["title"])
    }

    @Test
    fun fieldWithoutClockLoses() {
        val noClock = SyncRecord("task", "t1", mapOf("title" to JsonPrimitive("x")), emptyMap())
        val badClock = SyncRecord("task", "t1", mapOf("title" to JsonPrimitive("y")), mapOf("title" to "bad"))
        val good = rec("title" to (JsonPrimitive("z") to c(1)))
        assertEquals(JsonPrimitive("z"), merge(noClock, good).fields["title"])
        assertEquals(JsonPrimitive("z"), merge(good, badClock).fields["title"])
    }

    @Test
    fun maxClockAndDifferentRecordsRejected() {
        val a = rec("title" to (JsonPrimitive("x") to c(1)), "priority" to (JsonPrimitive(1) to c(9, "b")))
        assertEquals(Hlc(9, 0, "b"), a.maxClock())
        assertFailsWith<IllegalArgumentException> { merge(a, a.copy(id = "other")) }
    }
}
