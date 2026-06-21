package io.greasycat.watchout

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** FCM transport: hands the data message off to the shared ingest pipeline. */
class ClaudeMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // Rotated token. Paste it into tools/fcm_config.json on the dev machine.
        Log.i("ClaudeFCM", "FCM token: $token")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        StatusIngest.handle(this, message.data)
    }
}
