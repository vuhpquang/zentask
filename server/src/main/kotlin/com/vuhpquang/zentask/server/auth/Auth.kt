package com.vuhpquang.zentask.server.auth

import com.vuhpquang.zentask.server.store.ServerDb
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.bearer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

const val DEVICE_AUTH = "device"

/** The authenticated caller of a `/v1` route. */
data class DevicePrincipal(val deviceId: String)

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
 * Global login throttle: after [maxFailures] failed logins within [windowMs], every login (even a
 * correct one) is refused until the oldest failure leaves the window. Global because all traffic
 * arrives through cloudflared, so client IPs mean nothing.
 */
class LoginLimiter(
    private val now: () -> Long,
    private val maxFailures: Int = 5,
    private val windowMs: Long = 15 * 60 * 1000,
) {
    private val failures = ArrayDeque<Long>()

    @Synchronized
    fun blocked(): Boolean {
        val t = now()
        while (failures.isNotEmpty() && t - failures.first() > windowMs) failures.removeFirst()
        return failures.size >= maxFailures
    }

    @Synchronized
    fun recordFailure() {
        failures.addLast(now())
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
                DevicePrincipal(device.id)
            }
        }
    }
}

private val random = SecureRandom()
