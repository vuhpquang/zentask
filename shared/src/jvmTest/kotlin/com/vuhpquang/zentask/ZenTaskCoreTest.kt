package com.vuhpquang.zentask

import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.ticktick.SAMPLE_CSV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZenTaskCoreTest {
    @Test
    fun countsReflectImportedData() = kotlinx.coroutines.test.runTest {
        val core = ZenTaskCore(dbPath = null)
        assertEquals(Counts(lists = 0, openTasks = 0, completedTasks = 0, trashedTasks = 0), core.counts())
        val plan = core.importCsv(SAMPLE_CSV)
        assertTrue(plan.created > 0)
        assertEquals(Counts(lists = 2, openTasks = 2, completedTasks = 1, trashedTasks = 0), core.counts())
        assertFalse(core.ticktickHasApiData)
        assertTrue(core.ticktickLastSyncAt != null)
        core.close()
    }

    @Test
    fun tokenIsHeldInMemoryOnly() {
        val core = ZenTaskCore(dbPath = null)
        assertNull(core.token)
        core.token = "abc"
        assertEquals("abc", core.token)
        assertTrue(core.store.list(EntityTypes.preference).isEmpty())
        core.close()
    }

    @Test
    fun viewOptionsAndFiltersPersist() {
        val core = ZenTaskCore(dbPath = null)
        val f = com.vuhpquang.zentask.tasks.TaskFilter.InProject("work")
        assertEquals(com.vuhpquang.zentask.lists.ViewOptions(), core.viewOptions(f))
        core.setViewOptions(f, com.vuhpquang.zentask.lists.ViewOptions(sort = "priority", group = "none"))
        core.setViewOptions(f, com.vuhpquang.zentask.lists.ViewOptions(sort = "title", group = "none"))
        assertEquals("title", core.viewOptions(f).sort)
        val saved = core.saveFilter(null, " Hot ", com.vuhpquang.zentask.lists.FilterRule(priorities = listOf(5)))
        assertEquals("Hot", saved.name)
        core.saveFilter(saved.id, "Hotter", com.vuhpquang.zentask.lists.FilterRule(priorities = listOf(5, 3)))
        assertEquals(listOf(5, 3), core.filterRule(core.store.get(com.vuhpquang.zentask.model.EntityTypes.filter, saved.id)!!).priorities)
        core.deleteFilter(saved.id)
        assertTrue(core.store.list(com.vuhpquang.zentask.model.EntityTypes.filter).isEmpty())
        core.close()
    }
}
