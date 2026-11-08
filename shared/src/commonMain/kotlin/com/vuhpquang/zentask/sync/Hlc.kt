package com.vuhpquang.zentask.sync

/** The server rejects a change whose clock is further ahead of server time than this (`clock_skew`). */
const val MAX_CLOCK_AHEAD_MS: Long = 24 * 60 * 60 * 1000L

/**
 * A device whose last clock is further ahead of its wall clock than this (its clock was wrong, then fixed)
 * starts again from the wall clock, so new edits aren't stuck past the server's [MAX_CLOCK_AHEAD_MS].
 */
const val CLOCK_CLAMP_MS: Long = MAX_CLOCK_AHEAD_MS / 2

private const val MAX_COUNTER = 999_999

/**
 * Hybrid logical clock timestamp. Ordered by wall-clock millis, then counter, then node id, so two
 * devices never produce equal clocks. [encode] is fixed width: string order equals clock order.
 */
data class Hlc(val millis: Long, val counter: Int, val node: String) : Comparable<Hlc> {
    override fun compareTo(other: Hlc): Int =
        compareValuesBy(this, other, Hlc::millis, Hlc::counter, Hlc::node)

    fun encode(): String = "${millis.toString().padStart(15, '0')}-${counter.toString().padStart(6, '0')}-$node"

    override fun toString(): String = encode()

    companion object {
        private val FORMAT = Regex("""^(\d{15})-(\d{6})-(.+)$""")

        fun parse(s: String): Hlc? {
            val m = FORMAT.matchEntire(s) ?: return null
            val (millis, counter, node) = m.destructured
            return Hlc(millis.toLong(), counter.toInt(), node)
        }
    }
}

/**
 * Issues strictly increasing [Hlc]s for one device ([node]), even when the wall clock goes backwards,
 * and moves past clocks seen from other devices ([observe]) so a later local edit always wins.
 * A remembered clock more than [CLOCK_CLAMP_MS] ahead of the wall clock is dropped (see there).
 * Not thread-safe: callers serialise access (ZenStore does it inside its DB transaction).
 */
class HlcClock(val node: String, private val wall: () -> Long, last: Hlc? = null) {
    var last: Hlc? = last
        private set

    fun tick(): Hlc {
        val now = wall()
        val prev = last
        val next = when {
            prev == null || now > prev.millis -> Hlc(now, 0, node)
            prev.millis - now > CLOCK_CLAMP_MS -> Hlc(now, 0, node)
            prev.counter >= MAX_COUNTER -> Hlc(prev.millis + 1, 0, node) // keep the fixed-width encoding
            else -> Hlc(prev.millis, prev.counter + 1, node)
        }
        last = next
        return next
    }

    fun observe(remote: Hlc) {
        val prev = last
        if (prev == null || remote > prev) last = Hlc(remote.millis, remote.counter, node)
    }
}
