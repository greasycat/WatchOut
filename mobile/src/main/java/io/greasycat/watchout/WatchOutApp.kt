package io.greasycat.watchout

import android.app.Application

/** Runs at every process start (incl. when FCM wakes the app) to init Firebase
 *  from the runtime-supplied config before any message is dispatched. */
class WatchOutApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FcmInit.ensure(this)
    }
}
