package io.greasycat.watchout

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/** One swipe page = one Claude session. Renders the blob, ticks elapsed, and
 *  live-updates from the status broadcast while it is the resumed (visible) page. */
class SessionFragment : Fragment(R.layout.fragment_session) {

    private val sessionId: String get() = requireArguments().getString(ARG_ID).orEmpty()

    private val handler = Handler(Looper.getMainLooper())
    private var pulse: ObjectAnimator? = null
    private var frame = 0
    private val thinkingFrames = listOf("✶", "✷", "✸", "✹", "✸", "✷")
    private var elapsedBase = 0
    private var elapsedAnchor = 0L

    private val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

    private lateinit var project: TextView
    private lateinit var blob: View
    private lateinit var glyph: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusSubtext: TextView
    private lateinit var statusStats: TextView
    private lateinit var eventList: TextView

    private val glyphTick = object : Runnable {
        override fun run() {
            glyph.text = thinkingFrames[frame % thinkingFrames.size]
            frame++
            handler.postDelayed(this, 120)
        }
    }
    private val elapsedTick = object : Runnable {
        override fun run() {
            renderStats(elapsedBase + ((SystemClock.elapsedRealtime() - elapsedAnchor) / 1000).toInt())
            handler.postDelayed(this, 1000)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = render()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        project = view.findViewById(R.id.project)
        blob = view.findViewById(R.id.blob)
        glyph = view.findViewById(R.id.glyph)
        statusTitle = view.findViewById(R.id.status_title)
        statusSubtext = view.findViewById(R.id.status_subtext)
        statusStats = view.findViewById(R.id.status_stats)
        eventList = view.findViewById(R.id.event_list)
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            requireContext(), receiver, IntentFilter(Prefs.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        render()
    }

    override fun onPause() {
        super.onPause()
        requireContext().unregisterReceiver(receiver)
        stopThinking()
    }

    private fun render() {
        val s = Prefs.session(requireContext(), sessionId) ?: return
        project.text = s.project
        statusSubtext.text = s.detail
        statusSubtext.visibility = if (s.detail.isEmpty()) View.GONE else View.VISIBLE

        when (s.status) {
            "thinking", "update" -> {
                statusTitle.setText(R.string.status_thinking)
                startThinking(s)
            }
            "needs_input" -> {
                statusTitle.setText(R.string.status_needs); stopThinking("✦"); renderStats(s.elapsed)
            }
            "done" -> {
                statusTitle.setText(R.string.status_done); stopThinking("✓"); renderStats(s.elapsed)
            }
            else -> {
                statusTitle.setText(R.string.status_connected); stopThinking("✦"); renderStats(s.elapsed)
            }
        }
        renderEvents(s)
    }

    private fun renderStats(elapsedSeconds: Int) {
        val s = Prefs.session(requireContext(), sessionId) ?: return
        val parts = mutableListOf<String>()
        if (elapsedSeconds >= 0) parts.add(formatElapsed(elapsedSeconds))
        if (s.tokIn > 0 || s.tokOut > 0) parts.add("↑${fmt(s.tokIn)}  ↓${fmt(s.tokOut)}")
        statusStats.text = parts.joinToString("    ·    ")
        statusStats.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderEvents(s: Session) {
        val k = Prefs.eventCount(requireContext())
        val lines = s.events.take(k).joinToString("\n") { e ->
            val text = e.detail.ifEmpty { statusWord(e.status) }
            "${timeFmt.format(java.util.Date(e.time))}  ${glyphFor(e.status)}  $text"
        }
        eventList.text = lines
        eventList.visibility = if (k == 0 || lines.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun startThinking(s: Session) {
        handler.removeCallbacks(glyphTick); handler.post(glyphTick)
        // Continue from wall-clock: stored elapsed + time since the event was received,
        // so swiping back into a thinking session doesn't reset the timer.
        val sinceEvent = ((System.currentTimeMillis() - s.updated) / 1000).toInt().coerceAtLeast(0)
        elapsedBase = s.elapsed.coerceAtLeast(0) + sinceEvent
        elapsedAnchor = SystemClock.elapsedRealtime()
        handler.removeCallbacks(elapsedTick); handler.post(elapsedTick)
        if (pulse?.isStarted != true) {
            pulse = ObjectAnimator.ofPropertyValuesHolder(
                blob,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f),
            ).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    private fun stopThinking(staticGlyph: String? = null) {
        handler.removeCallbacks(glyphTick)
        handler.removeCallbacks(elapsedTick)
        pulse?.cancel(); pulse = null
        blob.scaleX = 1f; blob.scaleY = 1f
        if (staticGlyph != null) glyph.text = staticGlyph
    }

    private fun formatElapsed(s: Int) = if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
    private fun fmt(n: Int) = if (n >= 1000) String.format("%.1fk", n / 1000.0) else n.toString()
    private fun glyphFor(status: String) = when (status) {
        "needs_input" -> "✦"; "done" -> "✓"; "thinking", "update" -> "✶"; else -> "·"
    }
    private fun statusWord(status: String) = when (status) {
        "needs_input" -> "Needs you"; "done" -> "Done"; "thinking", "update" -> "Working"; else -> status
    }

    companion object {
        private const val ARG_ID = "session_id"
        fun newInstance(id: String) = SessionFragment().apply {
            arguments = Bundle().apply { putString(ARG_ID, id) }
        }
    }
}
