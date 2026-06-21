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

    private const val NAME = "watchout"
    private const val KEY_SESSIONS = "sessions_v2" // {order:[ids], map:{id:obj}}
    private const val KEY_EVENT_COUNT = "event_count"
    private const val KEY_PERSISTENT = "persistent"

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
