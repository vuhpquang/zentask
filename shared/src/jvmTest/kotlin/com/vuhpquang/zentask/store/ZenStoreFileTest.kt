package com.vuhpquang.zentask.store

import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.sync.Hlc
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZenStoreFileTest {
    @Test
    fun stateSurvivesReopen() {
        val path = Files.createTempDirectory("zenstore").resolve("zentask.db").toString()
        val first = ZenStore(createSqlDriver(path), wall = { 1_000L })
        first.put(EntityTypes.task, Task(id = "t1", title = "persist me"))
        first.checkpoint = 7
        first.serverId = "srv"
        val node = first.nodeId
        val lastClock = Hlc.parse(first.rawRecord("task", "t1")!!.clocks.getValue("title"))!!
        first.close()

        val second = ZenStore(createSqlDriver(path), wall = { 1_000L })
        assertEquals(node, second.nodeId)
        assertEquals(7L, second.checkpoint)
        assertEquals("srv", second.serverId)
        assertEquals("persist me", second.get(EntityTypes.task, "t1")!!.title)
        assertEquals(1, second.pending(10).size)
        second.put(EntityTypes.task, second.get(EntityTypes.task, "t1")!!.copy(title = "again"))
        val next = Hlc.parse(second.rawRecord("task", "t1")!!.clocks.getValue("title"))!!
        assertTrue(next > lastClock, "clock restored: $next > $lastClock")
        second.close()
    }
}
