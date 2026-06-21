package io.greasycat.watchout

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Single entry point for an incoming Claude event, regardless of transport
 * (FCM, the direct LAN server, or ntfy). Updates session state, the blob, the
 * persistent notification, the watch, and fires a buzz on come-back signals.
 */
object StatusIngest {
    private const val CHANNEL_ID = "claude_code"
    private const val NOTI_ID = 1

    /** `fields` uses the same keys the dev-side sender produces. */
    fun handle(context: Context, fields: Map<String, String>) {
        val sessionId = fields["session"].orEmpty()
        val project = fields["project"].orEmpty().ifEmpty { "Claude" }
        val status = fields["status"] ?: "update"
        val file = fields["file"].orEmpty()
        val summary = fields["summary"].orEmpty()

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

        val tokIn = fields["tok_in"]?.toIntOrNull() ?: 0
        val tokOut = fields["tok_out"]?.toIntOrNull() ?: 0
        val elapsed = fields["elapsed_s"]?.toIntOrNull() ?: -1

        Prefs.setStatus(context, sessionId, project, status, body, tokIn, tokOut, elapsed)
        context.sendBroadcast(Intent(Prefs.ACTION_STATUS).setPackage(context.packageName))
        StatusNotification.update(context)
        WatchSync.push(context)
        if (status == "needs_input" || status == "done") {
            buzz(context, title, body, urgent = status == "needs_input")
        }
    }

    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled()
    private fun buzz(context: Context, title: String, body: String, urgent: Boolean) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(context.getColor(R.color.claude_orange))
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(
                if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT
            )
            .setAutoCancel(true)
            .build()
        val manager = NotificationManagerCompat.from(context)
        if (manager.areNotificationsEnabled()) manager.notify(NOTI_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Claude Code", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }
}
