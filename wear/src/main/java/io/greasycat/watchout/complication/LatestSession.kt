package io.greasycat.watchout.complication

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import io.greasycat.watchout.presentation.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

const val STATUS_PATH = "/claude/status"

/** Tapping a complication opens the watch app. Shared by both data sources. */
fun openWatchApp(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return PendingIntent.getActivity(
        context, 0, intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Newest session (max `u`) across the Data Layer item as (project, status); null if none. */
suspend fun readLatestSession(context: Context): Pair<String, String>? = withContext(Dispatchers.IO) {
    try {
        val items = Tasks.await(Wearable.getDataClient(context).dataItems)
        var best: Pair<String, String>? = null
        var bestU = Long.MIN_VALUE
        for (item in items) {
            if (item.uri.path != STATUS_PATH) continue
            val json = DataMapItem.fromDataItem(item).dataMap.getString("payload") ?: continue
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val u = o.optLong("u")
                if (u >= bestU) { bestU = u; best = o.optString("p", "—") to o.optString("s", "") }
            }
        }
        items.release()
        best
    } catch (e: Exception) {
        null
    }
}
