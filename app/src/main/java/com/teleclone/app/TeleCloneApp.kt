package com.teleclone.app

import android.app.Application
import com.teleclone.app.data.telegram.TdLibManager

class TeleCloneApp : Application() {

    override fun onCreate() {
        super.onCreate()
        TdLibManager.initialize(this)
    }
}
