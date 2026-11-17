package ua.nebosumy

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var list: LinearLayout
    private lateinit var input: EditText
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
        last.text = "Програти останнє повідомлення"
        last.setOnClickListener {
            if (Bus.running) {
                Bus.playLast = true
            } else {
                Toast.makeText(this, "Спочатку натисніть «Почати моніторинг»", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(last)

        root.addView(header("Канали"))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        input = EditText(this).apply {
            hint = "Посилання або @назва каналу"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        root.addView(input)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val check = Button(this)
        check.text = "Перевірити"
        check.setOnClickListener {
            val n = Store.parseName(input.text.toString())
            if (n == null) {
                Toast.makeText(this, "Не вдалося розпізнати канал", Toast.LENGTH_SHORT).show()
            } else {
                runTest(n)
            }
        }
        val add = Button(this)
        add.text = "Додати"
        add.setOnClickListener {
            val n = Store.parseName(input.text.toString())
            if (n == null) {
                Toast.makeText(this, "Не вдалося розпізнати канал", Toast.LENGTH_SHORT).show()
            } else {
                val l = Store.channels(this)
                if (l.any { it.first.equals(n, true) }) {
                    Toast.makeText(this, "Цей канал уже є в списку", Toast.LENGTH_SHORT).show()
                } else {
                    l.add(n to true)
                    Store.save(this, l)
                    input.setText("")
                    renderChannels()
                }
            }
        }
        row.addView(check, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(add, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)

        root.addView(header("Налаштування"))
        addSeek(root, prefs, "interval", 1, 30, 2, "Інтервал перевірки: %d с")
        addSeek(root, prefs, "volume", 10, 100, 100, "Гучність: %d%%")
        addSeek(root, prefs, "maxwords", 5, 200, 40, "Не озвучувати тексти довші за %d слів")
        addCheck(root, prefs, "tts", true, "Озвучувати текстові повідомлення")
        addCheck(root, prefs, "autostart", false, "Автозапуск після ввімкнення телефону")

        val battery = Button(this)
        battery.text = "Налаштування економії батареї"
        battery.setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        root.addView(battery)

        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, 40, 0, 0)
        }
        root.addView(status)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        renderChannels()
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
        toggle.text = if (Bus.running) "Зупинити моніторинг" else "Почати моніторинг"
    }

    private fun header(t: String) = TextView(this).apply {
        text = t
        textSize = 18f
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 40, 0, 10)
    }

    private fun renderChannels() {
        list.removeAllViews()
        for (ch in Store.channels(this)) {
            val name = ch.first
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val cb = CheckBox(this)
            cb.text = "@$name"
            cb.isChecked = ch.second
            cb.setOnCheckedChangeListener { _, v ->
                val l = Store.channels(this)
                val idx = l.indexOfFirst { it.first == name }
                if (idx >= 0) {
                    l[idx] = name to v
                    Store.save(this, l)
                }
            }
            val test = Button(this)
            test.text = "Тест"
            test.setOnClickListener { runTest(name) }
            val del = Button(this)
            del.text = "✕"
            del.setOnClickListener {
                val l = Store.channels(this)
                l.removeAll { it.first == name }
                Store.save(this, l)
                renderChannels()
            }
            row.addView(cb, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(test)
            row.addView(del)
            list.addView(row)
        }
    }

    private fun runTest(name: String) {
        Toast.makeText(this, "Перевіряю @$name…", Toast.LENGTH_SHORT).show()
        Thread {
            val r = try {
                Telegram.test(name)
            } catch (e: Exception) {
                "Помилка: ${e.message}"
            }
            ui.post {
                if (!isFinishing) {
                    AlertDialog.Builder(this)
                        .setTitle("@$name")
                        .setMessage(r)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
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

    private fun addCheck(root: LinearLayout, prefs: SharedPreferences, key: String, def: Boolean, label: String) {
        val cb = CheckBox(this)
        cb.text = label
        cb.isChecked = prefs.getBoolean(key, def)
        cb.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(key, v).apply() }
        root.addView(cb)
    }
}
