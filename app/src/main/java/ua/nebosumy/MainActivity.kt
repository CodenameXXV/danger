package ua.nebosumy

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.RelativeSizeSpan
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private lateinit var feed: LinearLayout
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var heroDot: View
    private lateinit var toggle: TextView
    private lateinit var chip: TextView
    private lateinit var channelsBox: LinearLayout
    private lateinit var input: EditText
    private lateinit var sysBar: SeekBar
    private val pages = ArrayList<View>()
    private val tabs = ArrayList<TextView>()
    private val ui = Handler(Looper.getMainLooper())
    private val am by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val density by lazy { resources.displayMetrics.density }

    private fun dp(n: Int) = (n * density + 0.5f).toInt()

    private fun col(id: Int) = getColor(id)

    private fun alpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a shl 24)

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun shape(color: Int, radius: Int, stroke: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radius).toFloat()
        if (stroke != 0) g.setStroke(dp(1), stroke)
        return g
    }

    private fun style(v: TextView, fill: Int, fg: Int, radius: Int = 14) {
        v.setTextColor(fg)
        v.background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(50, 128, 128, 128)),
            shape(fill, radius),
            shape(Color.WHITE, radius)
        )
    }

    private fun tv(t: String, size: Float = 15f, color: Int = col(R.color.text), bold: Boolean = false): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setTextColor(color)
        if (bold) v.setTypeface(null, Typeface.BOLD)
        return v
    }

    private fun btn(t: String, fill: Int, fg: Int, onClick: () -> Unit): TextView {
        val v = tv(t, 15f, fg, true)
        v.gravity = Gravity.CENTER
        v.minHeight = dp(52)
        v.setPadding(dp(16), dp(12), dp(16), dp(12))
        v.isClickable = true
        style(v, fill, fg)
        v.setOnClickListener { onClick() }
        return v
    }

    private fun vbox(): LinearLayout {
        val b = LinearLayout(this)
        b.orientation = LinearLayout.VERTICAL
        return b
    }

    private fun card(): LinearLayout {
        val c = vbox()
        c.background = shape(col(R.color.card), 20, col(R.color.line))
        c.elevation = dp(1).toFloat()
        c.setPadding(dp(18), dp(16), dp(18), dp(18))
        val p = LinearLayout.LayoutParams(-1, -2)
        p.setMargins(0, 0, 0, dp(14))
        c.layoutParams = p
        return c
    }

    private fun cardTitle(t: String): TextView {
        val v = tv(t, 16f, col(R.color.accent), true)
        v.setPadding(0, 0, 0, dp(4))
        return v
    }

    private fun field(hint: String, value: String): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setText(value)
        e.setSingleLine()
        e.setTextColor(col(R.color.text))
        e.setHintTextColor(col(R.color.muted))
        e.textSize = 15f
        e.background = shape(col(R.color.bg), 12, col(R.color.line))
        e.setPadding(dp(14), dp(12), dp(14), dp(12))
        return e
    }

    private fun oval(color: Int) = GradientDrawable().apply {
        setShape(GradientDrawable.OVAL)
        setColor(color)
    }

    private fun tint(s: Switch) {
        val on = col(R.color.accent)
        val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        s.thumbTintList = ColorStateList(st, intArrayOf(on, col(R.color.muted)))
        s.trackTintList = ColorStateList(st, intArrayOf(alpha(on, 90), alpha(col(R.color.muted), 70)))
    }

    private fun tint(b: SeekBar) {
        b.progressTintList = ColorStateList.valueOf(col(R.color.accent))
        b.thumbTintList = ColorStateList.valueOf(col(R.color.accent))
    }

    private fun page(content: LinearLayout): ScrollView {
        val sv = ScrollView(this)
        sv.isFillViewport = true
        content.setPadding(dp(16), dp(16), dp(16), dp(24))
        sv.addView(content, FrameLayout.LayoutParams(-1, -2))
        return sv
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setVolumeControlStream(AudioManager.STREAM_MUSIC)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val prefs = getSharedPreferences("s", MODE_PRIVATE)

        val root = vbox()
        root.addView(appBar())
        val frame = FrameLayout(this)
        pages.add(homePage())
        pages.add(channelsPage())
        pages.add(settingsPage(prefs))
        for (p in pages) frame.addView(p, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, lp(-1, 0, 1f))
        root.addView(bottomBar())
        setContentView(root)
        select(0)
        renderChannels()
    }

    override fun onResume() {
        super.onResume()
        Bus.listener = { s ->
            ui.post {
                renderFeed(s)
                refresh()
            }
        }
        renderFeed(Bus.lastText)
        sysBar.progress = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        refresh()
    }

    override fun onPause() {
        Bus.listener = null
        super.onPause()
    }

    private fun appBar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(col(R.color.bar))
        bar.setPadding(dp(20), dp(14), dp(20), dp(14))
        bar.addView(tv("Монітор", 22f, col(R.color.text), true), lp(0, -2, 1f))
        chip = tv("", 13f, col(R.color.muted), true)
        chip.setPadding(dp(12), dp(6), dp(12), dp(6))
        bar.addView(chip)
        return bar
    }

    private fun bottomBar(): View {
        val wrap = vbox()
        val line = View(this)
        line.setBackgroundColor(col(R.color.line))
        wrap.addView(line, lp(-1, dp(1)))
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(col(R.color.bar))
        val items = listOf("🏠" to "Головна", "📡" to "Канали", "⚙️" to "Налаштування")
        items.forEachIndexed { i, (emoji, label) ->
            val t = TextView(this)
            val sp = SpannableString(emoji + "\n" + label)
            sp.setSpan(RelativeSizeSpan(1.7f), 0, emoji.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            t.text = sp
            t.textSize = 12f
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(6), 0, dp(6))
            t.isClickable = true
            t.setOnClickListener { select(i) }
            tabs.add(t)
            val tp = lp(0, -2, 1f)
            tp.setMargins(dp(8), dp(6), dp(8), dp(6))
            bar.addView(t, tp)
        }
        wrap.addView(bar)
        return wrap
    }

    private fun select(index: Int) {
        pages.forEachIndexed { i, p -> p.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, t ->
            t.setTextColor(if (i == index) col(R.color.accent) else col(R.color.muted))
            t.setTypeface(null, if (i == index) Typeface.BOLD else Typeface.NORMAL)
            t.background = if (i == index) shape(col(R.color.accent_soft), 16) else null
        }
    }

    private fun refresh() {
        val on = Bus.running
        val n = Store.channels(this).count { it.second }
        val every = getSharedPreferences("s", MODE_PRIVATE).getInt("interval", 2)
        heroTitle.text = if (on) "Моніторинг працює" else "Моніторинг зупинено"
        heroSub.text = if (on) Bus.lastText.lineSequence().first() + "\nКаналів: $n · перевірка кожні $every с"
        else "Каналів увімкнено: $n"
        heroDot.background = oval(col(if (on) R.color.ok else R.color.muted))
        if (on) {
            toggle.text = "■  Зупинити моніторинг"
            style(toggle, col(R.color.bad), col(R.color.on_accent), 16)
            chip.text = "● Працює"
            chip.setTextColor(col(R.color.ok))
            chip.background = shape(alpha(col(R.color.ok), 45), 20)
        } else {
            toggle.text = "▶  Почати моніторинг"
            style(toggle, col(R.color.accent), col(R.color.on_accent), 16)
            chip.text = "○ Зупинено"
            chip.setTextColor(col(R.color.muted))
            chip.background = shape(alpha(col(R.color.muted), 45), 20)
        }
    }

    private fun homePage(): View {
        val box = vbox()
        val hero = card()
        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL
        heroDot = View(this)
        val dp14 = lp(dp(14), dp(14))
        dp14.rightMargin = dp(12)
        top.addView(heroDot, dp14)
        val texts = vbox()
        heroTitle = tv("", 20f, col(R.color.text), true)
        heroSub = tv("", 13f, col(R.color.muted))
        heroSub.setLineSpacing(0f, 1.2f)
        texts.addView(heroTitle)
        texts.addView(heroSub)
        top.addView(texts, lp(0, -2, 1f))
        hero.addView(top)

        toggle = btn("", col(R.color.accent), col(R.color.on_accent)) {
            val i = Intent(this, MonitorService::class.java)
            if (Bus.running) stopService(i) else startForegroundService(i)
            ui.postDelayed({ refresh() }, 300)
            ui.postDelayed({ refresh() }, 1500)
        }
        toggle.textSize = 17f
        val tp = lp(-1, -2)
        tp.topMargin = dp(16)
        hero.addView(toggle, tp)
        val last = btn("▶  Програти останнє повідомлення", col(R.color.accent_soft), col(R.color.accent)) {
            if (Bus.running) Bus.playLast = true else toast("Спочатку натисніть «Почати моніторинг»")
        }
        val lastParams = lp(-1, -2)
        lastParams.topMargin = dp(10)
        hero.addView(last, lastParams)
        box.addView(hero)

        val h = tv("ОСТАННІ ПОВІДОМЛЕННЯ", 12f, col(R.color.muted), true)
        h.letterSpacing = 0.08f
        h.setPadding(dp(4), dp(4), 0, dp(8))
        box.addView(h)
        feed = vbox()
        box.addView(feed)
        return page(box)
    }

    private fun renderFeed(s: String) {
        feed.removeAllViews()
        val blocks = s.split("\n\n")
        val warns = blocks[0].lines().drop(1).filter { it.startsWith("⚠") }
        if (warns.isNotEmpty()) {
            val w = tv(warns.joinToString("\n"), 13f, col(R.color.bad))
            w.background = shape(alpha(col(R.color.bad), 30), 14)
            w.setPadding(dp(14), dp(10), dp(14), dp(10))
            val wp = lp(-1, -2)
            wp.bottomMargin = dp(12)
            feed.addView(w, wp)
        }
        val chans = blocks.drop(1)
        if (chans.isEmpty()) {
            val bad = blocks[0].startsWith("Помилка") || blocks[0].startsWith("Немає")
            val msg = if (bad) blocks[0] else "Тут з'являться повідомлення з каналів після запуску моніторингу."
            val e = tv(msg, 14f, col(if (bad) R.color.bad else R.color.muted))
            e.setPadding(dp(4), dp(4), dp(4), dp(4))
            feed.addView(e)
        }
        for (b in chans) {
            val lines = b.lines()
            val c = card()
            c.addView(tv(lines[0], 15f, col(R.color.accent), true))
            for (l in lines.drop(1)) {
                val t = tv(l, 14f, col(R.color.text))
                t.setPadding(0, dp(8), 0, 0)
                t.setLineSpacing(0f, 1.15f)
                c.addView(t)
            }
            feed.addView(c)
        }
    }

    private fun addChannel() {
        val n = Store.parseName(input.text.toString())
        if (n == null) {
            toast("Не вдалося розпізнати канал")
            return
        }
        val l = Store.channels(this)
        if (l.any { it.first.equals(n, true) }) {
            toast("Цей канал уже є в списку")
            return
        }
        l.add(n to true)
        Store.save(this, l)
        input.setText("")
        renderChannels()
        toast("Канал @$n додано")
    }

    private fun channelsPage(): View {
        val box = vbox()
        val add = card()
        add.addView(cardTitle("Додати канал"))
        input = field("Посилання або @назва каналу", "")
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        input.imeOptions = EditorInfo.IME_ACTION_DONE
        input.setOnEditorActionListener { _, a, _ ->
            if (a == EditorInfo.IME_ACTION_DONE) {
                addChannel()
                true
            } else false
        }
        add.addView(input)
        val hint = tv("Підійдуть t.me/назва, t.me/s/назва або @назва. Канал має бути публічним.", 12f, col(R.color.muted))
        hint.setPadding(dp(2), dp(6), 0, dp(12))
        add.addView(hint)
        val row = LinearLayout(this)
        val check = btn("Перевірити", col(R.color.accent_soft), col(R.color.accent)) {
            val n = Store.parseName(input.text.toString())
            if (n == null) toast("Не вдалося розпізнати канал") else runTest(n)
        }
        val addB = btn("Додати", col(R.color.accent), col(R.color.on_accent)) { addChannel() }
        val p1 = lp(0, -2, 1f)
        p1.rightMargin = dp(6)
        val p2 = lp(0, -2, 1f)
        p2.leftMargin = dp(6)
        row.addView(check, p1)
        row.addView(addB, p2)
        add.addView(row)
        box.addView(add)

        val h = tv("МОЇ КАНАЛИ", 12f, col(R.color.muted), true)
        h.letterSpacing = 0.08f
        h.setPadding(dp(4), dp(4), 0, dp(8))
        box.addView(h)
        channelsBox = vbox()
        box.addView(channelsBox)
        return page(box)
    }

    private fun renderChannels() {
        channelsBox.removeAllViews()
        val list = Store.channels(this)
        if (list.isEmpty()) {
            val empty = card()
            empty.addView(tv("Каналів ще немає. Додайте перший вище.", 14f, col(R.color.muted)))
            channelsBox.addView(empty)
        }
        for (ch in list) {
            val name = ch.first
            val c = card()
            val row = LinearLayout(this)
            row.gravity = Gravity.CENTER_VERTICAL
            val av = tv(name.take(1).uppercase(), 18f, col(R.color.accent), true)
            av.gravity = Gravity.CENTER
            av.background = oval(col(R.color.accent_soft))
            val ap = lp(dp(44), dp(44))
            ap.rightMargin = dp(12)
            row.addView(av, ap)
            val info = vbox()
            info.addView(tv("@$name", 16f, col(R.color.text), true))
            val sub = tv("", 12f, col(R.color.muted))
            fun paint(on: Boolean) {
                sub.text = if (on) "Моніторинг увімкнено" else "Вимкнено"
                sub.setTextColor(col(if (on) R.color.ok else R.color.muted))
            }
            paint(ch.second)
            info.addView(sub)
            val sw = Switch(this)
            tint(sw)
            sw.isChecked = ch.second
            sw.setOnCheckedChangeListener { _, v ->
                val l = Store.channels(this)
                val idx = l.indexOfFirst { it.first == name }
                if (idx >= 0) {
                    l[idx] = name to v
                    Store.save(this, l)
                }
                paint(v)
                refresh()
            }
            row.addView(info, lp(0, -2, 1f))
            row.addView(sw)
            c.addView(row)

            val row2 = LinearLayout(this)
            row2.setPadding(0, dp(14), 0, 0)
            val test = btn("Тест", col(R.color.accent_soft), col(R.color.accent)) { runTest(name) }
            val del = btn("Видалити", alpha(col(R.color.bad), 40), col(R.color.bad)) {
                AlertDialog.Builder(this)
                    .setTitle("Видалити @$name?")
                    .setMessage("Канал зникне зі списку моніторингу.")
                    .setPositiveButton("Видалити") { _, _ ->
                        val l = Store.channels(this)
                        l.removeAll { it.first == name }
                        Store.save(this, l)
                        renderChannels()
                    }
                    .setNegativeButton("Скасувати", null)
                    .show()
            }
            val q1 = lp(0, -2, 1f)
            q1.rightMargin = dp(6)
            val q2 = lp(0, -2, 1f)
            q2.leftMargin = dp(6)
            row2.addView(test, q1)
            row2.addView(del, q2)
            c.addView(row2)
            channelsBox.addView(c)
        }
        refresh()
    }

    private fun settingsPage(prefs: SharedPreferences): View {
        val box = vbox()

        val c1 = card()
        c1.addView(cardTitle("Моніторинг"))
        addSeek(c1, prefs, "interval", 1, 30, 2, "Інтервал перевірки", "%d с")
        addSeek(c1, prefs, "maxchars", 50, 1000, 200, "Макс. довжина тексту для озвучення", "%d симв.")
        addSwitch(c1, prefs, "tts", true, "Озвучувати текстові повідомлення", "Голосові повідомлення відтворюються завжди")
        addSwitch(c1, prefs, "mutecall", true, "Не озвучувати під час розмови по телефону")
        addSwitch(c1, prefs, "autostart", false, "Автозапуск після ввімкнення телефону", "Моніторинг стартує сам після перезавантаження")
        val battery = btn("Налаштування економії батареї", col(R.color.accent_soft), col(R.color.accent)) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        val bp = lp(-1, -2)
        bp.topMargin = dp(14)
        c1.addView(battery, bp)
        box.addView(c1)

        val c2 = card()
        c2.addView(cardTitle("Звук"))
        val sysRow = LinearLayout(this)
        sysRow.orientation = LinearLayout.HORIZONTAL
        sysRow.setPadding(0, dp(12), 0, 0)
        val sysValue = tv("", 14f, col(R.color.accent), true)
        sysRow.addView(tv("Гучність телефону (медіа)", 15f, col(R.color.text)), lp(0, -2, 1f))
        sysRow.addView(sysValue)
        sysBar = SeekBar(this)
        tint(sysBar)
        sysBar.max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        sysBar.progress = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        sysValue.text = (sysBar.progress * 100 / sysBar.max).toString() + "%"
        sysBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                sysValue.text = (p * 100 / sysBar.max).toString() + "%"
                if (fromUser) {
                    try {
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
                    } catch (e: Exception) {
                    }
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        c2.addView(sysRow)
        c2.addView(sysBar)
        addSeek(c2, prefs, "volume", 10, 100, 100, "Гучність додатка", "%d%%")
        box.addView(c2)

        val c3 = card()
        c3.addView(cardTitle("Голос озвучення"))

        val engineHint = tv("", 12f, col(R.color.muted))
        engineHint.setPadding(dp(2), dp(6), 0, 0)
        val geminiBox = vbox()
        val sherpaBox = vbox()

        val engines = listOf(
            Voice.ENGINE_SYSTEM to "Вбудований голос телефону",
            Voice.ENGINE_GOOGLE to "Google Translate",
            Voice.ENGINE_GEMINI to "Gemini",
            Voice.ENGINE_SHERPA to "Офлайн голос (Sherpa-ONNX)"
        )
        val hints = mapOf(
            Voice.ENGINE_SYSTEM to "Голос із налаштувань телефону (Google TTS, Samsung тощо). Потрібна встановлена українська мова. Якщо її немає, використовується голос Google Translate.",
            Voice.ENGINE_GOOGLE to "Безкоштовний голос Google Translate. Ключ не потрібен.",
            Voice.ENGINE_GEMINI to "Найприродніший голос. Потрібен ключ Gemini API. Без ключа або при вичерпанні ліміту використовується голос Google Translate.",
            Voice.ENGINE_SHERPA to "Повністю офлайн-синтез українською (Supertonic 3, чоловічий голос M1). Модель ~123 МБ докачується один раз при першому використанні, далі працює без інтернету."
        )
        val cur = Voice.engine(this)
        val group = RadioGroup(this)
        group.orientation = LinearLayout.VERTICAL
        group.setPadding(0, dp(8), 0, 0)
        for ((k, label) in engines) {
            val rb = RadioButton(this)
            rb.id = View.generateViewId()
            rb.text = label
            rb.tag = k
            rb.textSize = 15f
            rb.setTextColor(col(R.color.text))
            rb.buttonTintList = ColorStateList.valueOf(col(R.color.accent))
            group.addView(rb)
            if (k == cur) rb.isChecked = true
        }
        fun applyEngine(k: String) {
            engineHint.text = hints[k]
            geminiBox.visibility = if (k == Voice.ENGINE_GEMINI) View.VISIBLE else View.GONE
            sherpaBox.visibility = if (k == Voice.ENGINE_SHERPA) View.VISIBLE else View.GONE
        }
        applyEngine(cur)
        group.setOnCheckedChangeListener { g, id ->
            val k = g.findViewById<RadioButton>(id)?.tag as? String ?: return@setOnCheckedChangeListener
            prefs.edit().putString("engine", k).apply()
            applyEngine(k)
        }
        c3.addView(group)
        c3.addView(engineHint)

        val sherpaStatus = tv(Sherpa.statusText(this), 12f, col(R.color.muted))
        sherpaStatus.setPadding(dp(2), dp(10), 0, 0)
        val sherpaBtn = btn("Завантажити офлайн-модель", col(R.color.accent_soft), col(R.color.accent)) {}
        sherpaBtn.setOnClickListener {
            if (Sherpa.ready(this)) {
                toast("Модель уже завантажена")
                return@setOnClickListener
            }
            sherpaBtn.isEnabled = false
            sherpaBtn.alpha = 0.5f
            toast("Завантажую модель…")
            Thread {
                try {
                    val f = File(cacheDir, "warmup.wav")
                    Sherpa.speak(this, "Модель завантажено.", f)
                    f.delete()
                    ui.post { toast("Офлайн-модель готова") }
                } catch (e: Exception) {
                    ui.post {
                        if (!isFinishing) {
                            AlertDialog.Builder(this)
                                .setTitle("Помилка завантаження моделі")
                                .setMessage(e.message ?: e.javaClass.simpleName)
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
                ui.post {
                    sherpaBtn.isEnabled = true
                    sherpaBtn.alpha = 1f
                    sherpaStatus.text = Sherpa.statusText(this)
                }
            }.start()
        }
        val sp = lp(-1, -2)
        sp.topMargin = dp(10)
        sherpaBox.addView(sherpaStatus)
        sherpaBox.addView(sherpaBtn, sp)
        Sherpa.onStatus = { s ->
            ui.post {
                sherpaStatus.text = s ?: Sherpa.statusText(this)
                if (s == null && !sherpaBtn.isEnabled) {
                    sherpaBtn.isEnabled = true
                    sherpaBtn.alpha = 1f
                }
            }
        }
        c3.addView(sherpaBox)

        val how = tv("Як отримати ключ", 15f, col(R.color.text), true)
        how.setPadding(0, dp(14), 0, dp(4))
        geminiBox.addView(how)
        val steps = tv("", 14f, col(R.color.text))
        steps.autoLinkMask = Linkify.WEB_URLS
        steps.text = "1. Відкрийте https://aistudio.google.com/apikey і увійдіть у Google-акаунт.\n2. Натисніть «Create API key» і скопіюйте ключ.\n3. Вставте його в поле «Ключ Gemini API» нижче й натисніть «Перевірити голос». Якщо щось не так, з'явиться вікно з текстом помилки."
        steps.movementMethod = LinkMovementMethod.getInstance()
        steps.setLinkTextColor(col(R.color.accent))
        steps.setLineSpacing(0f, 1.2f)
        geminiBox.addView(steps)
        val open = btn("Відкрити сторінку отримання ключа", col(R.color.accent_soft), col(R.color.accent)) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        }
        val op = lp(-1, -2)
        op.topMargin = dp(12)
        geminiBox.addView(open, op)
        addField(geminiBox, prefs, "gkey", "Ключ Gemini API", "", true)
        addField(geminiBox, prefs, "gmodel", "Модель", Voice.DEFAULT_MODEL)
        addField(geminiBox, prefs, "gvoice", "Голос", Voice.DEFAULT_VOICE)
        val note = tv("Голоси: Kore, Zephyr, Aoede (жіночі), Charon, Puck, Fenrir (чоловічі).", 12f, col(R.color.muted))
        note.setPadding(dp(2), dp(8), 0, 0)
        geminiBox.addView(note)
        c3.addView(geminiBox)

        val testV = btn("Перевірити голос", col(R.color.accent), col(R.color.on_accent)) { testVoice() }
        val tp = lp(-1, -2)
        tp.topMargin = dp(14)
        c3.addView(testV, tp)
        box.addView(c3)

        return page(box)
    }

    private fun addSeek(
        parent: LinearLayout,
        prefs: SharedPreferences,
        key: String,
        min: Int,
        max: Int,
        def: Int,
        title: String,
        fmt: String
    ) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(12), 0, 0)
        val value = tv("", 14f, col(R.color.accent), true)
        row.addView(tv(title, 15f, col(R.color.text)), lp(0, -2, 1f))
        row.addView(value)
        val bar = SeekBar(this)
        tint(bar)
        val v = prefs.getInt(key, def)
        bar.max = max - min
        bar.progress = v - min
        value.text = String.format(fmt, v)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                value.text = String.format(fmt, p + min)
                prefs.edit().putInt(key, p + min).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        parent.addView(row)
        parent.addView(bar)
    }

    private fun addSwitch(parent: LinearLayout, prefs: SharedPreferences, key: String, def: Boolean, label: String, sub: String? = null) {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(14), 0, 0)
        val info = vbox()
        info.addView(tv(label, 15f, col(R.color.text)))
        if (sub != null) info.addView(tv(sub, 12f, col(R.color.muted)))
        row.addView(info, lp(0, -2, 1f))
        val sw = Switch(this)
        tint(sw)
        sw.isChecked = prefs.getBoolean(key, def)
        sw.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(key, v).apply() }
        row.addView(sw)
        parent.addView(row)
    }

    private fun addField(parent: LinearLayout, prefs: SharedPreferences, key: String, label: String, def: String, secret: Boolean = false) {
        val l = tv(label, 12f, col(R.color.muted))
        l.setPadding(dp(2), dp(14), 0, dp(4))
        parent.addView(l)
        val e = field(label, prefs.getString(key, def).orEmpty())
        if (secret) e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        e.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString(key, s.toString().trim()).apply()
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        parent.addView(e)
    }

    private fun runTest(name: String) {
        toast("Перевіряю @$name…")
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

    private fun testVoice() {
        toast("Запитую голос…")
        val engine = Voice.engine(this)
        Thread {
            val text = "Це перевірка голосу. Монітор працює."
            val f = File(cacheDir, if (engine == Voice.ENGINE_GOOGLE) "test.mp3" else "test.wav")
            val err = try {
                when (engine) {
                    Voice.ENGINE_SYSTEM -> Voice.system(this, text, f)
                    Voice.ENGINE_GOOGLE -> Voice.translate(text, f)
                    Voice.ENGINE_SHERPA -> Sherpa.speak(this, text, f)
                    else -> Voice.gemini(this, text, f)
                }
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            ui.post {
                if (isFinishing) return@post
                if (err != null) {
                    AlertDialog.Builder(this)
                        .setTitle(
                            when (engine) {
                                Voice.ENGINE_SYSTEM -> "Помилка вбудованого голосу"
                                Voice.ENGINE_GOOGLE -> "Помилка Google Translate"
                                Voice.ENGINE_SHERPA -> "Помилка офлайн голосу"
                                else -> "Помилка Gemini"
                            }
                        )
                        .setMessage(err)
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    val v = getSharedPreferences("s", MODE_PRIVATE).getInt("volume", 100) / 100f
                    val mp = MediaPlayer()
                    mp.setDataSource(f.absolutePath)
                    mp.setVolume(v, v)
                    mp.setOnCompletionListener { it.release() }
                    mp.prepare()
                    mp.start()
                }
            }
        }.start()
    }
}
