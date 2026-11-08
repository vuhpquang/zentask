package com.vuhpquang.zentask.sync

import com.vuhpquang.zentask.store.ZenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data class Synced(val at: Long) : SyncStatus
    data class Failed(val kind: SyncErrorKind, val message: String) : SyncStatus
}

/**
 * Pushes the outbox and pulls server changes until both are drained. A failure leaves the outbox and
 * the checkpoint exactly as they were; only what the server accepted is cleared. Runs one at a time.
 */
class SyncEngine(
    private val store: ZenStore,
    private val api: SyncApi,
    private val pushBatch: Int = 200,
    private val wall: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()
    private val state = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = state.asStateFlow()

    suspend fun syncOnce(): SyncStatus = mutex.withLock {
        state.value = SyncStatus.Syncing
        val result = try {
            drain()
            SyncStatus.Synced(wall())
        } catch (e: CancellationException) {
            state.value = SyncStatus.Idle
            throw e
        } catch (e: SyncException) {
            SyncStatus.Failed(e.kind, e.message.orEmpty())
        } catch (e: Exception) {
            SyncStatus.Failed(SyncErrorKind.CLIENT, e.message ?: e.toString())
        }
        state.value = result
        result
    }

    private suspend fun drain() {
        repeat(MAX_ROUNDS) {
            val outgoing = store.pending(pushBatch)
            val response = api.sync(SyncRequest(store.checkpoint, outgoing.map { it.record }))
            val known = store.serverId
            if (known != null && response.serverId != known) {
                // The server's database was replaced (restore/recreate): it may lack records this client
                // already synced. Re-upload everything and pull from the start.
                store.serverId = response.serverId
                store.checkpoint = 0
                store.markAllPending()
                return@repeat
            }
            store.markSynced(outgoing)
            store.applyRemote(response.changes)
            store.checkpoint = response.checkpoint
            store.serverId = response.serverId
            if (!response.hasMore && store.pending(1).isEmpty()) return
        }
        throw SyncException(SyncErrorKind.CLIENT, "sync did not settle after $MAX_ROUNDS rounds")
    }

    private companion object {
        const val MAX_ROUNDS = 10_000
    }
}
