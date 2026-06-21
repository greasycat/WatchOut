package io.greasycat.watchout.complication

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/** Latest session as a SHORT_TEXT complication: project = title, status = text. */
class LatestComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData =
        shortText("WatchOut", "Thinking")

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        val latest = readLatestSession(this) ?: return shortText("WatchOut", "—")
        return shortText(latest.first, statusWord(latest.second))
    }

    private fun shortText(title: String, text: String) =
        ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder("$title: $text").build(),
        ).setTitle(PlainComplicationText.Builder(title).build()).build()
}

private fun statusWord(s: String) = when (s) {
    "needs_input" -> "Needs you"
    "done" -> "Done"
    "thinking", "update" -> "Thinking"
    else -> "Idle"
}
