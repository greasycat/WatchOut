package io.greasycat.watchout

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.messaging.FirebaseMessaging

class SettingsActivity : AppCompatActivity() {

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

        // Events-under-blob count (k).
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

        val persistent = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.persistent_switch)
        persistent.isChecked = Prefs.persistentEnabled(this)
        persistent.setOnCheckedChangeListener { _, on ->
            Prefs.setPersistent(this, on)
            StatusNotification.update(this) // post or cancel immediately
        }

        val tokenView = findViewById<TextView>(R.id.token)
        val copyButton = findViewById<Button>(R.id.copy_button)
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Log.i("ClaudeFCM", "FCM token: ${task.result}")
                tokenView.text = task.result
                copyButton.isEnabled = true
                copyButton.setOnClickListener { copyToClipboard(task.result) }
            } else {
                tokenView.text = getString(R.string.token_error, task.exception?.message ?: "unknown")
            }
        }
    }

    private fun copyToClipboard(token: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("FCM token", token))
        // Android 13+ shows its own copy confirmation, so only toast below that.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.token_copied, Toast.LENGTH_SHORT).show()
        }
    }
}
