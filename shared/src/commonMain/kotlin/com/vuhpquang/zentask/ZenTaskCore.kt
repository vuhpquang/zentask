package com.vuhpquang.zentask

import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.model.draft
import com.vuhpquang.zentask.store.ZenStore
import com.vuhpquang.zentask.store.createSqlDriver

/**
 * Entry point for the native apps: opens the local database at [dbPath] (null = in-memory).
 * Generic Kotlin APIs export poorly to Swift, so typed helpers live here and grow phase by phase.
 * In Swift, Kotlin's `Task` is `ZenTaskShared.Task` (Swift has its own `Task`).
 */
class ZenTaskCore(dbPath: String?) {
    val store: ZenStore = ZenStore(createSqlDriver(dbPath))

    fun draftTask(title: String, now: Long): Task = Task.draft(title, now = now)

    fun putTask(task: Task) = store.put(EntityTypes.task, task)

    fun task(id: String): Task? = store.get(EntityTypes.task, id)

    fun tasks(): List<Task> = store.list(EntityTypes.task)

    fun close() = store.close()
}
