package io.greasycat.watchout

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.google.firebase.messaging.FirebaseMessaging
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings_root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val pad = (24 * resources.displayMetrics.density).toInt()
            v.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom + pad)
            insets
        }

        setupTransport()
        setupFcm()
        setupDirect()
        setupNtfy()
        setupEventsAndPersistent()
    }

    override fun onPause() {
        super.onPause()
        IngestService.sync(this) // apply any transport/config changes
    }

    // --- transport picker ---
    private fun setupTransport() {
        val group = findViewById<RadioGroup>(R.id.transport_group)
        when (Prefs.transport(this)) {
            Prefs.TRANSPORT_DIRECT -> group.check(R.id.radio_direct)
            Prefs.TRANSPORT_NTFY -> group.check(R.id.radio_ntfy)
            else -> group.check(R.id.radio_fcm)
        }
        updateSections(Prefs.transport(this))
        group.setOnCheckedChangeListener { _, id ->
            val t = when (id) {
                R.id.radio_direct -> Prefs.TRANSPORT_DIRECT
                R.id.radio_ntfy -> Prefs.TRANSPORT_NTFY
                else -> Prefs.TRANSPORT_FCM
            }
            Prefs.setTransport(this, t)
            updateSections(t)
            IngestService.sync(this)
        }
    }

    private fun updateSections(t: String) {
        findViewById<android.view.View>(R.id.fcm_section).visibility =
            if (t == Prefs.TRANSPORT_FCM) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.direct_section).visibility =
            if (t == Prefs.TRANSPORT_DIRECT) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.ntfy_section).visibility =
            if (t == Prefs.TRANSPORT_NTFY) android.view.View.VISIBLE else android.view.View.GONE
    }

    // --- FCM (runtime config, no build-time google-services.json) ---
    private fun setupFcm() {
        val paste = findViewById<EditText>(R.id.fcm_paste)
        val tokenView = findViewById<TextView>(R.id.token)
        val copyButton = findViewById<Button>(R.id.copy_button)

        fun refreshToken() {
            if (!FcmInit.ensure(this)) {
                tokenView.text = getString(R.string.fcm_need_config)
                copyButton.isEnabled = false
                return
            }
            try {
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Log.i("ClaudeFCM", "FCM token: ${task.result}")
                        tokenView.text = task.result
                        copyButton.isEnabled = true
                        copyButton.setOnClickListener { copyToClipboard(task.result) }
                    } else {
                        tokenView.text =
                            getString(R.string.token_error, task.exception?.message ?: "unknown")
                    }
                }
            } catch (e: Exception) {
                tokenView.text = getString(R.string.token_error, e.message ?: "init")
            }
        }

        findViewById<Button>(R.id.fcm_apply).setOnClickListener {
            if (Prefs.setFcmFromJson(this, paste.text.toString())) {
                refreshToken()
            } else {
                tokenView.text = getString(R.string.token_error, "bad google-services.json")
            }
        }
        refreshToken()
    }

    // --- direct ---
    private fun setupDirect() {
        val port = findViewById<EditText>(R.id.direct_port)
        port.setText(Prefs.directPort(this).toString())
        port.doAfterTextChanged { e ->
            e?.toString()?.toIntOrNull()?.let { if (it in 1..65535) Prefs.setDirectPort(this, it) }
        }

        // Shared secret: auto-generate once so direct is authed by default; user copies it into the hook config.
        val tokenField = findViewById<EditText>(R.id.direct_token)
        if (Prefs.directToken(this).isEmpty()) {
            Prefs.setDirectToken(this, java.util.UUID.randomUUID().toString().replace("-", "").take(20))
        }
        tokenField.setText(Prefs.directToken(this))
        tokenField.doAfterTextChanged { Prefs.setDirectToken(this, it?.toString().orEmpty().trim()) }

        val ips = findViewById<TextView>(R.id.direct_ips)
        val p = Prefs.directPort(this)
        val list = NetUtils.localIps()
        ips.text = if (list.isEmpty()) "no network address"
        else "This phone:\n" + list.joinToString("\n") { "  $it:$p" }

        val result = findViewById<TextView>(R.id.direct_result)
        findViewById<Button>(R.id.direct_test).setOnClickListener {
            IngestService.sync(this) // ensure the listener is running
            result.text = "testing…"
            val testPort = Prefs.directPort(this)
            Thread {
                val ok = loopbackPing(testPort)
                ui.post {
                    result.text = if (ok) "✓ listener is up on :$testPort"
                    else "✗ listener not responding on :$testPort"
                    ips.text = "This phone:\n" + NetUtils.localIps().joinToString("\n") { "  $it:$testPort" }
                }
            }.start()
        }
    }

    private fun loopbackPing(port: Int): Boolean = try {
        val c = URL("http://127.0.0.1:$port/ping").openConnection() as HttpURLConnection
        c.connectTimeout = 1500
        c.readTimeout = 1500
        val code = c.responseCode
        c.disconnect()
        code in 200..299
    } catch (e: Exception) {
        false
    }

    // --- ntfy ---
    private fun setupNtfy() {
        val server = findViewById<EditText>(R.id.ntfy_server)
        val topic = findViewById<EditText>(R.id.ntfy_topic)
        server.setText(Prefs.ntfyServer(this))
        topic.setText(Prefs.ntfyTopic(this))
        server.doAfterTextChanged { Prefs.setNtfyServer(this, it?.toString().orEmpty()) }
        topic.doAfterTextChanged { Prefs.setNtfyTopic(this, it?.toString().orEmpty()) }
    }

    // --- shared ---
    private fun setupEventsAndPersistent() {
        val heading = findViewById<TextView>(R.id.events_heading)
        val slider = findViewById<com.google.android.material.slider.Slider>(R.id.events_slider)
        val k = Prefs.eventCount(this)
        heading.text = getString(R.string.events_heading, k)
        slider.value = k.toFloat()
        slider.addOnChangeListener { _, value, _ ->
            val n = value.toInt()
            Prefs.setEventCount(this, n)
            heading.text = getString(R.string.events_heading, n)
        }

        val persistent =
            findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.persistent_switch)
        persistent.isChecked = Prefs.persistentEnabled(this)
        persistent.setOnCheckedChangeListener { _, on ->
            Prefs.setPersistent(this, on)
            StatusNotification.update(this)
        }
    }

    private fun copyToClipboard(token: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("FCM token", token))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.token_copied, Toast.LENGTH_SHORT).show()
        }
    }
}
