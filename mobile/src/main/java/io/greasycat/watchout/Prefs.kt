package io.greasycat.watchout

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A single Claude event for the under-blob history list. */
data class Event(val time: Long, val status: String, val detail: String)

/** Tiny shared-prefs wrapper for the latest Claude status, history, and config. */
object Prefs {
    /** Broadcast sent by the messaging service so an open home screen re-renders. */
    const val ACTION_STATUS = "io.greasycat.watchout.STATUS"
    const val DEFAULT_EVENT_COUNT = 5

    private const val NAME = "watchout"
    private const val KEY_ACTIVATED = "activated"
    private const val KEY_STATUS = "status"
    private const val KEY_DETAIL = "detail"
    private const val KEY_TOK_IN = "tok_in"
    private const val KEY_TOK_OUT = "tok_out"
    private const val KEY_ELAPSED = "elapsed_s"
    private const val KEY_EVENTS = "events"
    private const val KEY_EVENT_COUNT = "event_count"
    private const val KEY_PERSISTENT = "persistent"

    private const val EVENT_CAP = 50 // history kept; UI shows up to eventCount

    fun isActivated(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ACTIVATED, false)

    fun status(context: Context): String? =
        prefs(context).getString(KEY_STATUS, null)

    fun detail(context: Context): String =
        prefs(context).getString(KEY_DETAIL, "") ?: ""

    fun tokIn(context: Context): Int = prefs(context).getInt(KEY_TOK_IN, 0)
    fun tokOut(context: Context): Int = prefs(context).getInt(KEY_TOK_OUT, 0)

    fun elapsedSeconds(context: Context): Int = prefs(context).getInt(KEY_ELAPSED, -1)

    /** How many recent events to list under the blob (0..10). */
    fun eventCount(context: Context): Int =
        prefs(context).getInt(KEY_EVENT_COUNT, DEFAULT_EVENT_COUNT)

    fun setEventCount(context: Context, k: Int) {
        prefs(context).edit().putInt(KEY_EVENT_COUNT, k).apply()
    }

    /** Persistent (ongoing) status notification mirroring the home blob. Default on. */
    fun persistentEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PERSISTENT, true)

    fun setPersistent(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_PERSISTENT, on).apply()
    }

    /** Most-recent-first history, capped at EVENT_CAP. */
    fun events(context: Context): List<Event> {
        val arr = JSONArray(prefs(context).getString(KEY_EVENTS, "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Event(o.optLong("t"), o.optString("s"), o.optString("d"))
        }
    }

    /** First call also flips "activated" true; every call prepends to the history. */
    fun setStatus(
        context: Context,
        status: String,
        detail: String,
        tokIn: Int,
        tokOut: Int,
        elapsedSeconds: Int,
    ) {
        val p = prefs(context)
        val old = JSONArray(p.getString(KEY_EVENTS, "[]"))
        val updated = JSONArray().put(
            JSONObject()
                .put("t", System.currentTimeMillis())
                .put("s", status)
                .put("d", detail)
        )
        for (i in 0 until minOf(old.length(), EVENT_CAP - 1)) updated.put(old.getJSONObject(i))
        p.edit()
            .putBoolean(KEY_ACTIVATED, true)
            .putString(KEY_STATUS, status)
            .putString(KEY_DETAIL, detail)
            .putInt(KEY_TOK_IN, tokIn)
            .putInt(KEY_TOK_OUT, tokOut)
            .putInt(KEY_ELAPSED, elapsedSeconds)
            .putString(KEY_EVENTS, updated.toString())
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
