package io.greasycat.watchout

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that hosts the active non-FCM transport: the direct LAN
 * HTTP server (+ mDNS advertisement) or the ntfy WebSocket subscriber. FCM needs
 * no service, so for FCM this service is never started.
 */
class IngestService : Service() {

    private var server: DirectServer? = null
    private var ntfy: NtfyClient? = null
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotice()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopTransport()
        when (Prefs.transport(this)) {
            Prefs.TRANSPORT_DIRECT -> startDirect()
            Prefs.TRANSPORT_NTFY -> startNtfy()
            else -> stopSelf() // FCM handles itself
        }
        return START_STICKY
    }

    private fun startDirect() {
        val port = Prefs.directPort(this)
        try {
            server = DirectServer(port, Prefs.directToken(this)) { f ->
                StatusIngest.handle(applicationContext, f)
            }
            server!!.start()
            registerNsd(port)
            Log.i(TAG, "direct server listening on :$port")
        } catch (e: Exception) {
            Log.e(TAG, "direct start failed: ${e.message}")
        }
    }

    private fun startNtfy() {
        ntfy = NtfyClient(Prefs.ntfyServer(this), Prefs.ntfyTopic(this)) { f ->
            StatusIngest.handle(applicationContext, f)
        }.also { it.connect() }
    }

    private fun registerNsd(port: Int) {
        nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        val info = NsdServiceInfo().apply {
            serviceName = "WatchOut"
            serviceType = "_watchout._tcp."
            setPort(port)
        }
        nsdListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo?) {}
            override fun onRegistrationFailed(s: NsdServiceInfo?, code: Int) {}
            override fun onServiceUnregistered(s: NsdServiceInfo?) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo?, code: Int) {}
        }
        runCatching { nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener) }
    }

    private fun stopTransport() {
        server?.stop(); server = null
        ntfy?.stop(); ntfy = null
        nsdListener?.let { l -> runCatching { nsd?.unregisterService(l) } }
        nsdListener = null
    }

    override fun onDestroy() {
        stopTransport()
        super.onDestroy()
    }

    private fun startForegroundNotice() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "WatchOut listener", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("WatchOut listening")
            .setOngoing(true)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(FG_ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "watchout_listener"
        private const val FG_ID = 3
        private const val TAG = "IngestSvc"

        /** Start the listener for direct/ntfy, or stop it for FCM. Call on app start
         *  and whenever the transport setting changes. */
        fun sync(context: Context) {
            val intent = Intent(context, IngestService::class.java)
            if (Prefs.transport(context) == Prefs.TRANSPORT_FCM) {
                context.stopService(intent)
            } else {
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}
