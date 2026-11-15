package com.vuhpquang.zentask.android

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DepotDeferTest {
    @Test fun deferredWhenDepotInstalled() =
        assertTrue(updatesManagedByDepot(setOf("com.vuhpquang.zentask", "com.vuhpquang.appdepot")))

    @Test fun notDeferredWithoutDepot() = assertFalse(updatesManagedByDepot(setOf("com.vuhpquang.zentask")))

    @Test fun debugDepotDoesNotCount() = assertFalse(updatesManagedByDepot(setOf("com.vuhpquang.appdepot.dev")))
}
