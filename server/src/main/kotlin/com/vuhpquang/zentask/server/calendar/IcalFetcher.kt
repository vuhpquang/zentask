package com.vuhpquang.zentask.server.calendar

/** One occurrence from an iCal feed, already expanded and converted to epoch millis. */
data class IcalEvent(
    val uid: String,
    val summary: String,
    val location: String,
    val description: String,
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    val timeZone: String?,
)

/** Reads an iCal (secret link) feed: the occurrences inside [from, until]. */
fun interface IcalFetcher {
    suspend fun events(url: String, from: Long, until: Long): List<IcalEvent>
}
