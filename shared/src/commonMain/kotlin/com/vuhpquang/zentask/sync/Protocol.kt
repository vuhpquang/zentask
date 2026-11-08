package com.vuhpquang.zentask.sync

import kotlinx.serialization.Serializable

// Wire format between clients and the server (JSON via ZenJson). Field names are a protocol: add, never rename.

@Serializable
data class LoginRequest(val password: String, val deviceName: String)

@Serializable
data class LoginResponse(val token: String, val deviceId: String)

@Serializable
data class DeviceInfo(val id: String, val name: String, val createdAt: Long, val lastSeenAt: Long)

/** Push [changes] and pull everything the server has after [checkpoint]. */
@Serializable
data class SyncRequest(val checkpoint: Long, val changes: List<SyncRecord>)

/**
 * Server changes after the request's checkpoint (one page). [serverId] identifies the server database:
 * when it changes, the client re-uploads everything. More pages follow while [hasMore].
 */
@Serializable
data class SyncResponse(val serverId: String, val checkpoint: Long, val changes: List<SyncRecord>, val hasMore: Boolean)

/** Error body: `{"error": "<code>"}`. */
@Serializable
data class ApiError(val error: String)
