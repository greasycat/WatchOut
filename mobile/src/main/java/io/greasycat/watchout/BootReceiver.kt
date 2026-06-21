package io.greasycat.watchout

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-starts the direct/ntfy listener after a reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            IngestService.sync(context)
        }
    }
}
