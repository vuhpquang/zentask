package com.vuhpquang.zentask.update

import com.vuhpquang.zentask.update.AppUpdates.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdatesTest {
    private val release = """
        {"tag_name":"v0.10.0","body":"Notes","draft":false,"assets":[
          {"name":"ZenTask-0.10.0.zip","browser_download_url":"https://example.com/mac.zip","size":1},
          {"name":"ZenTask-0.10.0-android.apk","browser_download_url":"https://example.com/phone.apk","size":2},
          {"name":"ZenTask-0.10.0-wear.apk","browser_download_url":"https://example.com/wear.apk","size":3}
        ]}
    """.trimIndent()

    @Test fun comparesVersionsNumerically() {
        assertTrue(AppUpdates.isNewer("0.10.0", "0.9.0"))
        assertTrue(AppUpdates.isNewer("1.0.0", "0.99.9"))
        assertTrue(AppUpdates.isNewer("0.9.1", "0.9"))
        assertFalse(AppUpdates.isNewer("0.9.0", "0.9.0"))
        assertFalse(AppUpdates.isNewer("0.8.9", "0.9.0"))
        assertFalse(AppUpdates.isNewer("0.9.0-beta", "0.9.0"))
    }

    @Test fun picksThisPlatformsAsset() {
        val update = AppUpdates.parse(release, Platform.ANDROID, "0.9.0")!!
        assertEquals("0.10.0", update.version)
        assertEquals("https://example.com/phone.apk", update.downloadUrl)
        assertEquals("Notes", update.notes)
        assertEquals("https://example.com/mac.zip", AppUpdates.parse(release, Platform.MAC, "0.9.0")!!.downloadUrl)
    }

    @Test fun noUpdateWhenCurrentOrAssetMissing() {
        assertNull(AppUpdates.parse(release, Platform.ANDROID, "0.10.0"))
        assertNull(AppUpdates.parse(release, Platform.WINDOWS, "0.9.0"))
    }
}
