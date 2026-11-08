package com.vuhpquang.zentask.sync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HlcTest {
    @Test
    fun encodeSortsLikeCompare() {
        val r = Random(1)
        val nodes = listOf("a", "b", "node-1")
        repeat(500) {
            val x = Hlc(r.nextLong(0, 2_000_000_000_000), r.nextInt(0, 50), nodes.random(r))
            val y = Hlc(r.nextLong(0, 2_000_000_000_000), r.nextInt(0, 50), nodes.random(r))
            assertEquals(x.compareTo(y).coerceIn(-1, 1), x.encode().compareTo(y.encode()).coerceIn(-1, 1))
            assertEquals(x, Hlc.parse(x.encode()))
        }
        val same = Hlc(5, 1, "a")
        assertEquals(0, same.compareTo(Hlc(5, 1, "a")))
    }

    @Test
    fun parseRejectsGarbage() {
        listOf("x", "", "1-2", "abc-000001-n", "000000000000001-xyz-n", "000000000000001-000001-").forEach {
            assertNull(Hlc.parse(it), it)
        }
    }

    @Test
    fun tickIsMonotonicWhenWallClockGoesBack() {
        var wall = 100L
        val clock = HlcClock("n", { wall })
        val first = clock.tick()
        wall = 50
        val second = clock.tick()
        val third = clock.tick()
        assertTrue(second > first)
        assertTrue(third > second)
        wall = 200
        val fourth = clock.tick()
        assertEquals(Hlc(200, 0, "n"), fourth)
    }

    @Test
    fun observeMovesPastRemote() {
        val clock = HlcClock("n", { 1_000L })
        val remote = Hlc(11_000, 7, "other")
        clock.observe(remote)
        assertTrue(clock.tick() > remote)
    }

    @Test
    fun restoresFromLast() {
        val clock = HlcClock("n", { 10L }, last = Hlc(500, 3, "n"))
        assertTrue(clock.tick() > Hlc(500, 3, "n"))
    }

    @Test
    fun farFutureLastIsClampedToWallClock() {
        val clock = HlcClock("n", { 1_000L }, last = Hlc(1_000L + 48 * 3_600_000L, 5, "n"))
        assertEquals(Hlc(1_000, 0, "n"), clock.tick())
        val nearFuture = HlcClock("n", { 1_000L }, last = Hlc(1_000L + 60_000, 5, "n"))
        assertEquals(Hlc(61_000, 6, "n"), nearFuture.tick())
    }

    @Test
    fun counterOverflowStaysParsable() {
        val clock = HlcClock("n", { 0L }, last = Hlc(10, 999_999, "n"))
        val next = clock.tick()
        assertTrue(next > Hlc(10, 999_999, "n"))
        assertEquals(next, Hlc.parse(next.encode()))
    }
}
