package com.goju.ribs.myrainassist

import android.app.Application
import com.goju.ribs.myrainassist.notification.NotificationHelper

class RainAssistApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
    }
}
