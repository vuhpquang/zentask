package com.vuhpquang.zentask.store

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.vuhpquang.zentask.db.ZenTaskDatabase
import java.util.Properties
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

actual fun createSqlDriver(path: String?): SqlDriver =
    JdbcSqliteDriver(if (path == null) JdbcSqliteDriver.IN_MEMORY else "jdbc:sqlite:$path", Properties(), ZenTaskDatabase.Schema)

internal actual class StoreLock actual constructor() {
    private val lock = ReentrantLock()
    actual fun <T> withLock(block: () -> T): T = lock.withLock(block)
}
