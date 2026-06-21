package io.greasycat.watchout

import android.content.ComponentName
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import io.greasycat.watchout.complication.LatestComplicationService
import io.greasycat.watchout.complication.SignalComplicationService

/** Pushes a complication refresh whenever the phone sends new status data. */
class StatusListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (cls in listOf(LatestComplicationService::class.java, SignalComplicationService::class.java)) {
            ComplicationDataSourceUpdateRequester
                .create(this, ComponentName(this, cls))
                .requestUpdateAll()
        }
    }
}
