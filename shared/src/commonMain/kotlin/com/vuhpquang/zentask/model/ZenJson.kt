package com.vuhpquang.zentask.model

import kotlinx.serialization.json.Json

/**
 * The JSON configuration for records and the sync protocol. Every field is written (defaults and
 * nulls included) so each one carries its own clock; unknown keys from newer clients are ignored.
 */
val ZenJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}
