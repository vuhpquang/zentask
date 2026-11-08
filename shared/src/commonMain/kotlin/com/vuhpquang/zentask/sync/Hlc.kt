package com.vuhpquang.zentask.sync

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
