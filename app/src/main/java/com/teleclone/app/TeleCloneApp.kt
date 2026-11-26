package com.teleclone.app

import android.app.Application
import com.teleclone.app.data.tdlib.TdLibManager

class TeleCloneApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        TdLibManager.initialize()
    }

    companion object {
        lateinit var instance: TeleCloneApp
            private set
    }
}
