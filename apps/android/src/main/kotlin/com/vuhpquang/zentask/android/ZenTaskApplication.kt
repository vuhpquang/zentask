package com.vuhpquang.zentask.android

import android.app.Application
import com.vuhpquang.zentask.store.AndroidPlatform

class ZenTaskApplication : Application() {
    lateinit var zen: ZenApp
        private set

    override fun onCreate() {
        super.onCreate()
        AndroidPlatform.context = this
        Reminders.createChannel(this)
        zen = ZenApp(this).also { it.start() }
    }
}
