package io.greasycat.watchout

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Subscribes to an ntfy topic over WebSocket and feeds messages into ingest.
 * ntfy frames are JSON; event=="message" carries our payload (itself JSON).
 */
class NtfyClient(
    server: String,
    private val topic: String,
    private val onPayload: (Map<String, String>) -> Unit,
) {
    private val wsUrl = server.trimEnd('/')
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://") + "/$topic/ws"

    private val client = OkHttpClient.Builder()
        .pingInterval(40, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // keep the stream open
        .build()

    private val handler = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var stopped = false

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val frame = JSONObject(text)
                if (frame.optString("event") == "message") {
                    val inner = JSONObject(frame.optString("message"))
                    onPayload(inner.keys().asSequence().associateWith { inner.optString(it) })
                }
            } catch (e: Exception) {
                Log.w("ntfy", "bad frame: ${e.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!stopped) reconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!stopped) reconnect()
        }
    }

    fun connect() {
        if (topic.isBlank()) return
        ws = client.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
    }

    private fun reconnect() {
        handler.postDelayed({ if (!stopped) connect() }, 5000)
    }

    fun stop() {
        stopped = true
        ws?.close(1000, null)
        ws = null
    }
}
