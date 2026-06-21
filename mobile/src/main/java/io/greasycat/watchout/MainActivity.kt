package io.greasycat.watchout

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result unused */ }

    private val handler = Handler(Looper.getMainLooper())
    private var pulse: ObjectAnimator? = null
    private var frame = 0

    // Sparkle frames echo Claude Code's twinkling thinking glyph.
    private val thinkingFrames = listOf("✶", "✷", "✸", "✹", "✸", "✷")

    // Live elapsed timer: tick up from the value received, anchored to a monotonic clock.
    private var elapsedBase = 0
    private var elapsedAnchor = 0L

    private lateinit var blob: View
    private lateinit var glyph: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusSubtext: TextView
    private lateinit var statusStats: TextView
    private lateinit var eventList: TextView

    private val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

    private val glyphTick = object : Runnable {
        override fun run() {
            glyph.text = thinkingFrames[frame % thinkingFrames.size]
            frame++
            handler.postDelayed(this, 120)
        }
    }

    private val elapsedTick = object : Runnable {
        override fun run() {
            val live = elapsedBase + ((SystemClock.elapsedRealtime() - elapsedAnchor) / 1000).toInt()
            renderStats(live)
            handler.postDelayed(this, 1000)
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        applyInsetPadding(R.id.main)

        blob = findViewById(R.id.blob)
        glyph = findViewById(R.id.glyph)
        statusTitle = findViewById(R.id.status_title)
        statusSubtext = findViewById(R.id.status_subtext)
        statusStats = findViewById(R.id.status_stats)
        eventList = findViewById(R.id.event_list)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val openSettings = View.OnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.open_settings).setOnClickListener(openSettings)
        findViewById<Button>(R.id.settings_button).setOnClickListener(openSettings)
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this, statusReceiver, IntentFilter(Prefs.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        render()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
        stopThinking()
    }

    private fun render() {
        val activated = Prefs.isActivated(this)
        findViewById<View>(R.id.setup_card).visibility = if (activated) View.GONE else View.VISIBLE
        findViewById<View>(R.id.status_box).visibility = if (activated) View.VISIBLE else View.GONE
        findViewById<View>(R.id.settings_button).visibility = if (activated) View.VISIBLE else View.GONE
        if (!activated) {
            stopThinking()
            return
        }

        val detail = Prefs.detail(this)
        statusSubtext.text = detail
        statusSubtext.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE

        when (Prefs.status(this)) {
            "thinking", "update" -> {
                statusTitle.setText(R.string.status_thinking)
                startThinking() // also starts the live elapsed ticker (which renders stats)
            }
            "needs_input" -> {
                statusTitle.setText(R.string.status_needs)
                stopThinking(staticGlyph = "✦")
                renderStats(Prefs.elapsedSeconds(this))
            }
            "done" -> {
                statusTitle.setText(R.string.status_done)
                stopThinking(staticGlyph = "✓")
                renderStats(Prefs.elapsedSeconds(this))
            }
            else -> {
                statusTitle.setText(R.string.status_connected)
                stopThinking(staticGlyph = "✦")
                renderStats(Prefs.elapsedSeconds(this))
            }
        }
        renderEvents()
    }

    private fun renderEvents() {
        val k = Prefs.eventCount(this)
        val lines = Prefs.events(this).take(k).joinToString("\n") { e ->
            val text = e.detail.ifEmpty { statusWord(e.status) }
            "${timeFmt.format(java.util.Date(e.time))}  ${glyphFor(e.status)}  $text"
        }
        eventList.text = lines
        eventList.visibility = if (k == 0 || lines.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun glyphFor(status: String): String = when (status) {
        "needs_input" -> "✦"
        "done" -> "✓"
        "thinking", "update" -> "✶"
        else -> "·"
    }

    private fun statusWord(status: String): String = when (status) {
        "needs_input" -> "Needs you"
        "done" -> "Done"
        "thinking", "update" -> "Working"
        else -> status
    }

    /** elapsedSeconds < 0 hides the time chip; tokens hidden when both are zero. */
    private fun renderStats(elapsedSeconds: Int) {
        val parts = mutableListOf<String>()
        if (elapsedSeconds >= 0) parts.add(formatElapsed(elapsedSeconds))
        val tin = Prefs.tokIn(this)
        val tout = Prefs.tokOut(this)
        if (tin > 0 || tout > 0) parts.add("↑${formatTokens(tin)}  ↓${formatTokens(tout)}")
        statusStats.text = parts.joinToString("    ·    ")
        statusStats.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun formatElapsed(s: Int): String =
        if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"

    private fun formatTokens(n: Int): String =
        if (n >= 1000) String.format("%.1fk", n / 1000.0) else n.toString()

    private fun startThinking() {
        handler.removeCallbacks(glyphTick)
        handler.post(glyphTick)
        elapsedBase = Prefs.elapsedSeconds(this).coerceAtLeast(0)
        elapsedAnchor = SystemClock.elapsedRealtime()
        handler.removeCallbacks(elapsedTick)
        handler.post(elapsedTick)
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
        pulse?.cancel()
        pulse = null
        blob.scaleX = 1f
        blob.scaleY = 1f
        if (staticGlyph != null) glyph.text = staticGlyph
    }

    private fun applyInsetPadding(rootId: Int) {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(rootId)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val pad = (24 * resources.displayMetrics.density).toInt()
            v.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom + pad)
            insets
        }
    }
}
