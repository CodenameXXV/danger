package ua.nebosumy

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val prefs = getSharedPreferences("s", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
        }

        toggle = Button(this)
        toggle.setOnClickListener {
            val i = Intent(this, MonitorService::class.java)
            if (Bus.running) stopService(i) else startForegroundService(i)
            ui.postDelayed({ refresh() }, 300)
        }
        root.addView(toggle)

        val last = Button(this)
        last.text = "Програти останнє голосове"
        last.setOnClickListener {
            if (Bus.running) {
                Bus.playLast = true
            } else {
                Toast.makeText(this, "Спочатку натисніть Старт", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(last)

        addSeek(root, prefs, "interval", 1, 30, 2, "Інтервал перевірки: %d с")
        addSeek(root, prefs, "volume", 10, 100, 100, "Гучність: %d%%")

        val battery = Button(this)
        battery.text = "Налаштування економії батареї"
        battery.setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        root.addView(battery)

        status = TextView(this).apply { textSize = 15f; setPadding(0, 30, 0, 0) }
        val scroll = ScrollView(this)
        scroll.addView(status)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        Bus.listener = { s -> ui.post { status.text = s } }
        status.text = Bus.lastText
        refresh()
    }

    override fun onPause() {
        Bus.listener = null
        super.onPause()
    }

    private fun refresh() {
        toggle.text = if (Bus.running) "Стоп" else "Старт"
    }

    private fun addSeek(
        root: LinearLayout,
        prefs: SharedPreferences,
        key: String,
        min: Int,
        max: Int,
        def: Int,
        fmt: String
    ) {
        val label = TextView(this).apply { setPadding(0, 30, 0, 0) }
        val bar = SeekBar(this)
        val v = prefs.getInt(key, def)
        bar.max = max - min
        bar.progress = v - min
        label.text = String.format(fmt, v)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                label.text = String.format(fmt, p + min)
                prefs.edit().putInt(key, p + min).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(label)
        root.addView(bar)
    }
}
