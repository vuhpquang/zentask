package com.vuhpquang.zentask.store

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import co.touchlab.sqliter.DatabaseConfiguration
import com.vuhpquang.zentask.db.ZenTaskDatabase
import platform.Foundation.NSRecursiveLock

actual fun createSqlDriver(path: String?): SqlDriver {
    if (path == null) return inMemoryDriver(ZenTaskDatabase.Schema)
    val dir = path.substringBeforeLast('/', missingDelimiterValue = ".")
    val name = path.substringAfterLast('/')
    return NativeSqliteDriver(ZenTaskDatabase.Schema, name, onConfiguration = { config ->
        config.copy(extendedConfig = DatabaseConfiguration.Extended(basePath = dir))
    })
}

internal actual class StoreLock actual constructor() {
    private val lock = NSRecursiveLock()
    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
