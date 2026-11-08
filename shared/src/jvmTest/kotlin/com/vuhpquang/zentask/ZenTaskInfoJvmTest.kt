package com.vuhpquang.zentask

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ZenTaskInfoJvmTest {
    @Test
    fun versionMatchesRootVersionFile() {
        assertEquals(File("../VERSION").readText().trim(), ZenTaskInfo.version)
    }
}
