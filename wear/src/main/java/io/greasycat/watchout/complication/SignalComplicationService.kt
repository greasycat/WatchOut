package io.greasycat.watchout.complication

import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import io.greasycat.watchout.R

/**
 * Status glyph — shape carries the status (spark = thinking, bell = needs-you,
 * check = done, ring = idle), so it stays legible whether or not it's colored.
 *
 * Serves two complication types from one source:
 *   - MONOCHROMATIC_IMAGE → the watch face tints the glyph to its own theme.
 *   - SMALL_IMAGE         → the glyph in the status color (PHOTO, untinted).
 * Place whichever slot matches the look you want.
 */
class SignalComplicationService : SuspendingComplicationDataSourceService() {

    private data class Signal(val glyph: Int, val color: Int, val desc: String)

    override fun getPreviewData(type: ComplicationType): ComplicationData =
        build(type, Signal(R.drawable.ic_sig_thinking, COLOR_THINKING, "Claude status"))

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData =
        build(request.complicationType, signalFor(readLatestSession(this)?.second.orEmpty()))

    private fun signalFor(status: String): Signal = when (status) {
        "thinking", "update" -> Signal(R.drawable.ic_sig_thinking, COLOR_THINKING, "Thinking")
        "needs_input" -> Signal(R.drawable.ic_sig_needs, COLOR_NEEDS, "Needs you")
        "done" -> Signal(R.drawable.ic_sig_done, COLOR_DONE, "Done")
        else -> Signal(R.drawable.ic_sig_idle, COLOR_IDLE, "Idle")
    }

    private fun build(type: ComplicationType, s: Signal): ComplicationData {
        val text = PlainComplicationText.Builder(s.desc).build()
        val tap = openWatchApp(this) // tapping the complication opens the watch app
        return if (type == ComplicationType.MONOCHROMATIC_IMAGE) {
            // Plain (untinted) icon — the watch face applies its theme tint.
            MonochromaticImageComplicationData.Builder(
                MonochromaticImage.Builder(Icon.createWithResource(this, s.glyph)).build(), text,
            ).setTapAction(tap).build()
        } else {
            // PHOTO is never tinted by the face, so bake in the status color ourselves.
            val icon = Icon.createWithResource(this, s.glyph).setTint(s.color)
            SmallImageComplicationData.Builder(
                SmallImage.Builder(icon, SmallImageType.PHOTO).build(), text,
            ).setTapAction(tap).build()
        }
    }

    private companion object {
        const val COLOR_THINKING = 0xFFE5484D.toInt() // red
        const val COLOR_NEEDS = 0xFFF5A524.toInt()    // amber
        const val COLOR_DONE = 0xFF46A758.toInt()     // green
        const val COLOR_IDLE = 0xFF6F6F6F.toInt()     // gray
    }
}
