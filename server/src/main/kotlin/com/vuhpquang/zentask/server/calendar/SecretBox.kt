package com.vuhpquang.zentask.server.calendar

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM for calendar refresh tokens at rest. Output = base64(12-byte nonce ‖ ciphertext+tag).
 * The key comes from `ZENTASK_ENCRYPTION_KEY` (base64, 32 bytes) and never leaves the server.
 */
class SecretBox(keyBase64: String) {
    private val key: SecretKeySpec

    init {
        val raw = runCatching { Base64.getDecoder().decode(keyBase64.trim()) }.getOrNull()
        require(raw != null && raw.size == 32) { "ZENTASK_ENCRYPTION_KEY must be 32 bytes, base64" }
        key = SecretKeySpec(raw, "AES")
    }

    fun encrypt(plain: String): String {
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce)) }
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(plain.toByteArray()))
    }

    /** Throws when the data was not sealed with this key (or was tampered with). */
    fun decrypt(sealed: String): String {
        val bytes = Base64.getDecoder().decode(sealed)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12)) }
        return String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }

    private companion object {
        val random = SecureRandom()
    }
}
