package io.greasycat.watchout

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import io.greasycat.watchout.complication.LatestComplicationService
import io.greasycat.watchout.complication.STATUS_PATH
import io.greasycat.watchout.complication.SignalComplicationService
import org.json.JSONArray

/**
 * On new phone status: refresh the complications, and buzz the watch directly when
 * Claude comes back (needs-input / done). The watch can't rely on the phone's
 * notification bridging, so it vibrates itself.
 */
class StatusListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        for (cls in listOf(LatestComplicationService::class.java, SignalComplicationService::class.java)) {
            ComplicationDataSourceUpdateRequester
                .create(this, ComponentName(this, cls))
                .requestUpdateAll()
        }
        maybeBuzz(events)
    }

    /** Buzz once per come-back signal, keyed on the session's `updated` ts so a redundant
     *  re-sync of the same state doesn't double-buzz. */
    private fun maybeBuzz(events: DataEventBuffer) {
        var status = ""
        var updated = Long.MIN_VALUE
        for (event in events) {
            val item = event.dataItem
            if (item.uri.path != STATUS_PATH) continue
            val json = DataMapItem.fromDataItem(item).dataMap.getString("payload") ?: continue
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val u = o.optLong("u")
                if (u >= updated) { updated = u; status = o.optString("s", "") }
            }
        }
        if (status != "needs_input" && status != "done") return

        val prefs = getSharedPreferences("watchout_wear", Context.MODE_PRIVATE)
        if (updated <= prefs.getLong("last_buzz_u", Long.MIN_VALUE)) return
        prefs.edit().putLong("last_buzz_u", updated).apply()

        buzz(urgent = status == "needs_input")
    }

    private fun buzz(urgent: Boolean) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!vibrator.hasVibrator()) return
        // needs-input is more urgent → a double pulse; done → a single pulse.
        val timings = if (urgent) longArrayOf(0, 250, 150, 250) else longArrayOf(0, 300)
        vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
    }
}
