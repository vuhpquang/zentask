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
}
