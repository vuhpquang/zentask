package com.vuhpquang.zentask

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZenTaskInfoTest {
    @Test
    fun versionIsSemver() {
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(ZenTaskInfo.version), ZenTaskInfo.version)
    }

    @Test
    fun appNameIsZenTask() {
        assertEquals("ZenTask", ZenTaskInfo.appName)
    }
}
