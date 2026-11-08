package com.vuhpquang.zentask.store

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.vuhpquang.zentask.db.ZenTaskDatabase
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The app context, set once by the Android app before opening a database. */
object AndroidPlatform {
    lateinit var context: Context
}

/** [path] is a database name in the app's database folder (or null for an in-memory database). */
actual fun createSqlDriver(path: String?): SqlDriver =
    AndroidSqliteDriver(ZenTaskDatabase.Schema, AndroidPlatform.context, path)

internal actual class StoreLock actual constructor() {
    private val lock = ReentrantLock()
    actual fun <T> withLock(block: () -> T): T = lock.withLock(block)
}
