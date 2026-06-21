package io.greasycat.watchout.complication

import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import io.greasycat.watchout.R

/** Traffic-light dot: red = thinking, amber = needs input, green = done. */
class SignalComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData =
        dot(R.drawable.dot_amber, "Claude status")

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        val status = readLatestSession(this)?.second.orEmpty()
        val (res, desc) = when (status) {
            "thinking", "update" -> R.drawable.dot_red to "Thinking"
            "needs_input" -> R.drawable.dot_amber to "Needs you"
            "done" -> R.drawable.dot_green to "Done"
            else -> R.drawable.dot_gray to "Idle"
        }
        return dot(res, desc)
    }

    // PHOTO type is rendered untinted, so the dot keeps its color on any watch face.
    private fun dot(res: Int, desc: String) =
        SmallImageComplicationData.Builder(
            SmallImage.Builder(Icon.createWithResource(this, res), SmallImageType.PHOTO).build(),
            PlainComplicationText.Builder(desc).build(),
        ).build()
}
