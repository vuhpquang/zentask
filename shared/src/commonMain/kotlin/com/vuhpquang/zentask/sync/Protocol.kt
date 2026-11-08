package com.vuhpquang.zentask.sync

import kotlinx.serialization.Serializable

// Wire format between clients and the server (JSON via ZenJson). Field names are a protocol: add, never rename.

/** [scope]: `full` (a ZenTask app) or `mcp` (an AI client: only `/mcp`, never `/v1`). */
@Serializable
data class LoginRequest(val password: String, val deviceName: String, val scope: String = "full")

@Serializable
data class LoginResponse(val token: String, val deviceId: String)

@Serializable
data class DeviceInfo(val id: String, val name: String, val createdAt: Long, val lastSeenAt: Long, val scope: String = "full")

/** Push [changes] and pull everything the server has after [checkpoint]. */
@Serializable
data class SyncRequest(val checkpoint: Long, val changes: List<SyncRecord>)

/**
 * Server changes after the request's checkpoint (one page). [serverId] identifies the server database:
 * when it changes, the client re-uploads everything. More pages follow while [hasMore].
 * The server changes [serverId] itself when a client's checkpoint is ahead of its version (restored DB).
 */
@Serializable
data class SyncResponse(
    val serverId: String,
    val checkpoint: Long,
    val changes: List<SyncRecord>,
    val hasMore: Boolean,
    /** Pushed changes the server refused; every other pushed change was applied. */
    val rejected: List<RejectedChange> = emptyList(),
)

/** [reason]: `clock_skew` (re-stamp and resend) or `bad_request` (the client parks it). */
@Serializable
data class RejectedChange(val entity: String, val id: String, val reason: String)

/** Error body: `{"error": "<code>"}`. */
@Serializable
data class ApiError(val error: String)
