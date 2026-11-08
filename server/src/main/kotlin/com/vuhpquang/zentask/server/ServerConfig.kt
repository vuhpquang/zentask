package com.vuhpquang.zentask.server

import java.nio.file.Path
import java.nio.file.Paths

/** Server settings from the environment (`.env` on vps1, see docs/setup-guide.md §2). */
data class ServerConfig(
    val host: String,
    val port: Int,
    val dataDir: Path,
    val password: String,
    val publicUrl: String,
    val googleClientId: String = "",
    val googleClientSecret: String = "",
    /** Base64 32-byte AES key for calendar refresh tokens (`ZENTASK_ENCRYPTION_KEY`). */
    val encryptionKey: String = "",
) {
    override fun toString() = "ServerConfig(host=$host, port=$port, dataDir=$dataDir, publicUrl=$publicUrl)"

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            val password = env["ZENTASK_PASSWORD"]?.trim().orEmpty()
            check(password.isNotEmpty()) { "ZENTASK_PASSWORD is not set" }
            return ServerConfig(
                // Loopback unless told otherwise; the container sets HOST=0.0.0.0 and compose publishes 127.0.0.1 only.
                host = env["HOST"]?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
                port = env["PORT"]?.toIntOrNull() ?: 3100,
                dataDir = Paths.get(env["DATA_DIR"]?.takeIf { it.isNotBlank() } ?: "data"),
                password = password,
                publicUrl = env["ZENTASK_PUBLIC_URL"].orEmpty(),
                googleClientId = env["GOOGLE_CLIENT_ID"].orEmpty().trim(),
                googleClientSecret = env["GOOGLE_CLIENT_SECRET"].orEmpty().trim(),
                encryptionKey = env["ZENTASK_ENCRYPTION_KEY"].orEmpty().trim(),
            )
        }
    }
}
