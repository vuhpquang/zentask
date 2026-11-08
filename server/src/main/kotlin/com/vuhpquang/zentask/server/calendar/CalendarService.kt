package com.vuhpquang.zentask.server.calendar

import com.vuhpquang.zentask.model.CalendarAccount
import com.vuhpquang.zentask.model.CalendarEvent
import com.vuhpquang.zentask.model.CalendarSource
import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.toFields
import com.vuhpquang.zentask.server.store.ServerDb
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Instant

/**
 * Calendar accounts on the server (spec §9). Refresh tokens are sealed with [box] in a server-only
 * table and never synced. Calendars and events are written as server-owned records (`writeServer`,
 * field diffs only), so devices receive them through `/v1/sync` and an unchanged event costs nothing.
 * A revoked grant marks the account `needs_reconnect` and keeps its events.
 */
class CalendarService(
    private val db: ServerDb,
    private val google: GoogleApi,
    private val box: SecretBox?,
    private val publicUrl: String,
    private val now: () -> Long,
    private val ical: IcalFetcher? = null,
) {
    private val log = LoggerFactory.getLogger("ZenTask.Calendar")
    private val mutex = Mutex()
    private val redirectUri get() = publicUrl.trimEnd('/') + CALLBACK_PATH

    sealed class StartResult {
        data class Url(val url: String) : StartResult()
        data class Unavailable(val code: String) : StartResult()
    }

    fun start(deviceId: String): StartResult {
        if (!google.configured) return StartResult.Unavailable("google_not_configured")
        if (box == null) return StartResult.Unavailable("encryption_not_configured")
        val state = ByteArray(24).also { random.nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        db.putOAuthState(state, deviceId)
        return StartResult.Url(google.authUrl(state, redirectUri))
    }

    /** Finishes the browser flow; returns the connected email. Throws [IllegalArgumentException] for a bad state. */
    suspend fun callback(code: String, state: String): String {
        requireNotNull(db.takeOAuthState(state, STATE_TTL_MS)) { "This sign-in link has expired. Start again from ZenTask." }
        val box = requireNotNull(box) { "encryption_not_configured" }
        val tokens = google.exchange(code, redirectUri)
        val refresh = tokens.refresh_token ?: throw IllegalArgumentException("Google sent no refresh token. Remove ZenTask's access at myaccount.google.com/permissions and connect again.")
        val email = google.userEmail(tokens.access_token)
        val accountId = "google_" + hash(email.lowercase(), 16)
        db.saveCredential(ServerDb.Credential(accountId, "GOOGLE", email, box.encrypt(refresh), STATUS_OK))
        writeAccount(accountId, email, "GOOGLE", STATUS_OK)
        mutex.withLock { syncGoogle(accountId, tokens.access_token) }
        log.info("Calendar connected: {}", accountId)
        return email
    }

    /** Adds an iCal (secret link) account and reads it once. */
    suspend fun addIcal(name: String, url: String): String {
        val box = requireNotNull(box) { "encryption_not_configured" }
        require(url.startsWith("https://") || url.startsWith("webcal://")) { "The link must start with https:// or webcal://" }
        val accountId = "ical_" + hash(url, 16)
        db.saveCredential(ServerDb.Credential(accountId, "ICAL", name.trim().ifEmpty { "Calendar" }, box.encrypt(url), STATUS_OK))
        writeAccount(accountId, name.trim().ifEmpty { "Calendar" }, "ICAL", STATUS_OK)
        mutex.withLock { syncIcal(accountId, name, url) }
        return accountId
    }

    /** Deletes the credentials and tombstones the account, its calendars and their events. */
    suspend fun remove(accountId: String): Boolean = mutex.withLock {
        val had = db.credentials().any { it.accountId == accountId }
        db.deleteCredential(accountId)
        for (source in db.recordsWhere(EntityTypes.calendarSource.name, "accountId", accountId)) {
            tombstoneEvents(source.id, keep = emptySet())
            db.writeServer(EntityTypes.calendarSource.name, source.id, mapOf("deleted" to JsonPrimitive(true)))
        }
        db.writeServer(EntityTypes.calendarAccount.name, accountId, mapOf("deleted" to JsonPrimitive(true)))
        had
    }

    /** One pass over every account (the 10-minute timer). Failures of one account don't stop the others. */
    suspend fun syncAll() = mutex.withLock {
        val box = box ?: return@withLock
        for (c in db.credentials()) {
            try {
                val secret = box.decrypt(c.secretEnc)
                when (c.provider) {
                    "GOOGLE" -> syncGoogle(c.accountId, google.refresh(secret))
                    "ICAL" -> syncIcal(c.accountId, c.email, secret)
                }
                if (c.status != STATUS_OK) setStatus(c.accountId, STATUS_OK)
            } catch (e: InvalidGrantException) {
                log.warn("Calendar {} needs reconnecting", c.accountId)
                setStatus(c.accountId, STATUS_RECONNECT)
            } catch (e: Exception) {
                log.warn("Calendar {} sync failed: {}", c.accountId, e.message)
            }
        }
    }

    // ---- Google ----

    private suspend fun syncGoogle(accountId: String, access: String) {
        val calendars = google.calendars(access).filter { !it.deleted }
        val seen = HashSet<String>()
        val from = now() - 90 * DAY
        val until = now() + 365 * DAY
        for (cal in calendars) {
            val sourceId = "gsrc_" + hash("$accountId|${cal.id}", 20)
            seen += sourceId
            val zone = cal.timeZone?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: DEFAULT_ZONE
            writeSource(CalendarSource(id = sourceId, accountId = accountId, remoteId = cal.id, name = cal.summaryOverride ?: cal.summary, color = cal.backgroundColor))
            val events = google.events(access, cal.id, Instant.fromEpochMilliseconds(from).toString(), Instant.fromEpochMilliseconds(until).toString())
            val keep = HashSet<String>()
            for (e in events) {
                if (e.status == "cancelled") continue
                val id = "gev_" + hash("$sourceId|${e.id}", 24)
                val (start, startAllDay) = time(e.start, zone) ?: continue
                val (end, _) = time(e.end, zone) ?: (start to startAllDay)
                keep += id
                writeEvent(
                    CalendarEvent(
                        id = id, sourceId = sourceId, remoteId = e.id, title = e.summary, location = e.location, description = e.description,
                        start = start, end = end, isAllDay = startAllDay, timeZone = e.start.timeZone ?: cal.timeZone,
                        recurringEventId = e.recurringEventId, status = e.status,
                    ),
                )
            }
            tombstoneEvents(sourceId, keep)
        }
        // Calendars gone from the account (unsubscribed): tombstone them and their events.
        for (old in db.recordsWhere(EntityTypes.calendarSource.name, "accountId", accountId)) {
            if (old.id !in seen) {
                tombstoneEvents(old.id, keep = emptySet())
                db.writeServer(EntityTypes.calendarSource.name, old.id, mapOf("deleted" to JsonPrimitive(true)))
            }
        }
    }

    /** Google `{date}` (all-day, local) or `{dateTime}` (instant) → (epoch millis, allDay). */
    private fun time(t: GoogleTime, zone: TimeZone): Pair<Long, Boolean>? {
        t.dateTime?.let { return runCatching { Instant.parse(it).toEpochMilliseconds() to false }.getOrNull() }
        t.date?.let { d -> return runCatching { LocalDate.parse(d).atStartOfDayIn(zone).toEpochMilliseconds() to true }.getOrNull() }
        return null
    }

    // ---- iCal ----

    private suspend fun syncIcal(accountId: String, name: String, url: String) {
        val fetcher = ical ?: return
        val sourceId = "isrc_" + hash(accountId, 20)
        writeSource(CalendarSource(id = sourceId, accountId = accountId, remoteId = url.take(0), name = name))
        val from = now() - 90 * DAY
        val until = now() + 365 * DAY
        val keep = HashSet<String>()
        for (e in fetcher.events(url, from, until)) {
            val id = "iev_" + hash("$sourceId|${e.uid}|${e.start}", 24)
            keep += id
            writeEvent(
                CalendarEvent(
                    id = id, sourceId = sourceId, remoteId = e.uid, title = e.summary, location = e.location, description = e.description,
                    start = e.start, end = e.end, isAllDay = e.allDay, timeZone = e.timeZone,
                ),
            )
        }
        tombstoneEvents(sourceId, keep)
    }

    // ---- records ----

    private fun writeAccount(id: String, email: String, provider: String, status: String) =
        db.writeServer(EntityTypes.calendarAccount.name, id, fields(EntityTypes.calendarAccount.toFields(CalendarAccount(id = id, email = email, provider = provider, status = status))))

    private fun setStatus(accountId: String, status: String) {
        db.setCredentialStatus(accountId, status)
        db.writeServer(EntityTypes.calendarAccount.name, accountId, mapOf("status" to JsonPrimitive(status)))
    }

    private fun writeSource(s: CalendarSource) = db.writeServer(EntityTypes.calendarSource.name, s.id, fields(EntityTypes.calendarSource.toFields(s)))

    private fun writeEvent(e: CalendarEvent) = db.writeServer(EntityTypes.calendarEvent.name, e.id, fields(EntityTypes.calendarEvent.toFields(e)))

    private fun tombstoneEvents(sourceId: String, keep: Set<String>) {
        for (old in db.recordsWhere(EntityTypes.calendarEvent.name, "sourceId", sourceId)) {
            if (old.id !in keep) db.writeServer(EntityTypes.calendarEvent.name, old.id, mapOf("deleted" to JsonPrimitive(true)))
        }
    }

    /** Fields the server owns for its records (timestamps would churn every sync, so they are left out). */
    private fun fields(all: Map<String, kotlinx.serialization.json.JsonElement>) = all - setOf("createdAt", "updatedAt")

    companion object {
        const val CALLBACK_PATH = "/v1/calendar/google/callback"
        const val STATUS_OK = "ok"
        const val STATUS_RECONNECT = "needs_reconnect"
        private const val STATE_TTL_MS = 10 * 60 * 1000L
        private const val DAY = 86_400_000L
        private val DEFAULT_ZONE = TimeZone.of("Asia/Ho_Chi_Minh")
        private val random = SecureRandom()

        fun hash(s: String, length: Int): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(length)
    }
}
