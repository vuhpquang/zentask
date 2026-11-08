package com.vuhpquang.zentask

import kotlin.test.Test
import kotlin.test.assertEquals

class ZenTaskInfoTest {
    @Test
    fun versionMatchesRootVersionFile() {
        assertEquals("0.1.0", ZenTaskInfo.version)
    }

    @Test
    fun appNameIsZenTask() {
        assertEquals("ZenTask", ZenTaskInfo.appName)
    }
}
