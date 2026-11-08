package com.vuhpquang.zentask.store

import app.cash.sqldelight.db.SqlDriver

/** Opens the client database at [path] (null = in-memory), creating or migrating the schema. */
expect fun createSqlDriver(path: String?): SqlDriver

/** Re-entrant lock: ZenStore serialises writes and HLC updates from the UI and the sync engine. */
internal expect class StoreLock() {
    fun <T> withLock(block: () -> T): T
}
