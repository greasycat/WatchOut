package io.greasycat.watchout

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A single Claude event for a session's history list. */
data class Event(val time: Long, val status: String, val detail: String)

/** Per-session state — one tab on the home pager. */
data class Session(
    val id: String,
    val project: String,
    val status: String,
    val detail: String,
    val tokIn: Int,
    val tokOut: Int,
    val elapsed: Int,
    val updated: Long,
    val events: List<Event>,
)

/** Shared-prefs store: per-session status + history, plus global config. */
object Prefs {
    /** Broadcast sent by the messaging service so an open home screen re-renders. */
    const val ACTION_STATUS = "io.greasycat.watchout.STATUS"
    const val DEFAULT_EVENT_COUNT = 5

    const val TRANSPORT_FCM = "fcm"
    const val TRANSPORT_DIRECT = "direct"
    const val TRANSPORT_NTFY = "ntfy"
    const val DEFAULT_DIRECT_PORT = 8787
    const val DEFAULT_NTFY_SERVER = "https://ntfy.sh"

    private const val NAME = "watchout"
    private const val KEY_SESSIONS = "sessions_v2" // {order:[ids], map:{id:obj}}
    private const val KEY_EVENT_COUNT = "event_count"
    private const val KEY_PERSISTENT = "persistent"
    private const val KEY_TRANSPORT = "transport"
    private const val KEY_DIRECT_PORT = "direct_port"
    private const val KEY_DIRECT_TOKEN = "direct_token"
    private const val KEY_NTFY_SERVER = "ntfy_server"
    private const val KEY_NTFY_TOPIC = "ntfy_topic"
    private const val KEY_FCM_PROJECT = "fcm_project_id"
    private const val KEY_FCM_SENDER = "fcm_sender_id"
    private const val KEY_FCM_APP_ID = "fcm_app_id"
    private const val KEY_FCM_API_KEY = "fcm_api_key"

    private const val SESSION_CAP = 10
    private const val EVENT_CAP = 50

    // --- global config ---
    fun eventCount(context: Context): Int =
        prefs(context).getInt(KEY_EVENT_COUNT, DEFAULT_EVENT_COUNT)

    fun setEventCount(context: Context, k: Int) =
        prefs(context).edit().putInt(KEY_EVENT_COUNT, k).apply()

    fun persistentEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PERSISTENT, true)

    fun setPersistent(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_PERSISTENT, on).apply()

    // --- transport ---
    fun transport(context: Context): String =
        prefs(context).getString(KEY_TRANSPORT, TRANSPORT_DIRECT) ?: TRANSPORT_DIRECT

    fun setTransport(context: Context, t: String) =
        prefs(context).edit().putString(KEY_TRANSPORT, t).apply()

    fun directPort(context: Context): Int = prefs(context).getInt(KEY_DIRECT_PORT, DEFAULT_DIRECT_PORT)

    fun setDirectPort(context: Context, p: Int) =
        prefs(context).edit().putInt(KEY_DIRECT_PORT, p).apply()

    /** Shared secret for the direct listener. Empty = accept any POST (legacy).
     *  Auto-generated once on first view of the Direct settings; copy it into the hook config. */
    fun directToken(context: Context): String = prefs(context).getString(KEY_DIRECT_TOKEN, "") ?: ""

    fun setDirectToken(context: Context, s: String) =
        prefs(context).edit().putString(KEY_DIRECT_TOKEN, s).apply()

    fun ntfyServer(context: Context): String =
        prefs(context).getString(KEY_NTFY_SERVER, DEFAULT_NTFY_SERVER) ?: DEFAULT_NTFY_SERVER

    fun setNtfyServer(context: Context, s: String) =
        prefs(context).edit().putString(KEY_NTFY_SERVER, s).apply()

    fun ntfyTopic(context: Context): String = prefs(context).getString(KEY_NTFY_TOPIC, "") ?: ""

    fun setNtfyTopic(context: Context, s: String) =
        prefs(context).edit().putString(KEY_NTFY_TOPIC, s).apply()

    // --- FCM config supplied at runtime (no build-time google-services.json) ---
    fun fcmProjectId(context: Context): String = prefs(context).getString(KEY_FCM_PROJECT, "") ?: ""
    fun fcmSenderId(context: Context): String = prefs(context).getString(KEY_FCM_SENDER, "") ?: ""
    fun fcmAppId(context: Context): String = prefs(context).getString(KEY_FCM_APP_ID, "") ?: ""
    fun fcmApiKey(context: Context): String = prefs(context).getString(KEY_FCM_API_KEY, "") ?: ""

    fun fcmConfigured(context: Context): Boolean =
        fcmAppId(context).isNotEmpty() && fcmApiKey(context).isNotEmpty() &&
            fcmProjectId(context).isNotEmpty() && fcmSenderId(context).isNotEmpty()

    /** Parse a pasted google-services.json into the four fields. Returns false on bad JSON. */
    fun setFcmFromJson(context: Context, json: String): Boolean = try {
        val o = JSONObject(json)
        val info = o.getJSONObject("project_info")
        val client = o.getJSONArray("client").getJSONObject(0)
        val appId = client.getJSONObject("client_info").getString("mobilesdk_app_id")
        val apiKey = client.getJSONArray("api_key").getJSONObject(0).getString("current_key")
        prefs(context).edit()
            .putString(KEY_FCM_PROJECT, info.getString("project_id"))
            .putString(KEY_FCM_SENDER, info.getString("project_number"))
            .putString(KEY_FCM_APP_ID, appId)
            .putString(KEY_FCM_API_KEY, apiKey)
            .apply()
        true
    } catch (e: Exception) {
        false
    }

    // --- sessions ---
    fun isActivated(context: Context): Boolean = sessionIds(context).isNotEmpty()

    /** Tab order — first-seen, stable. */
    fun sessionIds(context: Context): List<String> {
        val order = root(context).optJSONArray("order") ?: return emptyList()
        return (0 until order.length()).map { order.getString(it) }
    }

    fun session(context: Context, id: String): Session? {
        val o = root(context).optJSONObject("map")?.optJSONObject(id) ?: return null
        return parse(id, o)
    }

    /** Most-recently-updated session — drives the persistent notification. */
    fun latestSession(context: Context): Session? =
        sessionIds(context).mapNotNull { session(context, it) }.maxByOrNull { it.updated }

    fun setStatus(
        context: Context,
        id: String,
        project: String,
        status: String,
        detail: String,
        tokIn: Int,
        tokOut: Int,
        elapsedSeconds: Int,
    ) {
        if (id.isEmpty()) return
        val root = root(context)
        val order = root.optJSONArray("order") ?: JSONArray()
        val map = root.optJSONObject("map") ?: JSONObject()

        val prevEvents = map.optJSONObject(id)?.optJSONArray("events") ?: JSONArray()
        val events = JSONArray().put(
            JSONObject().put("t", System.currentTimeMillis()).put("s", status).put("d", detail)
        )
        for (i in 0 until minOf(prevEvents.length(), EVENT_CAP - 1)) events.put(prevEvents.getJSONObject(i))

        map.put(
            id,
            JSONObject()
                .put("project", project)
                .put("status", status)
                .put("detail", detail)
                .put("tin", tokIn)
                .put("tout", tokOut)
                .put("elapsed", elapsedSeconds)
                .put("updated", System.currentTimeMillis())
                .put("events", events)
        )
        if (!contains(order, id)) order.put(id)
        pruneToCap(order, map)

        root.put("order", order).put("map", map)
        prefs(context).edit().putString(KEY_SESSIONS, root.toString()).apply()
    }

    fun deleteSession(context: Context, id: String) {
        val root = root(context)
        val order = root.optJSONArray("order") ?: return
        val map = root.optJSONObject("map") ?: JSONObject()
        val kept = JSONArray()
        for (i in 0 until order.length()) {
            val sid = order.getString(i)
            if (sid != id) kept.put(sid)
        }
        map.remove(id)
        root.put("order", kept).put("map", map)
        prefs(context).edit().putString(KEY_SESSIONS, root.toString()).apply()
    }

    private fun pruneToCap(order: JSONArray, map: JSONObject) {
        while (order.length() > SESSION_CAP) {
            var oldestIdx = 0
            var oldestT = Long.MAX_VALUE
            for (i in 0 until order.length()) {
                val t = map.optJSONObject(order.getString(i))?.optLong("updated") ?: 0L
                if (t < oldestT) {
                    oldestT = t; oldestIdx = i
                }
            }
            map.remove(order.getString(oldestIdx))
            order.remove(oldestIdx)
        }
    }

    private fun contains(arr: JSONArray, s: String): Boolean {
        for (i in 0 until arr.length()) if (arr.getString(i) == s) return true
        return false
    }

    private fun parse(id: String, o: JSONObject): Session {
        val ev = o.optJSONArray("events") ?: JSONArray()
        val events = (0 until ev.length()).map { i ->
            val e = ev.getJSONObject(i)
            Event(e.optLong("t"), e.optString("s"), e.optString("d"))
        }
        return Session(
            id = id,
            project = o.optString("project", "—"),
            status = o.optString("status", ""),
            detail = o.optString("detail", ""),
            tokIn = o.optInt("tin", 0),
            tokOut = o.optInt("tout", 0),
            elapsed = o.optInt("elapsed", -1),
            updated = o.optLong("updated", 0L),
            events = events,
        )
    }

    private fun root(context: Context): JSONObject =
        JSONObject(prefs(context).getString(KEY_SESSIONS, "{}") ?: "{}")

    private fun prefs(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
