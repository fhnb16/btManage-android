package ru.fhnb16.btmanage

import android.app.Application
import ru.fhnb16.btmanage.data.CrashLog

/** The process of the application: notes crashes before anything else runs. */
class BtManageApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
