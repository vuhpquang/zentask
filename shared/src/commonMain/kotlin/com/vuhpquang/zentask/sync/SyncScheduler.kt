package com.vuhpquang.zentask.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * When to sync (spec §7): [debounceMs] after local changes settle, every [periodMs] while started
 * (the app starts it when active and stops it when inactive), and on [requestNow] (launch, sync button,
 * network back). Requests that pile up during a sync collapse into one.
 */
class SyncScheduler(
    private val scope: CoroutineScope,
    private val changes: Flow<Unit>,
    private val debounceMs: Long = 2_000,
    private val periodMs: Long = 300_000,
    private val sync: suspend () -> Any?,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    /** Convenience: schedule [engine] on the store's local changes. */
    constructor(scope: CoroutineScope, engine: SyncEngine, changes: Flow<Unit>) :
        this(scope, changes, sync = { engine.syncOnce() })

    @OptIn(FlowPreview::class)
    fun start() {
        if (job != null) return
        job = scope.launch {
            launch { changes.debounce(debounceMs).collect { requests.trySend(Unit) } }
            launch {
                while (isActive) {
                    delay(periodMs)
                    requests.trySend(Unit)
                }
            }
            for (request in requests) {
                try {
                    sync()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // SyncEngine reports failures through its status; never let one stop the scheduler.
                }
            }
        }
    }

    fun requestNow() {
        requests.trySend(Unit)
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
