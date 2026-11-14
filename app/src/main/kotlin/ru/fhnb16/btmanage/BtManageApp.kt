package ru.fhnb16.btmanage

import android.app.Application
import ru.fhnb16.btmanage.data.CrashLog
import ru.fhnb16.btmanage.data.EventLog
import ru.fhnb16.btmanage.data.Settings

/** The process of the application: notes crashes before anything else runs, opens the log. */
class BtManageApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Settings.load(this)
        EventLog.open(this)
    }
}
