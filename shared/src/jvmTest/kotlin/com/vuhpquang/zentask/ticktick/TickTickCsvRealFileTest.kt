package com.vuhpquang.zentask.ticktick

import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.store.ZenStore
import com.vuhpquang.zentask.store.createSqlDriver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs against the owner's real backup when present (never committed); skipped elsewhere. */
class TickTickCsvRealFileTest {
    private val file = File(System.getProperty("user.home"), "Downloads/TickTick-backup-2026-11-08.csv")

    @Test
    fun realBackupCounts() {
        if (!file.exists()) {
            println("skipped: ${file.path} not found")
            return
        }
        val s = TickTickCsv.parse(file.readText())
        assertEquals(824, s.tasks.size)
        assertEquals(7, s.projects.size, "8 lists including Inbox")
        assertEquals(1, s.groups.size)
        assertEquals(16, s.tasks.count { it.kind == "CHECKLIST" })
        assertEquals(mapOf(2 to 469, 0 to 286, 1 to 67, -1 to 2), s.tasks.groupingBy { it.status ?: 0 }.eachCount())
        assertEquals(38, s.tasks.count { it.parentId != null })

        val store = ZenStore(createSqlDriver(null))
        TickTickSync(store).importCsv(s)
        assertEquals(824, store.list(EntityTypes.task).size)
        assertEquals(8, store.list(EntityTypes.project).size)
        assertEquals(38, store.list(EntityTypes.task).count { it.parentId != null && store.get(EntityTypes.task, it.parentId) != null })
    }
}
