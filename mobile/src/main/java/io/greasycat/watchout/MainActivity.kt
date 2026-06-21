package io.greasycat.watchout

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.color.MaterialColors

class MainActivity : AppCompatActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result unused */ }

    private lateinit var pager: ViewPager2
    private lateinit var dots: LinearLayout
    private lateinit var settingsButton: Button
    private lateinit var deleteButton: Button
    private lateinit var adapter: SessionPagerAdapter

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        pager = findViewById(R.id.pager)
        dots = findViewById(R.id.dots)
        settingsButton = findViewById(R.id.settings_button)
        deleteButton = findViewById(R.id.delete_button)
        applyInsets()

        adapter = SessionPagerAdapter(this)
        pager.adapter = adapter
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = renderDots(adapter.itemCount, position)
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val openSettings = View.OnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.open_settings).setOnClickListener(openSettings)
        settingsButton.setOnClickListener(openSettings)
        deleteButton.setOnClickListener {
            val id = adapter.ids().getOrNull(pager.currentItem) ?: return@setOnClickListener
            Prefs.deleteSession(this, id)
            StatusNotification.update(this) // latest session may have changed
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this, statusReceiver, IntentFilter(Prefs.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refresh()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    private fun refresh() {
        val ids = Prefs.sessionIds(this)
        val active = ids.isNotEmpty()
        findViewById<View>(R.id.setup_card).visibility = if (active) View.GONE else View.VISIBLE
        pager.visibility = if (active) View.VISIBLE else View.GONE
        settingsButton.visibility = if (active) View.VISIBLE else View.GONE
        deleteButton.visibility = if (active) View.VISIBLE else View.GONE
        if (active) {
            adapter.submit(ids)
            renderDots(ids.size, pager.currentItem)
        } else {
            dots.visibility = View.GONE
        }
    }

    /** Minimalistic dotted page indicator; hidden for a single session. */
    private fun renderDots(count: Int, selectedRaw: Int) {
        dots.removeAllViews()
        if (count <= 1) {
            dots.visibility = View.GONE
            return
        }
        dots.visibility = View.VISIBLE
        val selected = selectedRaw.coerceIn(0, count - 1)
        val primary = MaterialColors.getColor(dots, com.google.android.material.R.attr.colorPrimary)
        val muted = ColorUtils.setAlphaComponent(primary, 70)
        val d = resources.displayMetrics.density
        val size = (8 * d).toInt()
        val margin = (5 * d).toInt()
        for (i in 0 until count) {
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = margin; marginEnd = margin
                }
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.dot)
                backgroundTintList = ColorStateList.valueOf(if (i == selected) primary else muted)
            }
            dots.addView(dot)
        }
    }

    /** Top + side insets on the root; the Settings bar stays flush to the bottom,
     *  padding its label clear of the gesture bar. */
    private fun applyInsets() {
        val base = (16 * resources.displayMetrics.density).toInt()
        val top = (24 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top + top, bars.right, 0)
            settingsButton.setPadding(0, base, 0, base + bars.bottom)
            insets
        }
    }
}
