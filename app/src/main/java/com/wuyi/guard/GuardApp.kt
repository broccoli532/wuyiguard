package com.wuyi.guard

import android.app.Application
import com.wuyi.guard.util.CrashHandler
import com.wuyi.guard.util.Logger

class GuardApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Logger.init(this)
        Logger.purgeOldLogs()
        CrashHandler.install(this)
        Logger.i("App", LogText.APP_START)
    }
}
