@file:OptIn(ExperimentalCoroutinesApi::class)

package com.vuhpquang.zentask.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncSchedulerTest {
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    private var syncs = 0

    @Test
    fun burstOfChangesSyncsOnceAfterDebounce() = runTest {
        val s = SyncScheduler(backgroundScope, changes, sync = { syncs++ })
        s.start()
        runCurrent()
        repeat(3) {
            changes.emit(Unit)
            advanceTimeBy(500)
        }
        assertEquals(0, syncs)
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(1, syncs)
    }

    @Test
    fun requestNowSyncsImmediately() = runTest {
        val s = SyncScheduler(backgroundScope, changes, sync = { syncs++ })
        s.start()
        s.requestNow()
        runCurrent()
        assertEquals(1, syncs)
    }

    @Test
    fun periodicTickSyncs() = runTest {
        val s = SyncScheduler(backgroundScope, changes, sync = { syncs++ })
        s.start()
        advanceTimeBy(300_001)
        runCurrent()
        assertEquals(1, syncs)
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(2, syncs)
    }

    @Test
    fun stopCancelsTicks() = runTest {
        val s = SyncScheduler(backgroundScope, changes, sync = { syncs++ })
        s.start()
        s.stop()
        changes.emit(Unit)
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(0, syncs)
    }

    @Test
    fun failingSyncDoesNotStopScheduler() = runTest {
        val s = SyncScheduler(backgroundScope, changes, sync = { syncs++; error("boom") })
        s.start()
        s.requestNow()
        runCurrent()
        s.requestNow()
        runCurrent()
        assertEquals(2, syncs)
    }
}
