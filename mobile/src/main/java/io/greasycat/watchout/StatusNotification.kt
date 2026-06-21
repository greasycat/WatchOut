package io.greasycat.watchout

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * A silent, ongoing notification that mirrors the home blob — current status,
 * detail, tokens, and a live-ticking elapsed timer (via the notification's
 * native chronometer while thinking). Rebuilt from Prefs on every event.
 */
object StatusNotification {
    private const val CHANNEL_ID = "claude_status"
    private const val NOTI_ID = 2

    /** Post/refresh, or cancel if disabled or not yet activated. */
    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled()
    fun update(context: Context) {
        val mgr = NotificationManagerCompat.from(context)
        if (!Prefs.persistentEnabled(context) ||
            !Prefs.isActivated(context) ||
            !mgr.areNotificationsEnabled()
        ) {
            mgr.cancel(NOTI_ID)
            return
        }
        ensureChannel(context)

        val status = Prefs.status(context)
        val detail = Prefs.detail(context)
        val tin = Prefs.tokIn(context)
        val tout = Prefs.tokOut(context)
        val elapsed = Prefs.elapsedSeconds(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(context.getColor(R.color.claude_orange))
            .setContentTitle(titleFor(status))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)

        if (tin > 0 || tout > 0) builder.setSubText("↑${fmt(tin)} ↓${fmt(tout)}")

        val thinking = status == "thinking" || status == "update"
        if (thinking && elapsed >= 0) {
            // Native live timer in the corner — counts up on its own.
            builder.setUsesChronometer(true)
            builder.setShowWhen(true)
            builder.setWhen(System.currentTimeMillis() - elapsed * 1000L)
            if (detail.isNotEmpty()) builder.setContentText(detail)
        } else {
            builder.setUsesChronometer(false)
            builder.setShowWhen(false)
            val parts = buildList {
                if (detail.isNotEmpty()) add(detail)
                if (elapsed >= 0) add(formatElapsed(elapsed))
            }
            if (parts.isNotEmpty()) builder.setContentText(parts.joinToString("  ·  "))
        }

        mgr.notify(NOTI_ID, builder.build())
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTI_ID)
    }

    private fun titleFor(status: String?): String = when (status) {
        "needs_input" -> "Claude needs you"
        "done" -> "Claude finished"
        "thinking", "update" -> "Claude working"
        else -> "Claude"
    }

    private fun formatElapsed(s: Int): String =
        if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"

    private fun fmt(n: Int): String =
        if (n >= 1000) String.format("%.1fk", n / 1000.0) else n.toString()

    private fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Claude status", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
