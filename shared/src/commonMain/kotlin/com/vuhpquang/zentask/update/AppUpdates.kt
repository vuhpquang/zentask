package com.vuhpquang.zentask.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A newer release that has a build for this platform. */
data class AvailableUpdate(val version: String, val downloadUrl: String, val notes: String)

/**
 * In-app updates read the latest GitHub release of [REPO] (tag `vX.Y.Z`, marked Latest). Each platform
 * downloads its own asset from that release, attached by `scripts/release.sh`.
 */
object AppUpdates {
    const val REPO = "vuhpquang/zentask"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

    enum class Platform { MAC, ANDROID, WINDOWS }

    /** The release asset name for [platform] at [version]. */
    fun assetName(platform: Platform, version: String): String = when (platform) {
        Platform.MAC -> "ZenTask-$version.zip"
        Platform.ANDROID -> "ZenTask-$version-android.apk"
        Platform.WINDOWS -> "ZenTask-$version-windows.msi"
    }

    /** Numeric dotted comparison; anything after "-" is ignored. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** The update in [releaseJson] (the `/releases/latest` body) for [platform], or null when there's none. */
    fun parse(releaseJson: String, platform: Platform, currentVersion: String): AvailableUpdate? {
        val release = json.decodeFromString(Release.serializer(), releaseJson)
        val version = release.tag.trimStart('v', 'V')
        if (!isNewer(version, currentVersion)) return null
        val asset = release.assets.firstOrNull { it.name == assetName(platform, version) } ?: return null
        return AvailableUpdate(version, asset.url, release.body.orEmpty())
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tag: String,
        val body: String? = null,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(val name: String, @SerialName("browser_download_url") val url: String)
}
