package io.greasycat.watchout

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives Claude Code events forwarded from the dev machine via FCM and turns
 * them into a wrist-visible notification. We send FCM *data* messages (not
 * notification messages) so this runs even when the app is backgrounded — which
 * is what we'll need in step 3 to also push the event to the watch via the
 * Data Layer.
 */
class ClaudeMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // Rotated token. Paste it into tools/fcm_config.json on the dev machine.
        // ponytail: manual copy-paste is fine for one device; add a register
        // endpoint only when there's a second phone to keep in sync.
        Log.i(TAG, "FCM token: $token")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        val sessionId = d["session"].orEmpty()
        val project = d["project"].orEmpty().ifEmpty { "Claude" }
        val status = d["status"] ?: "update"
        val file = d["file"].orEmpty()
        val summary = d["summary"].orEmpty()

        val short = when (status) {
            "needs_input" -> "needs you"
            "done" -> "finished"
            "thinking" -> "working"
            else -> "active"
        }
        val title = "$project · Claude $short"
        val body = listOf(file, summary)
            .filter { it.isNotEmpty() }
            .joinToString(" — ")
            .ifEmpty { status }

        val tokIn = d["tok_in"]?.toIntOrNull() ?: 0
        val tokOut = d["tok_out"]?.toIntOrNull() ?: 0
        val elapsed = d["elapsed_s"]?.toIntOrNull() ?: -1
        Prefs.setStatus(this, sessionId, project, status, body, tokIn, tokOut, elapsed)
        // Nudge an open home screen to re-render (and animate) the status blob.
        sendBroadcast(Intent(Prefs.ACTION_STATUS).setPackage(packageName))
        // Refresh the persistent status notification (mirrors the blob).
        StatusNotification.update(this)
        // Only the come-back signals buzz; thinking/update just update the blob silently.
        if (status == "needs_input" || status == "done") {
            notify(title, body, urgent = status == "needs_input")
        }
        // TODO step 3: forward `d` to the watch via Wearable DataClient.
    }

    private fun notify(title: String, body: String, urgent: Boolean) {
        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(getColor(R.color.claude_orange))
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(
                if (urgent) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setAutoCancel(true)
            .build()
        // areNotificationsEnabled() is true only once POST_NOTIFICATIONS is
        // granted (requested in MainActivity), so this is the runtime guard.
        val manager = NotificationManagerCompat.from(this)
        if (manager.areNotificationsEnabled()) {
            manager.notify(NOTI_ID, notification)
        } else {
            Log.w(TAG, "notifications not enabled; dropping: $title")
        }
    }

    private fun ensureChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Claude Code", NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "claude_code"
        // ponytail: fixed id means a new event replaces the old one. Switch to
        // per-session ids if you want a stack of notifications.
        private const val NOTI_ID = 1
        private const val TAG = "ClaudeFCM"
    }
}
