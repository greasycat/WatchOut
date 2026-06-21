package io.greasycat.watchout.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.SystemClock
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import org.json.JSONArray

private const val STATUS_PATH = "/claude/status"

private val Orange = Color(0xFFE8916F)
private val Blob = Color(0xFF5A2A17)
private val Bg = Color(0xFF1B1A18)
private val Ink = Color(0xFFECE7DE)
private val InkMuted = Color(0xFFA8A096)

private val thinkingFrames = listOf("✶", "✷", "✸", "✹", "✸", "✷")

data class WatchSession(
    val project: String,
    val status: String,
    val detail: String,
    val tin: Int,
    val tout: Int,
    val elapsed: Int,
    val updated: Long,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WearApp() }
    }
}

@Composable
fun WearApp() {
    val context = LocalContext.current
    var sessions by remember { mutableStateOf<List<WatchSession>>(emptyList()) }
    // Monotonic anchor at payload receipt, lifted to app scope so it survives page
    // swipes/disposal — this is what keeps the elapsed timer from resetting on swipe.
    var receiptAnchor by remember { mutableStateOf(SystemClock.elapsedRealtime()) }

    DisposableEffect(Unit) {
        val client = Wearable.getDataClient(context)
        // Per-project last-seen status, to buzz only on a real transition. `primed`
        // stays false until the first payload so the initial load never buzzes.
        val prevStatus = HashMap<String, String>()
        var primed = false
        fun apply(dataItem: com.google.android.gms.wearable.DataItem) {
            val dm = DataMapItem.fromDataItem(dataItem).dataMap
            val next = parseSessions(dm.getString("payload"), dm.getLong("ts"))
            if (primed) {
                var needsInput = false
                var done = false
                for (s in next) if (s.status != prevStatus[s.project]) {
                    if (s.status == "needs_input") needsInput = true
                    else if (s.status == "done") done = true
                }
                if (needsInput) buzz(context, needsInput = true)
                else if (done) buzz(context, needsInput = false)
            }
            prevStatus.clear()
            for (s in next) prevStatus[s.project] = s.status
            primed = true
            sessions = next
            receiptAnchor = SystemClock.elapsedRealtime()
        }
        val listener = DataClient.OnDataChangedListener { buffer ->
            for (event in buffer) {
                if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == STATUS_PATH) {
                    apply(event.dataItem)
                }
            }
        }
        client.addListener(listener)
        // Initial read so the screen shows current state immediately.
        client.dataItems.addOnSuccessListener { items ->
            for (item in items) {
                if (item.uri.path == STATUS_PATH) apply(item)
            }
            items.release()
        }
        onDispose { client.removeListener(listener) }
    }

    MaterialTheme {
        if (sessions.isEmpty()) {
            StatusScreen(null, receiptAnchor)
        } else {
            // Sessions arrive in stable first-seen order, so pages don't jump.
            val pagerState = rememberPagerState(pageCount = { sessions.size })
            Box(Modifier.fillMaxSize().background(Bg)) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    StatusScreen(sessions.getOrNull(page), receiptAnchor)
                }
                if (sessions.size > 1) {
                    Dots(
                        count = sessions.size,
                        selected = pagerState.currentPage,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Dots(count: Int, selected: Int, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (i == selected) Orange else InkMuted.copy(alpha = 0.4f)),
            )
        }
    }
}

@Composable
fun StatusScreen(s: WatchSession?, receiptAnchor: Long) {
    Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
        if (s == null) {
            Text(
                "Waiting for Claude…",
                color = InkMuted, fontSize = 14.sp, textAlign = TextAlign.Center,
            )
            return@Box
        }

        val thinking = s.status == "thinking" || s.status == "update"

        var frame by remember { mutableStateOf(0) }
        LaunchedEffect(s.updated, thinking) {
            while (thinking) {
                frame++ // drives recompose (glyph + elapsed) ~7x/sec
                delay(140)
            }
        }
        // s.elapsed already folds in the phone-side age; tick forward from the
        // app-scoped receiptAnchor so swiping pages never resets the timer.
        val elapsed = when {
            s.elapsed < 0 -> -1
            thinking -> s.elapsed + ((SystemClock.elapsedRealtime() - receiptAnchor) / 1000).toInt()
            else -> s.elapsed
        }
        val glyph = if (thinking) thinkingFrames[frame % thinkingFrames.size] else glyphFor(s.status)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Text(s.project, color = Orange, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(Blob),
                contentAlignment = Alignment.Center,
            ) {
                Text(glyph, color = Orange, fontSize = 30.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(titleFor(s.status), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (s.detail.isNotEmpty()) {
                Text(
                    s.detail, color = InkMuted, fontSize = 11.sp,
                    maxLines = 2, textAlign = TextAlign.Center,
                )
            }
            val stats = statsLine(elapsed, s.tin, s.tout)
            if (stats.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(stats, color = Orange, fontSize = 11.sp)
            }
        }
    }
}

private fun parseSessions(json: String?, phoneNow: Long): List<WatchSession> {
    if (json.isNullOrEmpty()) return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val e = o.optInt("e", -1)
            val u = o.optLong("u")
            // Fold the phone-side age into the base using the phone's OWN clock
            // (phoneNow and u are both phone-clock), so it's correct regardless of
            // phone/watch clock skew. The watch then ticks forward from receipt.
            val base = if (e < 0) -1 else e + ((phoneNow - u).coerceAtLeast(0L) / 1000).toInt()
            WatchSession(
                o.optString("p", "—"), o.optString("s", ""), o.optString("d", ""),
                o.optInt("ti"), o.optInt("to"), base, u,
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/** One pulse for done, a double pulse for needs-input. */
private fun buzz(context: android.content.Context, needsInput: Boolean) {
    val vib = if (android.os.Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                as android.os.VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
    }
    val effect = if (needsInput)
        android.os.VibrationEffect.createWaveform(longArrayOf(0, 130, 110, 130), -1)
    else
        android.os.VibrationEffect.createOneShot(170, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
    vib.vibrate(effect)
}

private fun glyphFor(s: String) = when (s) {
    "needs_input" -> "✦"; "done" -> "✓"; "thinking", "update" -> "✶"; else -> "·"
}

private fun titleFor(s: String) = when (s) {
    "needs_input" -> "Needs you"; "done" -> "Done"; "thinking", "update" -> "Thinking…"; else -> "Claude"
}

private fun statsLine(elapsed: Int, tin: Int, tout: Int): String {
    val parts = mutableListOf<String>()
    if (elapsed >= 0) parts.add(if (elapsed < 60) "${elapsed}s" else "${elapsed / 60}m ${elapsed % 60}s")
    if (tin > 0 || tout > 0) parts.add("↑${fmt(tin)} ↓${fmt(tout)}")
    return parts.joinToString("  ·  ")
}

private fun fmt(n: Int) = if (n >= 1000) String.format("%.1fk", n / 1000.0) else n.toString()
