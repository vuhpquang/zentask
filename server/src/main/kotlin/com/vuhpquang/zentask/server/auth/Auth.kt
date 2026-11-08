package com.vuhpquang.zentask.server.auth

import com.vuhpquang.zentask.server.store.ServerDb
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

const val DEVICE_AUTH = "device"

const val SCOPE_FULL = "full"
const val SCOPE_MCP = "mcp"

/** The authenticated caller. [scope] `mcp` tokens may only use `/mcp`. */
data class DevicePrincipal(val deviceId: String, val scope: String = SCOPE_FULL)

/** Install inside an authenticated route: refuses tokens that aren't full app devices (403). */
val FullScopeOnly = io.ktor.server.application.createRouteScopedPlugin("FullScopeOnly") {
    on(io.ktor.server.auth.AuthenticationChecked) { call ->
        val principal = call.principal<DevicePrincipal>()
        if (principal != null && principal.scope != SCOPE_FULL) {
            call.respond(io.ktor.http.HttpStatusCode.Forbidden, com.vuhpquang.zentask.sync.ApiError("forbidden"))
        }
    }
}

/**
 * Checks the login password against a salted PBKDF2-HMAC-SHA256 hash made at startup; only the hash
 * is kept in memory.
 */
class PasswordCheck(password: String, private val iterations: Int = 120_000) {
    private val salt = ByteArray(16).also { random.nextBytes(it) }
    private val hash = derive(password)

    fun matches(candidate: String): Boolean = MessageDigest.isEqual(hash, derive(candidate))

    private fun derive(p: String): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(p.toCharArray(), salt, iterations, 256)).encoded
}

/**
 * Global login throttle: at most [maxFailures] failed logins within [windowMs]; beyond that every login
 * (even a correct one) is refused until the oldest failure leaves the window. Global because all traffic
 * arrives through cloudflared, so client IPs mean nothing.
 *
 * An attempt reserves a failure slot *before* the (slow) password check and gives it back on success,
 * so parallel requests can't all slip past the limit. In memory: a server restart clears it.
 */
class LoginLimiter(
    private val now: () -> Long,
    private val maxFailures: Int = 5,
    private val windowMs: Long = 15 * 60 * 1000,
) {
    private val attempts = ArrayDeque<Long>()

    /** Reserves a slot; null when the limit is reached. Pass the result to [succeeded] if the password was right. */
    @Synchronized
    fun reserve(): Long? {
        val t = now()
        while (attempts.isNotEmpty() && t - attempts.first() > windowMs) attempts.removeFirst()
        if (attempts.size >= maxFailures) return null
        attempts.addLast(t)
        return t
    }

    @Synchronized
    fun succeeded(slot: Long) {
        attempts.remove(slot)
    }
}

object Tokens {
    /** 32 random bytes, base64url without padding. */
    fun issue(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { random.nextBytes(it) })

    fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Bearer auth against the device table; revoked or unknown tokens are rejected. */
fun AuthenticationConfig.deviceBearer(db: ServerDb) {
    bearer(DEVICE_AUTH) {
        authenticate { credential ->
            val device = db.deviceByTokenHash(Tokens.hash(credential.token))
            if (device == null || device.revokedAt != null) {
                null
            } else {
                db.touch(device.id)
                DevicePrincipal(device.id, device.scope)
            }
        }
    }
}

private val random = SecureRandom()
