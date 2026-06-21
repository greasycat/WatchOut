package io.greasycat.watchout

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONArray
import org.json.JSONObject

/** Pushes all sessions to the watch over the Wearable Data Layer on each change. */
object WatchSync {
    const val PATH = "/claude/status"

    fun push(context: Context) {
        val arr = JSONArray()
        for (id in Prefs.sessionIds(context)) {
            val s = Prefs.session(context, id) ?: continue
            arr.put(
                JSONObject()
                    .put("p", s.project)
                    .put("s", s.status)
                    .put("d", s.detail)
                    .put("ti", s.tokIn)
                    .put("to", s.tokOut)
                    .put("e", s.elapsed)
                    .put("u", s.updated)
            )
        }
        val request = PutDataMapRequest.create(PATH).apply {
            dataMap.putString("payload", arr.toString())
            dataMap.putLong("ts", System.currentTimeMillis()) // force the item to change
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request)
    }
}
